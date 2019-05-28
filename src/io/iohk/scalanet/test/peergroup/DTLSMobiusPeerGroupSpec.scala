package io.iohk.scalanet.peergroup

import java.security.KeyStore

import io.iohk.decco.Codec
import io.iohk.scalanet.NetUtils.{aRandomAddress, isListeningUDP}
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import io.iohk.decco.auto._

//import scala.concurrent.Future
//import scala.util.Random
import monix.execution.Scheduler.Implicits.global
import io.iohk.scalanet.TaskValues._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._
import scala.concurrent.duration._

class DTLSMobiusPeerGroupSpec extends FlatSpec {
  implicit val patienceConfig: ScalaFutures.PatienceConfig = PatienceConfig(5 seconds)

  behavior of "DTLSMobiusPeerGroup"

//  it should "send and receive a message" in withTwoRandomDTLSPeerGroups[String] { (alice, bob) =>
//    val alicesMessage = Random.alphanumeric.take(1024 * 4).mkString
//    val bobsMessage = Random.alphanumeric.take(1024 * 4).mkString
//
//    val bobReceived: Future[String] = bob.server().mergeMap(channel => channel.in).headL.runToFuture
//    bob.server().foreach(channel => channel.sendMessage(bobsMessage).runToFuture)
//
//    val aliceClient = alice.client(bob.processAddress).evaluated
//    val aliceReceived = aliceClient.in.headL.runToFuture
//    aliceClient.sendMessage(alicesMessage).runToFuture
//
//    bobReceived.futureValue shouldBe alicesMessage
//    aliceReceived.futureValue shouldBe bobsMessage
//  }

  it should "shutdown cleanly" in {
    val pg1 = randomDTLSPeerGroup[String]
    isListeningUDP(pg1.config.bindAddress) shouldBe true

    pg1.shutdown().evaluated

    isListeningUDP(pg1.config.bindAddress) shouldBe false
  }

  def withTwoRandomDTLSPeerGroups[M](
      testCode: (DTLSMobiusPeerGroup[M], DTLSMobiusPeerGroup[M]) => Any
  )(implicit codec: Codec[M]): Unit = {
    val pg1 = randomDTLSPeerGroup
    val pg2 = randomDTLSPeerGroup
    try {
      testCode(pg1, pg2)
    } finally {
      pg1.shutdown()
      pg2.shutdown()
    }
  }

  def randomDTLSPeerGroup[M](implicit codec: Codec[M]): DTLSMobiusPeerGroup[M] = {
    val keystorePassword = "GfUNokaofNh6"
    val keystore = KeyStore.getInstance("JKS")
    keystore.load(getClass.getClassLoader.getResourceAsStream("dtls-demo.jks"), keystorePassword.toCharArray)

    val config = DTLSMobiusPeerGroup.Config(aRandomAddress(), keystore, keystorePassword)
    PeerGroup.createOrThrow(new DTLSMobiusPeerGroup[M](config), config)
  }

}
