/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileChannel;
import java.nio.channels.NetworkChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.jsse.JSSESupport;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 * <p>
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 */
public class NioEndpoint extends AbstractJsseEndpoint<NioChannel> {


    // -------------------------------------------------------------- Constants


    private static final Log log = LogFactory.getLog(NioEndpoint.class);


    public static final int OP_REGISTER = 0x100; //register interest op

    // ----------------------------------------------------------------- Fields

    private NioSelectorPool selectorPool = new NioSelectorPool();

    /**
     * Server socket "pointer".
     *
     * 用于socket 连接的 ServerSocket 对象
     */
    private volatile ServerSocketChannel serverSock = null;

    /**
     *
     */
    private volatile CountDownLatch stopLatch = null;

    /**
     * Cache for poller events
     * 由Poller类 往 这个堆栈中放入 PollerEven事件
     */
    private SynchronizedStack<PollerEvent> eventCache;

    /**
     * Bytebuffer cache, each channel holds a set of buffers (two, except for SSL holds four)
     */
    private SynchronizedStack<NioChannel> nioChannels;


    // ------------------------------------------------------------- Properties


    /**
     * Generic properties, introspected
     */
    @Override
    public boolean setProperty(String name, String value) {
        final String selectorPoolName = "selectorPool.";
        try {
            if (name.startsWith(selectorPoolName)) {
                return IntrospectionUtils.setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
            } else {
                return super.setProperty(name, value);
            }
        } catch (Exception x) {
            log.error("Unable to set attribute \"" + name + "\" to \"" + value + "\"", x);
            return false;
        }
    }


    /**
     * Use System.inheritableChannel to obtain channel from stdin/stdout.
     */
    private boolean useInheritedChannel = false;

    public void setUseInheritedChannel(boolean useInheritedChannel) {
        this.useInheritedChannel = useInheritedChannel;
    }

    public boolean getUseInheritedChannel() {
        return useInheritedChannel;
    }

    /**
     * Priority of the poller threads.
     */
    private int pollerThreadPriority = Thread.NORM_PRIORITY;

    public void setPollerThreadPriority(int pollerThreadPriority) {
        this.pollerThreadPriority = pollerThreadPriority;
    }

    public int getPollerThreadPriority() {
        return pollerThreadPriority;
    }


    /**
     * Poller thread count.   返回可用于Java虚拟机的处理器数量。  与2 一起取最小值
     */
    private int pollerThreadCount = Math.min(2, Runtime.getRuntime().availableProcessors());

    public void setPollerThreadCount(int pollerThreadCount) {
        this.pollerThreadCount = pollerThreadCount;
    }

    public int getPollerThreadCount() {
        return pollerThreadCount;
    }

    private long selectorTimeout = 1000;

    public void setSelectorTimeout(long timeout) {
        this.selectorTimeout = timeout;
    }

    public long getSelectorTimeout() {
        return this.selectorTimeout;
    }

    /**
     * The socket poller.
     */
    private Poller[] pollers = null;
    private AtomicInteger pollerRotater = new AtomicInteger(0);

    /**
     * Return an available poller in true round robin fashion.
     *
     * @return The next poller in sequence
     */
    public Poller getPoller0() {
        //pollerRotater.incrementAndGet()的绝对值 ，与pollers.length取余数
        int idx = Math.abs(pollerRotater.incrementAndGet()) % pollers.length;
        //返回计算后的Poller
        return pollers[idx];
    }


    public void setSelectorPool(NioSelectorPool selectorPool) {
        this.selectorPool = selectorPool;
    }

    public void setSocketProperties(SocketProperties socketProperties) {
        this.socketProperties = socketProperties;
    }

    /**
     * Is deferAccept supported?
     */
    @Override
    public boolean getDeferAccept() {
        // Not supported
        return false;
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Number of keep-alive sockets.
     *
     * @return The number of sockets currently in the keep-alive state waiting
     * for the next request to be received on the socket
     */
    public int getKeepAliveCount() {
        if (pollers == null) {
            return 0;
        } else {
            int sum = 0;
            for (int i = 0; i < pollers.length; i++) {
                sum += pollers[i].getKeyCount();
            }
            return sum;
        }
    }


    // ----------------------------------------------- Public Lifecycle Methods

    /**
     * Initialize the endpoint.
     * 初始化通道，用于接收通讯信息的，服务于http请求等功能
     */
    @Override
    public void bind() throws Exception {

        if (!getUseInheritedChannel()) {
            //开启创建一个通道
            serverSock = ServerSocketChannel.open();
            //通过serverSock的socket方法获取到创建的哪个通道，使用setProperties对这个socket进行设置
            socketProperties.setProperties(serverSock.socket());
            //获得配置文件中所写端口与地址
            InetSocketAddress addr = (getAddress() != null ? new InetSocketAddress(getAddress(), getPort()) : new InetSocketAddress(getPort()));
            //进行地址的绑定与开启
            serverSock.socket().bind(addr, getAcceptCount());
        } else {
            // Retrieve the channel provided by the OS
            Channel ic = System.inheritedChannel();
            if (ic instanceof ServerSocketChannel) {
                serverSock = (ServerSocketChannel) ic;
            }
            if (serverSock == null) {
                throw new IllegalArgumentException(sm.getString("endpoint.init.bind.inherited"));
            }
        }
        //设置为阻塞式调用
        serverSock.configureBlocking(true); //mimic APR behavior

        // Initialize thread count defaults for acceptor, poller
        if (acceptorThreadCount == 0) {
            // FIXME: Doesn't seem to work that well with multiple accept threads
            acceptorThreadCount = 1;
        }
        if (pollerThreadCount <= 0) {
            //minimum one poller thread
            pollerThreadCount = 1;
        }
        //设置CountDownLatch ，这个地方会给stopLatch赋值一个 pollerThreadCount 大小得等待
        //CountDownLatch会暂停线程运行
        setStopLatch(new CountDownLatch(pollerThreadCount));

        // Initialize SSL if needed
        //TODO 初始化ssl  这个有兴趣可以精读
        initialiseSsl();
        //启动多路复用器池子，因为此时此刻ServerSocket已经建立好了
        //这个池子里只有一个多路复用器，给这个服务的下面建立一个多路复用器
        //open会启动一个线程
        selectorPool.open();
    }

    /**
     * Start the NIO endpoint, creating acceptor, poller threads.
     * 由父类调用子类的io方法
     * //TODO 建立io连接与io 处理 关键类
     * 这个方法是一个关键方法：
     *
     *
     */
    @Override
    public void startInternal() throws Exception {

        if (!running) {
            //在初始化阶段就将父类的running变为true
            running = true;
            paused = false;
            //建立一个栈，初始大小为128 ，上限为 500的processorCache栈
            processorCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getProcessorCache());
            //建立一个栈 初始大小为128，，上限为 500的eventCache栈
            eventCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getEventCache());
            //建立一个栈 初始大小为128，，上限为 500的nioChannels栈
            nioChannels = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getBufferPool());

