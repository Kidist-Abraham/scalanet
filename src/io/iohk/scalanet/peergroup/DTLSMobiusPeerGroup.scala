package io.iohk.scalanet.peergroup

import java.net.{InetSocketAddress, SocketAddress}
import java.security.{KeyStore, SecureRandom}
import java.util.concurrent.ConcurrentHashMap

import com.mobius.software.iot.dal.crypto
import monix.eval.Task
import monix.reactive.Observable
import com.mobius.software.iot.dal.crypto._
import io.iohk.decco.{Codec, DecodeFailure}
import io.iohk.scalanet.peergroup.DTLSMobiusPeerGroup.Config
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.{getChannelId, toTask}
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import monix.reactive.subjects.{PublishSubject, ReplaySubject, Subject}
import org.bouncycastle.crypto.tls.ProtocolVersion
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

class DTLSMobiusPeerGroup[M](val config: Config)(implicit codec: Codec[M]) extends PeerGroup[InetMultiAddress, M] {
  private val log = LoggerFactory.getLogger(getClass)
  private val channelSubject = PublishSubject[Channel[InetMultiAddress, M]]()

  private val workerGroup = new NioEventLoopGroup()

  private val activeChannels = new ConcurrentHashMap[Seq[Byte], ChannelImpl]().asScala

  private def nettyChannels: ConcurrentHashMap[SocketAddress, channel.Channel] = new ConcurrentHashMap()

  private val serverHandshakeHandler = new HandshakeHandler {
    override def handleHandshake(messageType: MessageType, byteBuf: ByteBuf): Unit = {
      log.debug(s"handleHandshake $messageType")
    }

    override def postProcessHandshake(messageType: MessageType, byteBuf: ByteBuf): Unit = {
      log.debug(s"handleHandshake $messageType")
    }
  }

  private val serverStateHandler = new DtlsStateHandler {
    override def handshakeStarted(inetSocketAddress: InetSocketAddress, nettyChannel: channel.Channel): Unit = {
      log.debug(s"handshakeStarted $inetSocketAddress")
    }

    override def handshakeCompleted(inetSocketAddress: InetSocketAddress, nettyChannel: channel.Channel): Unit = {
      log.debug(s"handshakeCompleted $inetSocketAddress")
    }

    override def errorOccured(inetSocketAddress: InetSocketAddress, nettyChannel: channel.Channel): Unit = {
      log.debug(s"errorOccured $inetSocketAddress")
    }
  }
  private val clientHandshakeHandler = new HandshakeHandler {
    override def handleHandshake(messageType: MessageType, byteBuf: ByteBuf): Unit = {
      log.debug(s"handleHandshake $messageType")
    }

    override def postProcessHandshake(messageType: MessageType, byteBuf: ByteBuf): Unit = {
      log.debug(s"handleHandshake $messageType")
    }
  }

  private val clientStateHandler = new DtlsStateHandler {
    override def handshakeStarted(inetSocketAddress: InetSocketAddress, nettyChannel: channel.Channel): Unit = {
      log.debug(s"handshakeStarted $inetSocketAddress")
    }

    override def handshakeCompleted(inetSocketAddress: InetSocketAddress, nettyChannel: channel.Channel): Unit = {
      log.debug(s"handshakeCompleted $inetSocketAddress")
    }

    override def errorOccured(inetSocketAddress: InetSocketAddress, nettyChannel: channel.Channel): Unit = {
      log.debug(s"errorOccured $inetSocketAddress")
    }
  }

  private val contextMap: AsyncDtlsServerContextMap =
    new AsyncDtlsServerContextMap(serverHandshakeHandler, serverStateHandler)

  private val dtlsServerHandler =
    new AsyncDtlsServerHandler(config.keystore, config.keystorePassword, contextMap, nettyChannels, serverStateHandler)

