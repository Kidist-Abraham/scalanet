// import some peer group classes
import io.iohk.scalanet.peergroup.future._
import io.iohk.scalanet.peergroup._
import scala.concurrent.Future

import java.net.InetSocketAddress
import java.nio.ByteBuffer

object Try {

//val config = TCPPeerGroup.Config(new InetSocketAddress(???))

//val tcp = TCPPeerGroup.createOrThrow(config)

// send a message
//val messageF: Future[Unit] = tcp.sendMessage(new InetSocketAddress("example.com", 80), ByteBuffer.wrap("Hello!".getBytes))

// receive messages
//tcp.messageStream.foreach((b: ByteBuffer) => ())

def main(ar:Array[String])={
val a = "Kidist"
println(a)
}
}
 