            // Create worker collection
            //获取多线程执行器 并判断是否为null
            if (getExecutor() == null) {
                createExecutor();
            }
            //初始化 一个锁，赋值给自己的父类的connectionLimitLatch属性，来限制最大申请连接数，默认为10000
            initializeConnectionLatch();
            // TODO NIO启动线程池
            //启动处理socket读写事件的线程
            // Start poller threads   声明一个轮询数组，数组大小为可用于Java虚拟机的处理器数量。 与2 的最小值   pollers是主要处理接收请求的处理函数
            pollers = new Poller[getPollerThreadCount()];
            for (int i = 0; i < pollers.length; i++) {
                //对Poller进行初始化，Poller对象 会对各种通道进行逻辑处理 与事件注册
                pollers[i] = new Poller();
                Thread pollerThread = new Thread(pollers[i], getName() + "-ClientPoller-" + i);
                pollerThread.setPriority(threadPriority);
                pollerThread.setDaemon(true);
                pollerThread.start();
            }
            //启动接收建立socket连接的线程
            startAcceptorThreads();
        }
    }


    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     */
    @Override
    public void stopInternal() {
        releaseConnectionLatch();
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            unlockAccept();
            for (int i = 0; pollers != null && i < pollers.length; i++) {
                if (pollers[i] == null) continue;
                pollers[i].destroy();
                pollers[i] = null;
            }
            try {
                if (!getStopLatch().await(selectorTimeout + 100, TimeUnit.MILLISECONDS)) {
                    log.warn(sm.getString("endpoint.nio.stopLatchAwaitFail"));
                }
            } catch (InterruptedException e) {
                log.warn(sm.getString("endpoint.nio.stopLatchAwaitInterrupted"), e);
            }
            shutdownExecutor();
            eventCache.clear();
            nioChannels.clear();
            processorCache.clear();
        }
    }


    /**
     * Deallocate NIO memory pools, and close server socket.
     */
    @Override
    public void unbind() throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Destroy initiated for " + new InetSocketAddress(getAddress(), getPort()));
        }
        if (running) {
            stop();
        }
        doCloseServerSocket();
        destroySsl();
        super.unbind();
        if (getHandler() != null) {
            getHandler().recycle();
        }
        selectorPool.close();
        if (log.isDebugEnabled()) {
            log.debug("Destroy completed for " + new InetSocketAddress(getAddress(), getPort()));
        }
    }


    @Override
    protected void doCloseServerSocket() throws IOException {
        if (!getUseInheritedChannel() && serverSock != null) {
            // Close server socket
            serverSock.socket().close();
            serverSock.close();
        }
        serverSock = null;
    }


    // ------------------------------------------------------ Protected Methods


    public int getWriteBufSize() {
        return socketProperties.getTxBufSize();
    }

    public int getReadBufSize() {
        return socketProperties.getRxBufSize();
    }

    public NioSelectorPool getSelectorPool() {
        return selectorPool;
    }


    @Override
    protected AbstractEndpoint.Acceptor createAcceptor() {
        return new Acceptor();
    }


    protected CountDownLatch getStopLatch() {
        return stopLatch;
    }


    protected void setStopLatch(CountDownLatch stopLatch) {
        this.stopLatch = stopLatch;
    }


    /**
     * Process the specified connection.
     * 处理指定的socket连接
     *
     * @param socket The socket channel
     * @return <code>true</code> if the socket was correctly configured
     * and processing may continue, <code>false</code> if the socket needs to be
     * close immediately
     */
    protected boolean setSocketOptions(SocketChannel socket) {
        // Process the connection
        try {
            //disable blocking, APR style, we are gonna be polling it
            socket.configureBlocking(false);
            //获取到建立连接得socket
            Socket sock = socket.socket();
            //给socket设置属性参数
            socketProperties.setProperties(sock);
            //nioChannels 出栈一个NioChannel
            NioChannel channel = nioChannels.pop();
            if (channel == null) {
                //从socket设置属性参数的对象中获取到一些信息 生成一个SocketBufferHandler类 对象
                SocketBufferHandler bufhandler = new SocketBufferHandler(
                        socketProperties.getAppReadBufSize(),
                        socketProperties.getAppWriteBufSize(),
                        socketProperties.getDirectBuffer());
                if (isSSLEnabled()) {
                    channel = new SecureNioChannel(socket, bufhandler, selectorPool, this);
                } else {
                    //此处将SocketBufferHandler 与 建立链接的socket 共同生成一个NioChannel对象
                    channel = new NioChannel(socket, bufhandler);
                }
            } else {
                //将socket赋值给这个channel
                channel.setIOChannel(socket);
                //给NioChannel对象进行一系列初始化
                channel.reset();
            }
            //当有socket发起请求时，会先获取poller （根据算法获取），将channel注册进去，
            //将channel 放入events队列中去 ，这个地方会将channel的读取事件注册到多路复用器当中
            //根据逻辑筛选出一个Poller用于处理这个通道的逻辑行为
            //至此，socket建立连接到此结束，他最终就是将channel 放入events队列中去，我们剩下的就是查看 到底是谁使用了Poller的events队列  （是Poller线程的run方法 一直在检测events队列）
            getPoller0().register(channel);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            try {
                log.error("", t);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(tt);
            }
            // Tell to close the socket
            return false;
        }
        return true;
    }


    @Override
    protected Log getLog() {
        return log;
    }


    @Override
    protected NetworkChannel getServerSocket() {
        return serverSock;
    }


    // --------------------------------------------------- Acceptor Inner Class

    /**
     * The background thread that listens for incoming TCP/IP connections and
     * hands them off to an appropriate processor.
     * 这个类实现了AbstractEndpoint.Acceptor 抽象类
     * 提供了Nio自己的Acceptor实现方案
     * 开始的时候 running 默认是false 需要等待时机变为true
     * 这是单独生成的线程，他将一直执行下去 ，其剩余逻辑都将在此执行
     */
    protected class Acceptor extends AbstractEndpoint.Acceptor {

        @Override
        //这里会不断地轮询等待socket进行建立连接到请求
        public void run() {

            int errorDelay = 0;

            // Loop until we receive a shutdown command
            while (running) {
                //paused在nio初始化的时候为false，running设置为了true
                // Loop if endpoint is paused
                while (paused && running) {
                    state = AcceptorState.PAUSED;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
                //检查是否退出
                if (!running) {
                    break;
                }
                //设置Acceptor状态为RUNNING
                state = AcceptorState.RUNNING;

                try {
                    //if we have reached max connections, wait
                    //判断是否到达最大连接数，如果到达最大连接数 将等待
                    countUpOrAwaitConnection();

                    SocketChannel socket = null;
                    try {
                        // Accept the next incoming connection from the server
                        // 这个线程会等待 建立连接得socket
                        socket = serverSock.accept();
                    } catch (IOException ioe) {
                        // We didn't get a socket
                        countDownConnection();
                        if (running) {
                            // Introduce delay if necessary
                            errorDelay = handleExceptionWithDelay(errorDelay);
                            // re-throw
                            throw ioe;
                        } else {
                            break;
                        }
                    }
                    // Successful accept, reset the error delay
                    errorDelay = 0;

                    // Configure the socket
                    if (running && !paused) {
                        // setSocketOptions() will hand the socket off to
                        // an appropriate processor if successful
                        //setSocketOptions会将接收到的socket赋值给一个多路复用器
                        //一切顺利的话会返回true ，如果失败会返回false
                        //setSocketOptions 方法会将socket 放入events队列中去
                        //Poller会读取监听这个队列中的socket
                        if (!setSocketOptions(socket)) {
                            closeSocket(socket);
                        }
                    } else {
                        closeSocket(socket);
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("endpoint.accept.fail"), t);
                }
            }
            state = AcceptorState.ENDED;
        }


        private void closeSocket(SocketChannel socket) {
            countDownConnection();
            try {
                socket.socket().close();
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("endpoint.err.close"), ioe);
                }
            }
            try {
                socket.close();
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("endpoint.err.close"), ioe);
                }
            }
        }
    }


    @Override
    /**
     * 插眼这个地方是生成处理http请求的类
     */
    protected SocketProcessorBase<NioChannel> createSocketProcessor(
            SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
        return new SocketProcessor(socketWrapper, event);
    }


    private void close(NioChannel socket, SelectionKey key) {
        try {
            if (socket.getPoller().cancelledKey(key) != null) {
                // SocketWrapper (attachment) was removed from the
                // key - recycle the key. This can only happen once
                // per attempted closure so it is used to determine
                // whether or not to return the key to the cache.
                // We do NOT want to do this more than once - see BZ
                // 57340 / 57943.
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + socket + "] closed");
                }
                if (running && !paused) {
                    if (!nioChannels.push(socket)) {
                        socket.free();
                    }
                }
            }
        } catch (Exception x) {
            log.error("", x);
        }
    }

    // ----------------------------------------------------- Poller Inner Classes

    /**
     * PollerEvent, cacheable object for poller events to avoid GC
     */
    public static class PollerEvent implements Runnable {

        private NioChannel socket;
        private int interestOps;
        private NioSocketWrapper socketWrapper;

        //构造一个事件
        public PollerEvent(NioChannel ch, NioSocketWrapper w, int intOps) {
            reset(ch, w, intOps);
        }

        //替换重置事件属性
        public void reset(NioChannel ch, NioSocketWrapper w, int intOps) {
            socket = ch;
            socketWrapper = w;
            //socketWrapper 标识描述事件类型，如注册事件
            interestOps = intOps;
        }

        public void reset() {
            reset(null, null, 0);
        }

        @Override
        //PollerEvent事件，他将会将把socket注册到对应的 多路复用器中，
        // 并且携带一个socketWrapper，当有事件发生时，可以通过多路复用器的attachment() 获取到socketWrapper附件
        public void run() {
            //当这个事件为注册事件(一个新的socket注册到poller轮询中去) 时进入if
            if (interestOps == OP_REGISTER) {
                try {
                    //给多路复用器添加一个读事件的监听，这个地方是通道注册的关键代码
                    //读取事件就是从这里注册的
                    socket.getIOChannel().register(
                            socket.getPoller().getSelector(), SelectionKey.OP_READ, socketWrapper);
                } catch (Exception x) {
                    log.error(sm.getString("endpoint.nio.registerFail"), x);
                }
                //如果不是注册事件，那么就查看这个事件是所属类型
            } else {
                //如果不是注册事件通过socket的多路复用器取出key
                final SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
                try {
                    if (key == null) {
                        // The key was cancelled (e.g. due to socket closure)
                        // and removed from the selector while it was being
                        // processed. Count down the connections at this point
                        // since it won't have been counted down when the socket
                        // closed.
                        socket.socketWrapper.getEndpoint().countDownConnection();
                        ((NioSocketWrapper) socket.socketWrapper).closed = true;
                    } else {
                        //从key中取出附件
                        final NioSocketWrapper socketWrapper = (NioSocketWrapper) key.attachment();
                        //首先判断附件不为null
                        if (socketWrapper != null) {
                            //we are registering the key to start with, reset the fairness counter.
                            //
                            int ops = key.interestOps() | interestOps;
                            socketWrapper.interestOps(ops);
                            key.interestOps(ops);
                        } else {
                            socket.getPoller().cancelledKey(key);
                        }
                    }
                } catch (CancelledKeyException ckx) {
                    try {
                        socket.getPoller().cancelledKey(key);
                    } catch (Exception ignore) {
                    }
                }
            }
        }

        @Override
        public String toString() {
            return "Poller event: socket [" + socket + "], socketWrapper [" + socketWrapper +
                    "], interestOps [" + interestOps + "]";
        }
    }

    /**
     * Poller class.   轮询类 他继承自多线程
     * 这个类会处理http请求，处理socket的连接，事件注册等工作
     */
    public class Poller implements Runnable {

        private Selector selector;
        //声明一个无边界队列，队列中存储各种事件
        private final SynchronizedQueue<PollerEvent> events =
                new SynchronizedQueue<>();

        private volatile boolean close = false;
        private long nextExpiration = 0;//optimize expiration handling
        //这是一个线程安全的aqs
        private AtomicLong wakeupCounter = new AtomicLong(0);

        private volatile int keyCount = 0;

        //Selector 检查器 在初始化的时候创立
        public Poller() throws IOException {
            this.selector = Selector.open();
        }

        public int getKeyCount() {
            return keyCount;
        }

        public Selector getSelector() {
            return selector;
        }

        /**
         * Destroy the poller.
         * 调用destroy方法 会将close属性变为true
         * 然后将关键属性selector置为初始状态
         */
        protected void destroy() {
            // Wait for polltime before doing anything, so that the poller threads
            // exit, otherwise parallel closure of sockets which are still
            // in the poller can cause problems
            close = true;
            selector.wakeup();
        }

        //往队列中插入一个事件,关于events这个队列，会有另外一个线程 不断地轮询检查他，、
        private void addEvent(PollerEvent event) {
            //插入事件 （在此我们就查看到底是谁一直在监听socket事件）
            events.offer(event);
            //如果wakeupCounter+1后依然为0 ，就重置selector 状态
            if (wakeupCounter.incrementAndGet() == 0) selector.wakeup();
        }

        /**
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         *
         * @param socket      to add to the poller
         * @param interestOps Operations for which to register this socket with
         *                    the Poller
         *                    给事件队列添加 注册事件元素，socket 与 事件类型（注册事件、读取事件、写入事件）
         */
        public void add(final NioChannel socket, final int interestOps) {
            //从事件缓存器中取出一个事件来
            PollerEvent r = eventCache.pop();
            //如果事件为null 就new一个 （复用以前的对象）
            if (r == null) r = new PollerEvent(socket, null, interestOps);
            else r.reset(socket, null, interestOps);
            addEvent(r);
            if (close) {
                NioEndpoint.NioSocketWrapper ka = (NioEndpoint.NioSocketWrapper) socket.getAttachment();
                processSocket(ka, SocketEvent.STOP, false);
            }
        }
        /**
         * Processes events in the event queue of the Poller.
         * 处理轮询器的事件队列中的事件。
         *
         * @return <code>true</code> if some events were processed,
         * <code>false</code> if queue was empty
         */
        public boolean events() {
            boolean result = false;
            //声明PollerEvent类型
            PollerEvent pe = null;
            //检查events<PollerEvent> 队列中是否有 数据，如果存在数据 那就遍历他们 （查看是否有事件发生）
            for (int i = 0, size = events.size(); i < size && (pe = events.poll()) != null; i++) {
                //result代表数据事件已经被处理
                result = true;
                try {
                    //执行pe的run函数
                    pe.run();
                    //重置事件 属性
                    pe.reset();
                    //将事件放回到evenCache中
                    if (running && !paused) {
                        //如果有事件发生，会将events队列中的事件取出，放入到eventCache栈中去（我们还需要关注到底是谁在处理栈信息）
                        //将事件压栈
                        eventCache.push(pe);
                    }
                } catch (Throwable x) {
                    log.error("", x);
                }
            }
            //处理成功返回true
            return result;
        }

        /**
         * Registers a newly created socket with the poller.
         *
         * @param socket The newly created socket
         *               注册最新创建的socket到poller中
         *               这里的注册方法就是 将包装好的事件放入到，队列中
         *               且这个地方只注册 读取事件
         */
        public void register(final NioChannel socket) {
            socket.setPoller(this);
            //包装类 对socket与NioEndpoint进行包装
            NioSocketWrapper ka = new NioSocketWrapper(socket, NioEndpoint.this);
            socket.setSocketWrapper(ka);
            ka.setPoller(this);
            ka.setReadTimeout(getSocketProperties().getSoTimeout());
            ka.setWriteTimeout(getSocketProperties().getSoTimeout());
            ka.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
            ka.setSecure(isSSLEnabled());
            ka.setReadTimeout(getConnectionTimeout());
            ka.setWriteTimeout(getConnectionTimeout());
            //从eventCache 栈顶取出一个事件
            PollerEvent r = eventCache.pop();
            //将该socket的 读取事件注册到多路复用器中，
            ka.interestOps(SelectionKey.OP_READ);//this is what OP_REGISTER turns into.
            //判断栈中是否有数据，如果没有数据，构建一个轮训事件，传入刚刚产生的socket 与通道包装类NioChannel
            //注册事件 REGISTER（登记表）  在PollerEvent事件中，通过 传入的第三个参数 去确认当前事件是一个什么事件，如 是一个注册事件，就传入一个OP_REGISTER
            if (r == null) r = new PollerEvent(socket, ka, OP_REGISTER);
                //如果从栈中取出了事件 那么执行事件的rest方法
            else r.reset(socket, ka, OP_REGISTER);
            //将事件放入队列，到底是哪个线程执行消费这个Event（事件）呢
            addEvent(r);
        }

        public NioSocketWrapper cancelledKey(SelectionKey key) {
            NioSocketWrapper ka = null;
            try {
                if (key == null) return null;//nothing to do
                ka = (NioSocketWrapper) key.attach(null);
                if (ka != null) {
                    // If attachment is non-null then there may be a current
                    // connection with an associated processor.
                    getHandler().release(ka);
                }
                if (key.isValid()) key.cancel();
                // If it is available, close the NioChannel first which should
                // in turn close the underlying SocketChannel. The NioChannel
                // needs to be closed first, if available, to ensure that TLS
                // connections are shut down cleanly.
                if (ka != null) {
                    try {
                        ka.getSocket().close(true);
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString(
                                    "endpoint.debug.socketCloseFail"), e);
                        }
                    }
                }
                // The SocketChannel is also available via the SelectionKey. If
                // it hasn't been closed in the block above, close it now.
                if (key.channel().isOpen()) {
                    try {
                        key.channel().close();
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString(
                                    "endpoint.debug.channelCloseFail"), e);
                        }
                    }
                }
                try {
                    if (ka != null && ka.getSendfileData() != null
                            && ka.getSendfileData().fchannel != null
                            && ka.getSendfileData().fchannel.isOpen()) {
                        ka.getSendfileData().fchannel.close();
                    }
                } catch (Exception ignore) {
                }
                if (ka != null) {
                    countDownConnection();
                    ka.closed = true;
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) log.error("", e);
            }
            return ka;
        }

        /**
         * The background thread that adds sockets to the Poller, checks the
         * poller for triggered events and hands the associated socket off to an
         * appropriate processor as events occur.
         */
        @Override
        public void run() {
            // Loop until destroy() is called
            //一直循环 直到 收到 destroy() 方法调用 才结束
            while (true) {

                boolean hasEvents = false;

                try {
                    if (!close) {
                        //这里检查队列中是否有（事件）消息产生，（只要有连接建立 这个队列中就会产生事件）
                        //事件处理器 执行Poller 事件 处理器   监听者模式
                        hasEvents = events();
                        if (wakeupCounter.getAndSet(-1) > 0) {
                            //if we are here, means we have other stuff to do
                            //do a non blocking select
                            keyCount = selector.selectNow();
                        } else {
                            //判断多路复用器中 是否有了活跃的socket，如果有了 返回 int
                            keyCount = selector.select(selectorTimeout);
                        }
                        //最终将wakeupCounter设置为0 0是>-1的
                        wakeupCounter.set(0);
                    }
                    if (close) {
                        //事件处理器 处理events队列中的事件 执行Poller 事件 处理器   监听者模式
                        events();
                        timeout(0, false);
                        try {
                            selector.close();
                        } catch (IOException ioe) {
                            log.error(sm.getString("endpoint.nio.selectorCloseFail"), ioe);
                        }
                        break;
                    }
                } catch (Throwable x) {
                    ExceptionUtils.handleThrowable(x);
                    log.error("", x);
                    continue;
                }
                //either we timed out or we woke up, process events first
                //要么超时，要么唤醒，先处理事件
                if (keyCount == 0) hasEvents = (hasEvents | events());
                //迭代器，检查多路复用器是否发现活跃的key
                Iterator<SelectionKey> iterator =
                        keyCount > 0 ? selector.selectedKeys().iterator() : null;
                // Walk through the collection of ready keys and dispatch
                // any active event.
                //如果迭代器不为null，说明有请求发生
                while (iterator != null && iterator.hasNext()) {
                    //取出发生事件的key
                    SelectionKey sk = iterator.next();
                    //通过key给他拿出来
                    NioSocketWrapper attachment = (NioSocketWrapper) sk.attachment();
                    // Attachment may be null if another thread has called
                    // cancelledKey()
                    //确定好拿出来的不是一个null
                    if (attachment == null) {
                        //如果是null 给他移除掉
                        iterator.remove();
                    } else {
                        //如果不是null 给他移除掉
                        iterator.remove();
//                        接着处理这个发生事件的socket，当iterator 迭代器不为null时  那么就开始处理这个key和附件  （这个地方是关键，后期会用多线程处理），
//                        迭代器不为null是因为selector多路复用器发现建立的socket有处于活跃状态的
                        processKey(sk, attachment);
                    }
                }//while

                //process timeouts
                timeout(keyCount, hasEvents);
            }//while

            getStopLatch().countDown();
        }

        //当多路复用器发现 有socket处于活跃状态才会调用此方法，传入发生事件的key，或读或写 ，还有发生事件的socket包装类
        //他会启动提交一个多线程去处理socket事件
        //这个方法由run方法 在轮询中调用
        protected void processKey(SelectionKey sk, NioSocketWrapper attachment) {
            try {
                if (close) {
                    cancelledKey(sk);
                } else if (sk.isValid() && attachment != null) {
                    if (sk.isReadable() || sk.isWritable()) {
                        if (attachment.getSendfileData() != null) {
                            processSendfile(sk, attachment, false);
                        } else {
                            unreg(sk, attachment, sk.readyOps());
                            boolean closeSocket = false;
                            // Read goes before write
                            //如果key键为读事件
                            if (sk.isReadable()) {
                                //这个方法里面会创建http请求处理类，给这个socket注册一个读事件，多线程处理  socket连接已经建立
                                //会启动一个新的线程快速处理socket事件
                                if (!processSocket(attachment, SocketEvent.OPEN_READ, true)) {
                                    closeSocket = true;
                                }
                            }
                            if (!closeSocket && sk.isWritable()) {
                                //这个方法里面会创建http请求处理类，给这个socket注册一个写事件，多线程处理
                                if (!processSocket(attachment, SocketEvent.OPEN_WRITE, true)) {
                                    closeSocket = true;
                                }
                            }
                            if (closeSocket) {
                                cancelledKey(sk);
                            }
                        }
                    }
                } else {
                    //invalid key
                    cancelledKey(sk);
                }
            } catch (CancelledKeyException ckx) {
                cancelledKey(sk);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error("", t);
            }
        }

        public SendfileState processSendfile(SelectionKey sk, NioSocketWrapper socketWrapper,
                                             boolean calledByProcessor) {
            NioChannel sc = null;
            try {
                unreg(sk, socketWrapper, sk.readyOps());
                SendfileData sd = socketWrapper.getSendfileData();

                if (log.isTraceEnabled()) {
                    log.trace("Processing send file for: " + sd.fileName);
                }

                if (sd.fchannel == null) {
                    // Setup the file channel
                    File f = new File(sd.fileName);
                    @SuppressWarnings("resource") // Closed when channel is closed
                    FileInputStream fis = new FileInputStream(f);
                    sd.fchannel = fis.getChannel();
                }

                // Configure output channel
                sc = socketWrapper.getSocket();
                // TLS/SSL channel is slightly different
                WritableByteChannel wc = ((sc instanceof SecureNioChannel) ? sc : sc.getIOChannel());

                // We still have data in the buffer
                if (sc.getOutboundRemaining() > 0) {
                    if (sc.flushOutbound()) {
                        socketWrapper.updateLastWrite();
                    }
                } else {
                    long written = sd.fchannel.transferTo(sd.pos, sd.length, wc);
                    if (written > 0) {
                        sd.pos += written;
                        sd.length -= written;
                        socketWrapper.updateLastWrite();
                    } else {
                        // Unusual not to be able to transfer any bytes
                        // Check the length was set correctly
                        if (sd.fchannel.size() <= sd.pos) {
                            throw new IOException("Sendfile configured to " +
                                    "send more data than was available");
                        }
                    }
                }
                if (sd.length <= 0 && sc.getOutboundRemaining() <= 0) {
                    if (log.isDebugEnabled()) {
                        log.debug("Send file complete for: " + sd.fileName);
                    }
                    socketWrapper.setSendfileData(null);
                    try {
                        sd.fchannel.close();
                    } catch (Exception ignore) {
                    }
                    // For calls from outside the Poller, the caller is
                    // responsible for registering the socket for the
                    // appropriate event(s) if sendfile completes.
                    if (!calledByProcessor) {
                        switch (sd.keepAliveState) {
                            case NONE: {
                                if (log.isDebugEnabled()) {
                                    log.debug("Send file connection is being closed");
                                }
                                close(sc, sk);
                                break;
                            }
                            case PIPELINED: {
                                if (log.isDebugEnabled()) {
                                    log.debug("Connection is keep alive, processing pipe-lined data");
                                }
                                if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) {
                                    close(sc, sk);
                                }
                                break;
                            }
                            case OPEN: {
                                if (log.isDebugEnabled()) {
                                    log.debug("Connection is keep alive, registering back for OP_READ");
                                }
                                reg(sk, socketWrapper, SelectionKey.OP_READ);
                                break;
                            }
                        }
                    }
                    return SendfileState.DONE;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("OP_WRITE for sendfile: " + sd.fileName);
                    }
                    if (calledByProcessor) {
                        add(socketWrapper.getSocket(), SelectionKey.OP_WRITE);
                    } else {
                        reg(sk, socketWrapper, SelectionKey.OP_WRITE);
                    }
                    return SendfileState.PENDING;
                }
            } catch (IOException x) {
                if (log.isDebugEnabled()) log.debug("Unable to complete sendfile request:", x);
                if (!calledByProcessor && sc != null) {
                    close(sc, sk);
                }
                return SendfileState.ERROR;
            } catch (Throwable t) {
                log.error("", t);
                if (!calledByProcessor && sc != null) {
                    close(sc, sk);
                }
                return SendfileState.ERROR;
            }
        }

        protected void unreg(SelectionKey sk, NioSocketWrapper attachment, int readyOps) {
            //this is a must, so that we don't have multiple threads messing with the socket
            reg(sk, attachment, sk.interestOps() & (~readyOps));
        }

        protected void reg(SelectionKey sk, NioSocketWrapper attachment, int intops) {
            sk.interestOps(intops);
            attachment.interestOps(intops);
        }

        protected void timeout(int keyCount, boolean hasEvents) {
            long now = System.currentTimeMillis();
            // This method is called on every loop of the Poller. Don't process
            // timeouts on every loop of the Poller since that would create too
            // much load and timeouts can afford to wait a few seconds.
            // However, do process timeouts if any of the following are true:
            // - the selector simply timed out (suggests there isn't much load)
            // - the nextExpiration time has passed
            // - the server socket is being closed
            if (nextExpiration > 0 && (keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
                return;
            }
            //timeout
            int keycount = 0;
            try {
                for (SelectionKey key : selector.keys()) {
                    keycount++;
                    try {
                        NioSocketWrapper ka = (NioSocketWrapper) key.attachment();
                        if (ka == null) {
                            cancelledKey(key); //we don't support any keys without attachments
                        } else if (close) {
                            key.interestOps(0);
                            ka.interestOps(0); //avoid duplicate stop calls
                            processKey(key, ka);
                        } else if ((ka.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ ||
                                (ka.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                            boolean isTimedOut = false;
                            // Check for read timeout
                            if ((ka.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
                                long delta = now - ka.getLastRead();
                                long timeout = ka.getReadTimeout();
                                isTimedOut = timeout > 0 && delta > timeout;
                            }
                            // Check for write timeout
                            if (!isTimedOut && (ka.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                                long delta = now - ka.getLastWrite();
                                long timeout = ka.getWriteTimeout();
                                isTimedOut = timeout > 0 && delta > timeout;
                            }
                            if (isTimedOut) {
                                key.interestOps(0);
                                ka.interestOps(0); //avoid duplicate timeout calls
                                ka.setError(new SocketTimeoutException());
                                if (!processSocket(ka, SocketEvent.ERROR, true)) {
                                    cancelledKey(key);
                                }
                            }
                        }
                    } catch (CancelledKeyException ckx) {
                        cancelledKey(key);
                    }
                }//for
            } catch (ConcurrentModificationException cme) {
                // See https://bz.apache.org/bugzilla/show_bug.cgi?id=57943
                log.warn(sm.getString("endpoint.nio.timeoutCme"), cme);
            }
            long prevExp = nextExpiration; //for logging purposes only
            nextExpiration = System.currentTimeMillis() +
                    socketProperties.getTimeoutInterval();
            if (log.isTraceEnabled()) {
                log.trace("timeout completed: keys processed=" + keycount +
                        "; now=" + now + "; nextExpiration=" + prevExp +
                        "; keyCount=" + keyCount + "; hasEvents=" + hasEvents +
                        "; eval=" + ((now < prevExp) && (keyCount > 0 || hasEvents) && (!close)));
            }

        }
    }

    // ---------------------------------------------------- Key Attachment Class
    public static class NioSocketWrapper extends SocketWrapperBase<NioChannel> {

        private final NioSelectorPool pool;

        private Poller poller = null;
        private int interestOps = 0;
        private CountDownLatch readLatch = null;
        private CountDownLatch writeLatch = null;
        private volatile SendfileData sendfileData = null;
        private volatile long lastRead = System.currentTimeMillis();
        private volatile long lastWrite = lastRead;
        private volatile boolean closed = false;

        public NioSocketWrapper(NioChannel channel, NioEndpoint endpoint) {
            super(channel, endpoint);
            //socket多路复用器
            pool = endpoint.getSelectorPool();
            //对应的通道处理方法
            socketBufferHandler = channel.getBufHandler();
        }

        public Poller getPoller() {
            return poller;
        }

        public void setPoller(Poller poller) {
            this.poller = poller;
        }

        public int interestOps() {
            return interestOps;
        }

        public int interestOps(int ops) {
            this.interestOps = ops;
            return ops;
        }

        public CountDownLatch getReadLatch() {
            return readLatch;
        }

        public CountDownLatch getWriteLatch() {
            return writeLatch;
        }

        protected CountDownLatch resetLatch(CountDownLatch latch) {
            if (latch == null || latch.getCount() == 0) return null;
            else throw new IllegalStateException("Latch must be at count 0");
        }

        public void resetReadLatch() {
            readLatch = resetLatch(readLatch);
        }

        public void resetWriteLatch() {
            writeLatch = resetLatch(writeLatch);
        }

        protected CountDownLatch startLatch(CountDownLatch latch, int cnt) {
            if (latch == null || latch.getCount() == 0) {
                return new CountDownLatch(cnt);
            } else throw new IllegalStateException("Latch must be at count 0 or null.");
        }

        public void startReadLatch(int cnt) {
            readLatch = startLatch(readLatch, cnt);
        }

        public void startWriteLatch(int cnt) {
            writeLatch = startLatch(writeLatch, cnt);
        }

        //进行 线程等待 的方法
        protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException {
            if (latch == null) throw new IllegalStateException("Latch cannot be null");
            // Note: While the return value is ignored if the latch does time
            //       out, logic further up the call stack will trigger a
            //       SocketTimeoutException
            //对于指定的ContDownLatch进行 线程等待
            latch.await(timeout, unit);
        }

        //执行对应的线程等待
        public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException {
            awaitLatch(readLatch, timeout, unit);
        }

        //执行对应的线程等待
        public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException {
            awaitLatch(writeLatch, timeout, unit);
        }

        public void setSendfileData(SendfileData sf) {
            this.sendfileData = sf;
        }

        public SendfileData getSendfileData() {
            return this.sendfileData;
        }

        public void updateLastWrite() {
            lastWrite = System.currentTimeMillis();
        }

        public long getLastWrite() {
            return lastWrite;
        }

        public void updateLastRead() {
            lastRead = System.currentTimeMillis();
        }

        public long getLastRead() {
            return lastRead;
        }


        @Override
        public boolean isReadyForRead() throws IOException {
            socketBufferHandler.configureReadBufferForRead();

            if (socketBufferHandler.getReadBuffer().remaining() > 0) {
                return true;
            }

            fillReadBuffer(false);

            boolean isReady = socketBufferHandler.getReadBuffer().position() > 0;
            return isReady;
        }


        @Override
        public int read(boolean block, byte[] b, int off, int len) throws IOException {
            int nRead = populateReadBuffer(b, off, len);
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
            }

            // Fill the read buffer as best we can.
            nRead = fillReadBuffer(block);
            updateLastRead();

            // Fill as much of the remaining byte array as possible with the
            // data that was just read
            if (nRead > 0) {
                socketBufferHandler.configureReadBufferForRead();
                nRead = Math.min(nRead, len);
                socketBufferHandler.getReadBuffer().get(b, off, nRead);
            }
            return nRead;
        }

        /***
         * 从socket中读取流操作，放入到ByteBuffer中
         * @param block
         * @param to  需要放入的ByteBuffer 对象
         * @return
         * @throws IOException
         */
        @Override
        public int read(boolean block, ByteBuffer to) throws IOException {
            int nRead = populateReadBuffer(to);
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
            }
            //获取ReadBuffer最大可以承受的容量
            // The socket read buffer capacity is socket.appReadBufSize
            int limit = socketBufferHandler.getReadBuffer().capacity();
            //判断容量是否可以装载的下socket中的数据
            if (to.remaining() >= limit) {
                to.limit(to.position() + limit);
                //进行真正的流装载工作
                nRead = fillReadBuffer(block, to);
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + this + "], Read direct from socket: [" + nRead + "]");
                }
                updateLastRead();
            } else {
                // Fill the read buffer as best we can.
                nRead = fillReadBuffer(block);
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + this + "], Read into buffer: [" + nRead + "]");
                }
                updateLastRead();

                // Fill as much of the remaining byte array as possible with the
                // data that was just read
                if (nRead > 0) {
                    nRead = populateReadBuffer(to);
                }
            }
            return nRead;
        }


        @Override
        public void close() throws IOException {
            getSocket().close();
        }


        @Override
        public boolean isClosed() {
            return closed;
        }


        private int fillReadBuffer(boolean block) throws IOException {
            socketBufferHandler.configureReadBufferForWrite();
            return fillReadBuffer(block, socketBufferHandler.getReadBuffer());
        }

        /**
         * 执行socket流装填工作
         * @param block
         * @param to
         * @return
         * @throws IOException
         */
        private int fillReadBuffer(boolean block, ByteBuffer to) throws IOException {
            int nRead;
            //获取到socket
            NioChannel channel = getSocket();
            if (block) {
                Selector selector = null;
                try {
                    selector = pool.get();
                } catch (IOException x) {
                    // Ignore
                }
                try {
                    NioEndpoint.NioSocketWrapper att = (NioEndpoint.NioSocketWrapper) channel
                            .getAttachment();
                    if (att == null) {
                        throw new IOException("Key must be cancelled.");
                    }
                    nRead = pool.read(to, channel, selector, att.getReadTimeout());
                } finally {
                    if (selector != null) {
                        pool.put(selector);
                    }
                }
            } else {
                //进行真实的读取 这个方法会调用底层，返回读取的数据量
                nRead = channel.read(to);
                if (nRead == -1) {
                    throw new EOFException();
                }
            }
            return nRead;
        }


        @Override
        //执行写事件
        protected void doWrite(boolean block, ByteBuffer from) throws IOException {
            long writeTimeout = getWriteTimeout();
            Selector selector = null;
            try {
                selector = pool.get();
            } catch (IOException x) {
                // Ignore
            }
            try {
                pool.write(from, getSocket(), selector, writeTimeout, block);
                if (block) {
                    // Make sure we are flushed
                    do {
                        if (getSocket().flush(true, selector, writeTimeout)) {
                            break;
                        }
                    } while (true);
                }
                updateLastWrite();
            } finally {
                if (selector != null) {
                    pool.put(selector);
                }
            }
            // If there is data left in the buffer the socket will be registered for
            // write further up the stack. This is to ensure the socket is only
            // registered for write once as both container and user code can trigger
            // write registration.
        }


        @Override
        public void registerReadInterest() {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.debug.registerRead", this));
            }
            getPoller().add(getSocket(), SelectionKey.OP_READ);
        }


        @Override
        public void registerWriteInterest() {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.debug.registerWrite", this));
            }
            getPoller().add(getSocket(), SelectionKey.OP_WRITE);
        }


        @Override
        public SendfileDataBase createSendfileData(String filename, long pos, long length) {
            return new SendfileData(filename, pos, length);
        }


        @Override
        public SendfileState processSendfile(SendfileDataBase sendfileData) {
            setSendfileData((SendfileData) sendfileData);
            SelectionKey key = getSocket().getIOChannel().keyFor(
                    getSocket().getPoller().getSelector());
            // Might as well do the first write on this thread
            return getSocket().getPoller().processSendfile(key, this, true);
        }


        @Override
        protected void populateRemoteAddr() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getInetAddress();
            if (inetAddr != null) {
                remoteAddr = inetAddr.getHostAddress();
            }
        }


        @Override
        protected void populateRemoteHost() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getInetAddress();
            if (inetAddr != null) {
                remoteHost = inetAddr.getHostName();
                if (remoteAddr == null) {
                    remoteAddr = inetAddr.getHostAddress();
                }
            }
        }


        @Override
        protected void populateRemotePort() {
            remotePort = getSocket().getIOChannel().socket().getPort();
        }


        @Override
        protected void populateLocalName() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getLocalAddress();
            if (inetAddr != null) {
                localName = inetAddr.getHostName();
            }
        }


        @Override
        protected void populateLocalAddr() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getLocalAddress();
            if (inetAddr != null) {
                localAddr = inetAddr.getHostAddress();
            }
        }


        @Override
        protected void populateLocalPort() {
            localPort = getSocket().getIOChannel().socket().getLocalPort();
        }


        /**
         * {@inheritDoc}
         *
         * @param clientCertProvider Ignored for this implementation
         */
        @Override
        public SSLSupport getSslSupport(String clientCertProvider) {
            if (getSocket() instanceof SecureNioChannel) {
                SecureNioChannel ch = (SecureNioChannel) getSocket();
                SSLEngine sslEngine = ch.getSslEngine();
                if (sslEngine != null) {
                    SSLSession session = sslEngine.getSession();
                    return ((NioEndpoint) getEndpoint()).getSslImplementation().getSSLSupport(session);
                }
            }
            return null;
        }


        @Override
        public void doClientAuth(SSLSupport sslSupport) throws IOException {
            SecureNioChannel sslChannel = (SecureNioChannel) getSocket();
            SSLEngine engine = sslChannel.getSslEngine();
            if (!engine.getNeedClientAuth()) {
                // Need to re-negotiate SSL connection
                engine.setNeedClientAuth(true);
                sslChannel.rehandshake(getEndpoint().getConnectionTimeout());
                ((JSSESupport) sslSupport).setSession(engine.getSession());
            }
        }


        @Override
        public void setAppReadBufHandler(ApplicationBufferHandler handler) {
            getSocket().setAppReadBufHandler(handler);
        }

        @Override
        protected <A> OperationState<A> newOperationState(boolean read,
                                                          ByteBuffer[] buffers, int offset, int length,
                                                          BlockingMode block, long timeout, TimeUnit unit, A attachment,
                                                          CompletionCheck check, CompletionHandler<Long, ? super A> handler,
                                                          Semaphore semaphore, VectoredIOCompletionHandler<A> completion) {
            return new NioOperationState<>(read, buffers, offset, length, block,
                    timeout, unit, attachment, check, handler, semaphore, completion);
        }

        private class NioOperationState<A> extends OperationState<A> {
            private volatile boolean inline = true;

            private NioOperationState(boolean read, ByteBuffer[] buffers, int offset, int length,
                                      BlockingMode block, long timeout, TimeUnit unit, A attachment, CompletionCheck check,
                                      CompletionHandler<Long, ? super A> handler, Semaphore semaphore,
                                      VectoredIOCompletionHandler<A> completion) {
                super(read, buffers, offset, length, block,
                        timeout, unit, attachment, check, handler, semaphore, completion);
            }

            @Override
            protected boolean isInline() {
                return inline;
            }

            @Override
            public void run() {
                // Perform the IO operation
                // Called from the poller to continue the IO operation
                long nBytes = 0;
                if (getError() == null) {
                    try {
                        synchronized (this) {
                            if (!completionDone) {
                                // This filters out same notification until processing
                                // of the current one is done
                                if (log.isDebugEnabled()) {
                                    log.debug("Skip concurrent " + (read ? "read" : "write") + " notification");
                                }
                                return;
                            }
                            if (read) {
                                // Read from main buffer first
                                if (!socketBufferHandler.isReadBufferEmpty()) {
                                    // There is still data inside the main read buffer, it needs to be read first
                                    socketBufferHandler.configureReadBufferForRead();
                                    for (int i = 0; i < length && !socketBufferHandler.isReadBufferEmpty(); i++) {
                                        nBytes += transfer(socketBufferHandler.getReadBuffer(), buffers[offset + i]);
                                    }
                                }
                                if (nBytes == 0) {
                                    nBytes = getSocket().read(buffers, offset, length);
                                    updateLastRead();
                                }
                            } else {
                                boolean doWrite = true;
                                // Write from main buffer first
                                if (!socketBufferHandler.isWriteBufferEmpty()) {
                                    // There is still data inside the main write buffer, it needs to be written first
                                    socketBufferHandler.configureWriteBufferForRead();
                                    do {
                                        nBytes = getSocket().write(socketBufferHandler.getWriteBuffer());
                                    } while (!socketBufferHandler.isWriteBufferEmpty() && nBytes > 0);
                                    if (!socketBufferHandler.isWriteBufferEmpty()) {
                                        doWrite = false;
                                    }
                                    // Preserve a negative value since it is an error
                                    if (nBytes > 0) {
                                        nBytes = 0;
                                    }
                                }
                                if (doWrite) {
                                    long n = 0;
                                    do {
                                        n = getSocket().write(buffers, offset, length);
                                        if (n == -1) {
                                            nBytes = n;
                                        } else {
                                            nBytes += n;
                                        }
                                    } while (n > 0);
                                    updateLastWrite();
                                }
                            }
                            if (nBytes != 0 || !buffersArrayHasRemaining(buffers, offset, length)) {
                                completionDone = false;
                            }
                        }
                    } catch (IOException e) {
                        setError(e);
                    }
                }
                if (nBytes > 0 || (nBytes == 0 && !buffersArrayHasRemaining(buffers, offset, length))) {
                    // The bytes processed are only updated in the completion handler
                    completion.completed(Long.valueOf(nBytes), this);
                } else if (nBytes < 0 || getError() != null) {
                    IOException error = getError();
                    if (error == null) {
                        error = new EOFException();
                    }
                    completion.failed(error, this);
                } else {
                    // As soon as the operation uses the poller, it is no longer inline
                    inline = false;
                    if (read) {
                        registerReadInterest();
                    } else {
                        registerWriteInterest();
                    }
                }
            }

        }

    }


    // ---------------------------------------------- SocketProcessor Inner Class

    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     * 这个类父类中存在一个event属性，我们可以根据这个属性来判断 事件类型 是读取 还是写入
     */
    protected class SocketProcessor extends SocketProcessorBase<NioChannel> {
        //创建一个socketProcessor类，里面存有socketWrapper包装类  这个包装类 前面讲过、存有内存 socket 、 endpoint 对象 ，现在加一个处理事件 （读取、写入等）
        public SocketProcessor(SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
            super(socketWrapper, event);
        }

        @Override
        protected void doRun() {
            //从socketWrapper中取出 NioChannel
            NioChannel socket = socketWrapper.getSocket();
            //得到对应的key
            SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());

            try {
                int handshake = -1;

                try {
                    if (key != null) {
                        //这个地方就很搞笑 因为这个地方的if一定成立
                        if (socket.isHandshakeComplete()) {
                            // No TLS handshaking required. Let the handler
                            // process this socket / event combination.
                            handshake = 0;
                        } else if (event == SocketEvent.STOP || event == SocketEvent.DISCONNECT ||
                                event == SocketEvent.ERROR) {
                            // Unable to complete the TLS handshake. Treat it as
                            // if the handshake failed.
                            handshake = -1;
                        } else {
                            handshake = socket.handshake(key.isReadable(), key.isWritable());
                            // The handshake process reads/writes from/to the
                            // socket. status may therefore be OPEN_WRITE once
                            // the handshake completes. However, the handshake
                            // happens when the socket is opened so the status
                            // must always be OPEN_READ after it completes. It
                            // is OK to always set this as it is only used if
                            // the handshake completes.
                            event = SocketEvent.OPEN_READ;
                        }
                    }
                } catch (IOException x) {
                    handshake = -1;
                    if (log.isDebugEnabled()) log.debug("Error during SSL handshake", x);
                } catch (CancelledKeyException ckx) {
                    handshake = -1;
                }
                if (handshake == 0) {
                    SocketState state = SocketState.OPEN;
                    // Process the request from this socket
                    //处理传入的socket事件  读取 事件
                    if (event == null) {
                        //在读取配置文件初始化时，handler就被赋值了
                        state = getHandler().process(socketWrapper, SocketEvent.OPEN_READ);
                    } else {
                        //收到了连接请求
                        //在读取配置文件初始化时，handler就被赋值了
                        state = getHandler().process(socketWrapper, event);
                    }
                    if (state == SocketState.CLOSED) {
                        close(socket, key);
                    }
                } else if (handshake == -1) {
                    getHandler().process(socketWrapper, SocketEvent.CONNECT_FAIL);
                    close(socket, key);
                } else if (handshake == SelectionKey.OP_READ) {
                    socketWrapper.registerReadInterest();
                } else if (handshake == SelectionKey.OP_WRITE) {
                    socketWrapper.registerWriteInterest();
                }
            } catch (CancelledKeyException cx) {
                socket.getPoller().cancelledKey(key);
            } catch (VirtualMachineError vme) {
                ExceptionUtils.handleThrowable(vme);
            } catch (Throwable t) {
                log.error("", t);
                socket.getPoller().cancelledKey(key);
            } finally {
                socketWrapper = null;
                event = null;
                //return to cache
                if (running && !paused) {
                    processorCache.push(this);
                }
            }
        }
    }

    // ----------------------------------------------- SendfileData Inner Class

    /**
     * SendfileData class.
     */
    public static class SendfileData extends SendfileDataBase {

        public SendfileData(String filename, long pos, long length) {
            super(filename, pos, length);
        }

        protected volatile FileChannel fchannel;
    }
}
