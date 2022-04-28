/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.remoting.transport;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.*;
import org.apache.dubbo.remoting.transport.dispatcher.ChannelHandlers;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.dubbo.common.constants.CommonConstants.*;

/**
 * AbstractClient
 * 抽象的Client的端
 *
 * @author allen.wu
 */
public abstract class AbstractClient extends AbstractEndpoint implements Client {

    protected static final String CLIENT_THREAD_POOL_NAME = "DubboClientHandler";

    private static final Logger logger = LoggerFactory.getLogger(AbstractClient.class);

    /**
     * 在Client底层进行连接、断开、重连等操作时，需要获取该锁进行同步
     */
    private final Lock connectLock = new ReentrantLock();

    /**
     * 在发送数据之前，会检查Client底层的连接是否断开，如果断开了，则会根据 needReconnect 字段，决定是否重连
     */
    private final boolean needReconnect;

    /**
     * 当前Client关联的线程池
     */
    protected volatile ExecutorService executor;

    /**
     * executor的管理类
     */
    private final ExecutorRepository executorRepository;


    public AbstractClient(URL url, ChannelHandler handler) throws RemotingException {
        super(url, handler);
        // 1. 从配置信息中获取executorRepository的配置
        executorRepository = url.getOrDefaultApplicationModel().getExtensionLoader(ExecutorRepository.class).getDefaultExtension();
        // 2. 从配置信息中获取needReconnect的配置
        needReconnect = url.getParameter(Constants.SEND_RECONNECT_KEY, true);
        // 3. 初始化executor
        initExecutor(url);

        try {
            // 4. doOpen
            doOpen();
        } catch (Throwable t) {
            close();
            throw new RemotingException(url.toInetSocketAddress(), null,
                    "Failed to start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress()
                            + " connect to the server " + getRemoteAddress() + ", cause: " + t.getMessage(), t);
        }

        try {
            // 5. 初始化Connection
            connect();
            if (logger.isInfoEnabled()) {
                logger.info("Start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress() + " connect to the server " + getRemoteAddress());
            }
        } catch (RemotingException t) {
            // If lazy connect client fails to establish a connection, the client instance will still be created,
            // and the reconnection will be initiated by ReconnectTask, so there is no need to throw an exception
            if (url.getParameter(LAZY_CONNECT_KEY, false)) {
                logger.warn("Failed to start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress() +
                        " connect to the server " + getRemoteAddress() +
                        " (the connection request is initiated by lazy connect client, ignore and retry later!), cause: " +
                        t.getMessage(), t);
                return;
            }

            // 6.  如果是非lazy connect的client，则直接抛出异常
            if (url.getParameter(Constants.CHECK_KEY, true)) {
                close();
                throw t;
            } else {
                logger.warn("Failed to start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress()
                        + " connect to the server " + getRemoteAddress() + " (check == false, ignore and retry later!), cause: " + t.getMessage(), t);
            }
        } catch (Throwable t) {
            // 7. close()
            close();
            throw new RemotingException(url.toInetSocketAddress(), null,
                    "Failed to start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress()
                            + " connect to the server " + getRemoteAddress() + ", cause: " + t.getMessage(), t);
        }
    }

    private void initExecutor(URL url) {
        /*
         * Consumer's executor is shared globally, provider ip doesn't need to be part of the thread name.
         * consumer的执行程序是全局共享的，提供者ip不需要成为线程名的一部分。
         *
         * Instance of url is InstanceAddressURL, so addParameter actually adds parameters into ServiceInstance,
         * which means params are shared among different services.
         * url的实例是InstanceAddressURL，所以addParameter实际上是添加参数到ServiceInstance，这意味着params是在不同的服务之间共享的。
         * Since client is shared among services this is currently not a problem.
         * 由于客户机是在服务之间共享的，因此目前这不是问题。
         */
        url = url.addParameter(THREAD_NAME_KEY, CLIENT_THREAD_POOL_NAME);
        url = url.addParameterIfAbsent(THREADPOOL_KEY, DEFAULT_CLIENT_THREADPOOL);
        // 创建线程池
        executor = executorRepository.createExecutorIfAbsent(url);
    }

    /**
     * 包装一个连接，并且把连接放到连接池中
     *
     * @param url     url
     * @param handler handler
     * @return ChannelHandler
     */
    protected static ChannelHandler wrapChannelHandler(URL url, ChannelHandler handler) {
        return ChannelHandlers.wrap(handler, url);
    }

    /**
     * 初始化 connect address 和 connect timeout
     *
     * @return InetSocketAddress
     */
    public InetSocketAddress getConnectAddress() {
        return new InetSocketAddress(NetUtils.filterLocalHost(getUrl().getHost()), getUrl().getPort());
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        Channel channel = getChannel();
        if (channel == null) {
            return getUrl().toInetSocketAddress();
        }
        return channel.getRemoteAddress();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        Channel channel = getChannel();
        if (channel == null) {
            return InetSocketAddress.createUnresolved(NetUtils.getLocalHost(), 0);
        }
        return channel.getLocalAddress();
    }

    @Override
    public boolean isConnected() {
        Channel channel = getChannel();
        if (channel == null) {
            return false;
        }
        return channel.isConnected();
    }

    @Override
    public Object getAttribute(String key) {
        Channel channel = getChannel();
        if (channel == null) {
            return null;
        }
        return channel.getAttribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        Channel channel = getChannel();
        if (channel == null) {
            return;
        }
        channel.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        Channel channel = getChannel();
        if (channel == null) {
            return;
        }
        channel.removeAttribute(key);
    }

    @Override
    public boolean hasAttribute(String key) {
        Channel channel = getChannel();
        if (channel == null) {
            return false;
        }
        return channel.hasAttribute(key);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        // 1. 如果需要重新连接并且连接不可用，则进行重连
        if (needReconnect && !isConnected()) {
            connect();
        }
        // 2. 获取channel
        Channel channel = getChannel();
        //TODO Can the value returned by getChannel() be null? need improvement.
        if (channel == null || !channel.isConnected()) {
            throw new RemotingException(this, "message can not send, because channel is closed . url:" + getUrl());
        }
        // 3. 发送消息
        channel.send(message, sent);
    }

    protected void connect() throws RemotingException {
        connectLock.lock();

        try {
            // 1. 如果已经连接，则直接返回
            if (isConnected()) {
                return;
            }

            // 2. 如果已关闭或关闭了，则直接返回
            if (isClosed() || isClosing()) {
                logger.warn("No need to connect to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " "
                        + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion() + ", cause: client status is closed or closing.");
                return;
            }

            // 3. 重新连接
            doConnect();

            if (!isConnected()) {
                throw new RemotingException(this, "Failed to connect to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " "
                        + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion()
                        + ", cause: Connect wait timeout: " + getConnectTimeout() + "ms.");

            } else {
                if (logger.isInfoEnabled()) {
                    logger.info("Successfully connect to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " "
                            + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion()
                            + ", channel is " + this.getChannel());
                }
            }

        } catch (RemotingException e) {
            throw e;

        } catch (Throwable e) {
            throw new RemotingException(this, "Failed to connect to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " "
                    + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion()
                    + ", cause: " + e.getMessage(), e);

        } finally {
            connectLock.unlock();
        }
    }

    public void disconnect() {
        connectLock.lock();
        try {
            try {
                Channel channel = getChannel();
                if (channel != null) {
                    channel.close();
                }
            } catch (Throwable e) {
                logger.warn(e.getMessage(), e);
            }
            try {
                doDisConnect();
            } catch (Throwable e) {
                logger.warn(e.getMessage(), e);
            }
        } finally {
            connectLock.unlock();
        }
    }

    @Override
    public void reconnect() throws RemotingException {
        connectLock.lock();
        try {
            disconnect();
            connect();
        } finally {
            connectLock.unlock();
        }
    }

    @Override
    public void close() {
        if (isClosed()) {
            logger.warn("No need to close connection to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " " + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion() + ", cause: the client status is closed.");
            return;
        }

        connectLock.lock();
        try {
            if (isClosed()) {
                logger.warn("No need to close connection to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " " + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion() + ", cause: the client status is closed.");
                return;
            }

            try {
                super.close();
            } catch (Throwable e) {
                logger.warn(e.getMessage(), e);
            }

            try {
                disconnect();
            } catch (Throwable e) {
                logger.warn(e.getMessage(), e);
            }

            try {
                doClose();
            } catch (Throwable e) {
                logger.warn(e.getMessage(), e);
            }

        } finally {
            connectLock.unlock();
        }
    }

    @Override
    public void close(int timeout) {
        close();
    }

    @Override
    public String toString() {
        return getClass().getName() + " [" + getLocalAddress() + " -> " + getRemoteAddress() + "]";
    }

    /**
     * Open client.
     * 打开客户端
     *
     * @throws Throwable Throwable
     */
    protected abstract void doOpen() throws Throwable;

    /**
     * Close client.
     * 关闭client端
     *
     * @throws Throwable Throwable
     */
    protected abstract void doClose() throws Throwable;

    /**
     * Connect to server.
     * 连接服务端
     *
     * @throws Throwable Throwable
     */
    protected abstract void doConnect() throws Throwable;

    /**
     * disConnect to server.
     * 断开连接
     *
     * @throws Throwable Throwable
     */
    protected abstract void doDisConnect() throws Throwable;

    /**
     * Get the connected channel.
     * 获取连接的channel
     *
     * @return channel
     */
    protected abstract Channel getChannel();
}
