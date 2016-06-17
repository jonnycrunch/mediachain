package io.mediachain.transactor

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import io.mediachain.copycat.{Client, Transport}
import io.mediachain.protocol.Datastore.JournalBlock
import io.mediachain.util.Properties

object JournalCurrentBlock {
  def parseArgs(args: Array[String]): (String, Option[Transport.SSLConfig]) = {
    args match {
      case Array(server) => (server, None)
      case Array(server, config) =>
        val props = Properties.load(config)
        (server, Transport.SSLConfig.fromProperties(props))
      case _ => 
        throw new RuntimeException("Expected arguments: server-address [client-config]")
    }
  }
  
  def main(args: Array[String]) {
    val (server, sslConfig) = parseArgs(args)
    val client = Client.build(sslConfig)
    client.connect(server)
    val block = Await.result(client.currentBlock, Duration.Inf)
    println(s"Current block is ${block.index}; chain pointer is ${block.chain}")
    client.close()
  }
}
