package io.mediachain.copycat

import java.util.function.Consumer
import java.util.{Random, Timer, TimerTask}
import java.util.concurrent.{ExecutorService, Executors}
import java.time.Duration
import io.atomix.copycat.Operation
import io.atomix.copycat.client.{CopycatClient, ConnectionStrategy}
import io.atomix.catalyst.transport.Address
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{Future, Promise}
import scala.util.{Try, Success, Failure}
import scala.compat.java8.FutureConverters
import scala.collection.JavaConversions._

import cats.data.Xor

import io.mediachain.copycat.StateMachine._
import io.mediachain.protocol.Datastore._
import io.mediachain.protocol.Transactor._

class Client(client: CopycatClient) extends JournalClient {
  import scala.concurrent.ExecutionContext.Implicits.global
  import Client._
  import ClientState._
  
  @volatile private var shutdown = false
  @volatile private var state: ClientState = Disconnected
  private var cluster: Option[List[Address]] = None
  private var exec: ExecutorService = Executors.newSingleThreadExecutor()
  private var listeners: Set[JournalListener] = Set()
  private var stateListeners: Set[ClientStateListener] = Set()
  private val logger = LoggerFactory.getLogger(classOf[Client])
  private val timer = new Timer(true) // runAsDaemon
  private val maxRetries = 5
  
  client.onStateChange(new Consumer[CopycatClient.State] {
    def accept(state: CopycatClient.State) {
      onStateChange(state)
    }})
  
  // submit a state machine operation with retry logic to account
  // for potentially transient client connectivity errors
  private def submit[T](op: Operation[T], retry: Int = 0): Future[T] = {
    (state, shutdown) match {
      case (_, true) =>
        Future.failed {new ClientException("Copycat client has shutdown")}

      case (Disconnected, _) => 
        Future.failed {new ClientException("Copycat client is disconnected")}

      case (Suspended, _) =>
        if (retry < maxRetries) {
          logger.info("Copycat client is suspended; delaying "+ op)
          backoff(retry).flatMap { retry =>
            logger.info("Retrying operation " + op)
            submit(op, retry + 1)
          }
        } else {
          logger.warn("Copycat client is unavailable; aborting " + op)
          Future.failed {new ClientException("Copycat client unavailable; giving up")}
        }
        
      case (Connected, _) =>
        val fut = FutureConverters.toScala(client.submit(op))
        if (retry < maxRetries) {
          fut.recoverWith { 
            case e: Throwable =>
              logger.error("Copycat client error in " + op, e)
              backoff(retry).flatMap { retry => 
                logger.info("Retrying operation " + op)
                submit(op, retry + 1)
              }
          }
        } else {
          fut
        }
    }
  }
  
  private def backoff(retry: Int): Future[Int] = {
    val promise = Promise[Int]
    val delay = Client.randomBackoff(retry)
    
    logger.info("Backing off for " + delay + " ms")
    timer.schedule(new TimerTask {
      def run {
        promise.complete(Try(retry))
      }
    }, delay)
    promise.future
  }
  
  private def onStateChange(cstate: CopycatClient.State) {
    cstate match {
      case CopycatClient.State.CONNECTED => 
        state = Connected
        logger.info("Copycat session connected")
        emitStateChange(Connected)
        
      case CopycatClient.State.SUSPENDED =>
        state = Suspended
        if (!shutdown) {
          logger.info("Copycat session suspended; attempting to recover")
          emitStateChange(Suspended)
          recover()
        }
        
      case CopycatClient.State.CLOSED =>
        if (!shutdown) {
          val ostate = state
          state = Suspended
          logger.info("Copycat session closed; attempting to reconnect")
          if (ostate != state) {
            emitStateChange(Suspended)
          }
          reconnect()
        } else {
          disconnect("Copycat session closed")
        }
    }
  }
  
  private def emitStateChange(stateChange: ClientState) {
    exec.submit(new Runnable {
      def run {
        try {
          stateListeners.foreach(_.onStateChange(stateChange))
        } catch {
          case e: Throwable =>
            logger.error("Error dispatching state change", e)
        }
      }})
  }
  
  private def disconnect(what: String) {
    state = Disconnected
    logger.info(what)
    emitStateChange(Disconnected)
  }
  
  private def recover() {
    val cf = client.recover()
    exec.submit(new Runnable {
      def run {
        try {
          cf.join()
          logger.info("Copycat session recovered")
        } catch {
          case e: Throwable =>
            logger.error("Copycat session recovery failed", e)
        }
      }})
  }
  
