package org.season.ymir.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.season.ymir.client.handler.NettyClientHandler;
import org.season.ymir.common.constant.CommonConstant;
import org.season.ymir.common.entity.ServiceBean;
import org.season.ymir.common.model.YmirRequest;
import org.season.ymir.common.model.YmirResponse;
import org.season.ymir.common.utils.YmirThreadFactory;
import org.season.ymir.core.codec.MessageEncoder;
import org.season.ymir.core.codec.MessageResponseDecoder;
import org.season.ymir.core.protocol.MessageProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Netty客户端
 *
 * @author KevinClair
 **/
public class YmirNettyClient {

    private static Logger logger = LoggerFactory.getLogger(YmirNettyClient.class);

    private static ExecutorService threadPool = new ThreadPoolExecutor(4, 10, 200,
            TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000), new YmirThreadFactory("netty-client"));

    private EventLoopGroup loopGroup = new NioEventLoopGroup(4);

    /**
     * 已连接的服务缓存
     * key: 服务地址，格式：ip:port
     */
    public static Map<String, NettyClientHandler> connectedServerNodes = new ConcurrentHashMap<>();

    public YmirResponse sendRequest(YmirRequest rpcRequest, ServiceBean service, MessageProtocol protocol) {

        String address = service.getAddress();
        synchronized (address) {
            if (connectedServerNodes.containsKey(address)) {
                NettyClientHandler handler = connectedServerNodes.get(address);
                return handler.sendRequest(rpcRequest);
            }

            String[] addrInfo = address.split(":");
            final String serverAddress = addrInfo[0];
            final String serverPort = addrInfo[1];
            final NettyClientHandler handler = new NettyClientHandler(protocol, address);
            // 异步建立客户端
            threadPool.submit(() -> {
                        startClient(address, serverAddress, serverPort, handler, protocol);
                    }
            );
            return handler.sendRequest(rpcRequest);
        }
    }

    private void startClient(String address, String serverAddress, String serverPort, NettyClientHandler handler, MessageProtocol protocol) {
        // 配置客户端
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(loopGroup)
                .channel(NioSocketChannel.class)
                .remoteAddress(serverAddress, Integer.parseInt(serverPort))
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) throws Exception {
                        ChannelPipeline pipeline = channel.pipeline();
                        pipeline
                                // 空闲检测
                                .addLast(new IdleStateHandler(CommonConstant.READ_TIMEOUT_SECONDS, 0, 0))
                                .addLast(new ReadTimeoutHandler(3 * CommonConstant.READ_TIMEOUT_SECONDS))
                                // 解码器
                                .addLast(new MessageResponseDecoder(protocol))
                                // 编码器
                                .addLast(new MessageEncoder(protocol))
                                .addLast(handler);
                    }
                });
        // 启用客户端连接
        bootstrap.connect().addListener((ChannelFutureListener) channelFuture -> {
            if (!channelFuture.isSuccess()) {
                reconnect(address, serverAddress, serverPort, handler, protocol);
                return;
            }
            connectedServerNodes.put(address, handler);
        });
    }

    /**
     * TODO 重新链接服务端
     *
     * @param address
     * @param serverAddress
     * @param serverPort
     * @param handler
     */
    public void reconnect(String address, String serverAddress, String serverPort, NettyClientHandler handler, MessageProtocol protocol) {
        loopGroup.schedule(() -> {
            if (logger.isDebugEnabled()){
                logger.info("Netty client start reconnect, address:{}", address);
            }
            startClient(address, serverAddress, serverPort, handler, protocol);
        }, CommonConstant.RECONNECT_SECONDS, TimeUnit.SECONDS);
    }
}