  private val serverBootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioDatagramChannel])
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
    .handler(new ChannelInitializer[NioDatagramChannel]() {
      override def initChannel(nettyChannel: NioDatagramChannel): Unit = {
        nettyChannel
          .pipeline()
          .addLast(dtlsServerHandler)
          .addLast(new ChannelInboundHandlerAdapter() {
            override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {

              val datagram = msg.asInstanceOf[DatagramPacket]
              val remoteAddress = datagram.sender()
              val localAddress = processAddress.inetSocketAddress //datagram.recipient()

              val messageE: Either[DecodeFailure, M] = codec.decode(datagram.content().nioBuffer().asReadOnlyBuffer())

              log.debug(s"Server read $messageE")
              val nettyChannel: NioDatagramChannel = ctx.channel().asInstanceOf[NioDatagramChannel]
              val channelId = getChannelId(remoteAddress, localAddress)

              if (activeChannels.contains(channelId)) {
                log.debug(s"Channel with id $channelId found in active channels table.")
                val channel = activeChannels(channelId)
                messageE.foreach(message => channel.messageSubject.onNext(message))
              } else {
                val channel = new ChannelImpl(nettyChannel, localAddress, remoteAddress, ReplaySubject[M]())
                log.debug(s"Channel with id $channelId NOT found in active channels table. Creating a new one")
                activeChannels.put(channelId, channel)
                channelSubject.onNext(channel)
                messageE.foreach(message => channel.messageSubject.onNext(message))
              }
            }
          })
      }
    })

  private val serverBind: ChannelFuture = serverBootstrap.bind(config.bindAddress)

  /*
  		clientBootstrap.group(clientGroup);
		clientBootstrap.channel(NioDatagramChannel.class);
		clientBootstrap.option(ChannelOption.TCP_NODELAY, true);
		clientBootstrap.option(ChannelOption.SO_KEEPALIVE, true);

		final InetSocketAddress remoteAddress = new InetSocketAddress(remoteHost, remotePort);
		final InetSocketAddress localAddress = new InetSocketAddress(host, 0);
		final DtlsClient client=this;

		clientBootstrap.handler(new ChannelInitializer<NioDatagramChannel>()
		{
			@Override
			protected void initChannel(NioDatagramChannel socketChannel) throws Exception
			{
				protocol=new AsyncDtlsClientProtocol(new AsyncDtlsClient(keystore, keystorePassword,null),SECURE_RANDOM, socketChannel,handshakeHandler,client, remoteAddress, true,ProtocolVersion.DTLSv12);
				socketChannel.pipeline().addLast(new AsyncDtlsClientHandler(protocol,client));
				socketChannel.pipeline().addLast(new DummyMessageHandler(client));
			}
		});

   */
  private val secureRandom = new SecureRandom()
  private val clientBootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioDatagramChannel])
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)

  override def initialize(): Task[Unit] =
    toTask(serverBind).map(_ => log.info(s"Server bound to address ${config.bindAddress}"))

  override def processAddress: InetMultiAddress = config.processAddress

  private class HoldingDtlsClientHandler(
      val clientProto: AsyncDtlsClientProtocol,
      val clientStateHandler: DtlsStateHandler
  ) extends AsyncDtlsClientHandler(clientProto, clientStateHandler)

  override def client(to: InetMultiAddress): Task[Channel[InetMultiAddress, M]] = {
    val cb = clientBootstrap
      .clone()
      .handler(new ChannelInitializer[NioDatagramChannel]() {
        override def initChannel(nettyChannel: NioDatagramChannel): Unit = {

          val clientProto = new crypto.AsyncDtlsClientProtocol(
            new AsyncDtlsClient(config.keystore, config.keystorePassword),
            secureRandom,
            nettyChannel,
            clientHandshakeHandler,
            clientStateHandler,
            to.inetSocketAddress,
            true,
            ProtocolVersion.DTLSv12
          )

          nettyChannel
            .pipeline()
            .addLast("HoldingDtlsClientHandler", new HoldingDtlsClientHandler(clientProto, clientStateHandler))
            .addLast(new channel.ChannelInboundHandlerAdapter() {
              override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
                val datagram = msg.asInstanceOf[DatagramPacket]
                val remoteAddress = datagram.sender()
                val localAddress = datagram.recipient()
                val messageE: Either[DecodeFailure, M] = codec.decode(datagram.content().nioBuffer().asReadOnlyBuffer())
                log.info(s"Client channel read message $messageE with remote $remoteAddress and local $localAddress")

                val channelId = getChannelId(remoteAddress, localAddress)

                if (!activeChannels.contains(channelId)) {
                  throw new IllegalStateException(s"Missing channel instance for channelId $channelId")
                }

                val channel = activeChannels(channelId)
                messageE.foreach(message => channel.messageSubject.onNext(message))
              }
            })
        }
      })

    val cf = cb.connect(to.inetSocketAddress)
    val ct: Task[NioDatagramChannel] = toTask(cf).map(_ => cf.channel().asInstanceOf[NioDatagramChannel])

    ct.map { nettyChannel =>
      val localAddress = nettyChannel.localAddress()
      log.debug(s"Generated local address for new client is $localAddress")
      val channelId = getChannelId(to.inetSocketAddress, localAddress)

      assert(!activeChannels.contains(channelId), s"HOUSTON, WE HAVE A MULTIPLEXING PROBLEM")

      val channel = new ChannelImpl(nettyChannel, localAddress, to.inetSocketAddress, ReplaySubject[M]())
      activeChannels.put(channelId, channel)

      val clientProto =
        nettyChannel.pipeline().get("HoldingDtlsClientHandler").asInstanceOf[HoldingDtlsClientHandler].clientProto

      try {
        clientProto.initHandshake(null)
      } catch {
        case e: Exception =>
          log.error("An error occured while initializing handshake", e)
      }

      channel
    }
  }

  override def server(): Observable[Channel[InetMultiAddress, M]] = channelSubject

  override def shutdown(): Task[Unit] = {
    for {
      _ <- Task.gatherUnordered(activeChannels.values.map(channel => channel.close()))
      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
    } yield {
      channelSubject.onComplete()
      log.info(s"DTLSMobiusPeerGroup@$processAddress shutdown successfully.")
      ()
    }
  }

  class ChannelImpl(
      val nettyChannel: NioDatagramChannel,
      localAddress: InetSocketAddress,
      remoteAddress: InetSocketAddress,
      val messageSubject: Subject[M, M]
  ) extends Channel[InetMultiAddress, M] {

    log.debug(
      s"Setting up new channel from local address $localAddress " +
        s"to remote address $remoteAddress. Netty channelId is ${nettyChannel.id()}. " +
        s"My channelId is ${getChannelId(remoteAddress, localAddress)}"
    )

    override val to: InetMultiAddress = InetMultiAddress(remoteAddress)

    override def sendMessage(message: M): Task[Unit] = sendMessage(message, localAddress, remoteAddress, nettyChannel)

    override def in: Observable[M] = messageSubject

    override def close(): Task[Unit] = {
      messageSubject.onComplete()
      Task.unit
    }

    private def sendMessage(
        message: M,
        sender: InetSocketAddress,
        recipient: InetSocketAddress,
        nettyChannel: NioDatagramChannel
    ): Task[Unit] = {
      val nettyBuffer = Unpooled.wrappedBuffer(codec.encode(message))
      proto.sendPacket(nettyBuffer)
      Task.unit // fixme
    }

    private def proto: AsyncDtlsClientProtocol = {
      nettyChannel.pipeline().get("HoldingDtlsClientHandler").asInstanceOf[HoldingDtlsClientHandler].clientProto
    }
  }

}

object DTLSMobiusPeerGroup {

  case class Config(
      bindAddress: InetSocketAddress,
      processAddress: InetMultiAddress,
      keystore: KeyStore,
      keystorePassword: String
  )

  object Config {
    def apply(bindAddress: InetSocketAddress, keystore: KeyStore, keystorePassword: String): Config =
      Config(bindAddress, InetMultiAddress(bindAddress), keystore, keystorePassword)
  }

}