  private def reconnect() {
    def loop(addresses: List[Address], retry: Int) {
      if (!shutdown) {
        if (retry < maxRetries) {
          logger.info("Reconnecting to " + addresses)
          Try(client.connect(addresses).join()) match {
            case Success(_) => 
              logger.info("Successfully reconnected")
              if (shutdown) {
                // lost race with user calling #close
                // make sure the client is closed
                client.close()
              }
            case Failure(e) =>
              logger.error("Connection error", e)
              val sleep = Client.randomBackoff(retry)
              logger.info("Backing off reconnect for " + sleep + " ms")
              Thread.sleep(sleep)
              loop(addresses, retry + 1) 
          }
        } else {
          disconnect("Failed to reconnect; giving up.")
        }
      } else {
        disconnect("Client has shutdown")
      }
    }
    
    exec.submit(new Runnable {
      def run { 
        try {
          cluster.foreach { addresses => loop(addresses, 0) }
        } catch {
          case e: InterruptedException => 
            disconnect("Client reconnect interrupted")
          case e: Throwable =>
            logger.error("Unhandled exception in Client#reconnect", e)
            disconnect("Client reconnect failed")
        }
      }})
  }
  
  def addStateListener(listener: ClientStateListener) {
    stateListeners += listener
  }

  // Journal 
  def insert(rec: CanonicalRecord): Future[Xor[JournalError, CanonicalEntry]] =
    submit(JournalInsert(rec))

  def update(ref: Reference, cell: ChainCell): Future[Xor[JournalError, ChainEntry]] =
    submit(JournalUpdate(ref, cell))
  
  def lookup(ref: Reference): Future[Xor[JournalError, Option[Reference]]] = 
    submit(JournalLookup(ref))
  
  def currentBlock: Future[JournalBlock] =
    submit(JournalCurrentBlock())
  
  // JournalClient
  def connect(addresses: List[String]) {
    if (!shutdown) {
      val clusterAddresses = addresses.map {a => new Address(a)}
      cluster = Some(clusterAddresses)
      client.connect(clusterAddresses).join()
    } else {
      throw new IllegalStateException("client has been shutdown")
    }
  }
  
  def close() {
    if (!shutdown) {
      shutdown = true
      exec.shutdownNow()
      client.close.join()
    }
  }
  
  def listen(listener: JournalListener) {
    if (listeners.isEmpty) {
      listeners = Set(listener)
      client.onEvent("journal-commit", 
                     new Consumer[JournalCommitEvent] { 
        def accept(evt: JournalCommitEvent) {
          listeners.foreach(_.onJournalCommit(evt.entry))
        }
      })
      client.onEvent("journal-block", 
                     new Consumer[JournalBlockEvent] { 
        def accept(evt: JournalBlockEvent) { 
          listeners.foreach(_.onJournalBlock(evt.ref, evt.index))
        }
      })
    } else {
      listeners += listener
    }
  }
}

object Client {

  class ClientException(what: String) extends RuntimeException(what)

  sealed abstract class ClientState
  
  object ClientState {
    case object Connected extends ClientState
    case object Suspended extends ClientState
    case object Disconnected extends ClientState
  }

  trait ClientStateListener {
    def onStateChange(state: ClientState): Unit
  }
  
  class ClientConnectionStrategy extends ConnectionStrategy {
    val maxRetries = 10
    val logger = LoggerFactory.getLogger(classOf[ClientConnectionStrategy])
    
    def attemptFailed(at: ConnectionStrategy.Attempt) {
      val retry = at.attempt - 1
      if (retry < maxRetries) {
        val sleep = randomBackoff(retry)
        logger.info(s"Connection attempt ${at.attempt} failed. Retrying in ${sleep} ms")
        at.retry(Duration.ofMillis(sleep))
      } else {
        logger.error(s"Connection attempt ${at.attempt} failed; giving up.")
        at.fail()
      }
    }
  }

  val random = new Random  
  def randomBackoff(retry: Int, max: Int = 60) = 
    random.nextInt(Math.min(max, Math.pow(2, retry).toInt) * 1000)
  
  def build(sslConfig: Option[Transport.SSLConfig] = None): Client = {
    val client = CopycatClient.builder()
      .withTransport(Transport.build(2, sslConfig))
      .withConnectionStrategy(new ClientConnectionStrategy)
      .build()
    Serializers.register(client.serializer)
    new Client(client)
  }
}
