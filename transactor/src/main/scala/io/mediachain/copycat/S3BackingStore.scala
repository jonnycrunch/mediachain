package io.mediachain.copycat

import java.util.concurrent.{ExecutorService, Executors, TimeUnit}
import java.io.{File, FileOutputStream}
import com.amazonaws.AmazonClientException
import com.amazonaws.auth.{AWSCredentials, BasicAWSCredentials}
import com.amazonaws.services.s3.AmazonS3Client
import org.slf4j.{Logger, LoggerFactory}
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.collection.JavaConversions._
import scala.util.{Try, Success, Failure}
import cats.data.Xor
import io.mediachain.protocol.Transactor._
import io.mediachain.protocol.Datastore._
import io.mediachain.protocol.CborSerialization._
import io.mediachain.copycat.Client.{ClientState, ClientStateListener}
import io.mediachain.datastore.DynamoDatastore
import io.mediachain.util.Properties

class S3BackingStore(config: S3BackingStore.Config)
extends JournalListener with ClientStateListener with AutoCloseable {
  import S3BackingStore.multihashRef
  private val logger = LoggerFactory.getLogger(classOf[S3BackingStore])
  private val exec = Executors.newScheduledThreadPool(Math.max(4, Runtime.getRuntime.availableProcessors))
  private val datastore = new DynamoDatastore(config.dynamo)
  private val s3 = new AmazonS3Client()
  private val s3bucket = config.s3bucket
  private val client = Client.build(config.sslConfig)
  private var cluster: Option[List[String]] = None
  private var blocks: Set[Reference] = Set()
  
  client.addStateListener(this)

  def connect(addrs: List[String]) {
    cluster = Some(addrs)
    client.connect(addrs)
    client.listen(this)
  }
  
  def close() {
    exec.shutdownNow()
    client.close()
    datastore.close()
    s3.shutdown()
  }
  
  // Client state listener
  def onStateChange(state: ClientState) {
    state match {
      case ClientState.Connected =>
        logger.info("Client connected; scheduling blockchain catchup")
        exec.submit(new Runnable {
          def run { withErrorLog(writeCurrentBlock()) }
        })
      case ClientState.Suspended => ()
      case ClientState.Disconnected =>
        logger.info("Client disconnected; scheduling reconnect")
        exec.submit(new Runnable {
          def run { withErrorLog(reconnect()) }
        })
    }
  }
  
  // JournalListener
  def onJournalCommit(entry: JournalEntry) {}

  def onJournalBlock(ref: Reference, index: BigInt) {
    logger.info(s"New block ${ref} (${index})")
    execWriteBlock(ref)
  }
  
  private def withErrorLog(expr: => Unit) {
    try {
      expr
    } catch {
      case e: Throwable =>
        logger.error("Unhandled exception in task", e)
    }
  }
  
  private def writeCurrentBlock() {
    logger.info("Fetching current block")
    val op = client.currentBlock
    val block = Await.result(op, Duration.Inf)
    logger.info("Current blockchain pointer is " + block.chain)
    block.chain.foreach { execWriteBlock(_) }
  }
  
  private def execWriteBlock(ref: Reference) {
    this.synchronized {
      if (!blocks.contains(ref)) {
        blocks += ref
        exec.submit(new Runnable {
          def run { withErrorLog(writeBlock(ref)) }
        })
      }
    }
  }
  
  private def writeBlock(ref: Reference) {
    val mref = multihashRef(ref)
    val ref58 = mref.multihash.base58
    logger.info(s"Fetching block ${ref58}")
    val block = fetchBlock(mref)
    block.chain.foreach { execWriteBlock(_) }
    // check S3 first to see if we have already archived the block
    // (perhaps in a previous run)
    val listing = withBackoffRetry(s3.listObjects(s3bucket, ref58))
    if (listing.getObjectSummaries.isEmpty) {
      writeBlockArchive(mref, block)
    } else {
      logger.info(s"Block archive ${ref58} is already stored; skipping")
    }
  }
  
  private def writeBlockArchive(ref: MultihashReference, block: JournalBlock) {
    import sys.process._
    val archive = fetchBlockArchive(ref, block)
    val ref58 = ref.multihash.base58
    val key = ref58 + ":" + block.index + ".gz"
    logger.info(s"Writing block archive ${ref58} -> ${key}")
    val path = "/tmp/" + ref58
    val ostream = new FileOutputStream(path)
    ostream.write(archive.toCborBytes)
    ostream.flush()
    ostream.close()
    s"gzip ${path}".!
    val gzpath = path + ".gz"
    withBackoffRetry(s3.putObject(s3bucket, key, new File(gzpath)))
    s"rm ${gzpath}".!
  }
  
  private def withBackoffRetry[T](op: => T) = {
    def loop(retry: Int): T = {
      Try(op) match {
        case Success(res) => res
          
        case Failure(err: AmazonClientException) =>
          logger.error("AWS error", err)
          val backoff = Client.randomBackoff(retry)
          logger.info(s"Retrying in ${backoff} ms")
          Thread.sleep(backoff)
          loop(retry + 1)
          
        case Failure(err: Throwable) =>
          throw err
      }
    }
    
    loop(0)
  }
  
  private def fetchBlock(ref: MultihashReference) =
    fetchObject[JournalBlock](ref)
  
  private def fetchBlockArchive(ref: MultihashReference, block: JournalBlock) = {
    def addEntry(data: Map[Reference, Array[Byte]], ref: Reference) = {
      val bytes = fetchData(multihashRef(ref))
      var newdata = data + (ref -> bytes)
      val rec = decodeObject[Record](bytes)
      rec.metaSource.foreach { ref =>
        newdata += (ref -> fetchData(multihashRef(ref)))
      }
      newdata
    }
    
    logger.info(s"Fetching block archive ${ref.multihash.base58}")
    var data: Map[Reference, Array[Byte]] = Map()
    block.entries.foreach {
      case entry: CanonicalEntry => 
        data = addEntry(data, entry.ref)
      case entry: ChainEntry =>
        data = addEntry(data, entry.chain)
    }
    
    JournalBlockArchive(ref, block, data)
  }
  
  private def fetchObject[T <: CborSerializable](ref: MultihashReference) =
    decodeObject[T](fetchData(ref))
  
  private def decodeObject[T <: CborSerializable](bytes: Array[Byte]) =
    fromCborBytes[T](bytes) match {
      case Xor.Right(obj) => obj
      case Xor.Left(err) => throw new RuntimeException("Block deserialization error " + err)
    }

  private def fetchData(ref: MultihashReference) = {
    def loop(ref: MultihashReference, retry: Int): Array[Byte] = {
      lazy val ref58 = ref.multihash.base58
      withBackoffRetry(datastore.getData(ref.multihash)) match {
        case Some(bytes) => bytes
        case None =>
          // hasn't reached the store yet, backoff and retry
          val backoff = Client.randomBackoff(retry)
          logger.info(s"Missing data for ${ref58}; retrying in ${backoff} ms")
          Thread.sleep(backoff)
          loop(ref, retry + 1)
      }
    }
    
    loop(ref, 0)
  }
    
  private def reconnect(retry: Int = 0) {
    try {
      cluster.foreach { addrs =>
        logger.info(s"Attempting reconnect to ${addrs}")
        client.connect(addrs)
      }
    } catch {
      case e: Throwable =>
        logger.error("Error connecting to cluster", e)
        val backoff = Client.randomBackoff(retry, 300)
        logger.info(s"Retrying reconnect in ${backoff} ms")
        exec.schedule(new Runnable {
          def run { withErrorLog(reconnect(retry + 1)) }
        }, backoff, TimeUnit.MILLISECONDS)
    }
  }
}

object S3BackingStore {
  def multihashRef(ref: Reference): MultihashReference = {
    ref match {
      case xref: MultihashReference => 
        // Leeloo Dallas, mul-ti-hash!
        xref
      case _ => throw new RuntimeException("bogus reference " + ref)
    }
  }
  
  case class Config(
    s3bucket: String,
    dynamo: DynamoDatastore.Config,
    sslConfig: Option[Transport.SSLConfig]
  )

  object Config {
    def fromProperties(conf: Properties) = {
      val s3bucket = conf.getq("io.mediachain.transactor.s3.bucket")
      val dynamoConfig = DynamoDatastore.Config.fromProperties(conf)
      val sslConfig = Transport.SSLConfig.fromProperties(conf)
      Config(s3bucket, dynamoConfig, sslConfig)
    }
  }
  
}
