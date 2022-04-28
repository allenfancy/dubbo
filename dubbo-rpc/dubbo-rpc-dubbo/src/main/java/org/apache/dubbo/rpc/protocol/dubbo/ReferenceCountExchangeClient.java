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
package org.apache.dubbo.rpc.protocol.dubbo;


import org.apache.dubbo.common.Parameters;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_SERVER_SHUTDOWN_TIMEOUT;

/**
 * dubbo protocol support class.
 * ExchangeClient 的一个装饰器，在原始 ExchangeClient 对象基础上添加了引用计数的功能
 * 对于同一个地址的共享连接，就可以满足两个基本需求：
 * 当引用次数referenceCount减到 0 的时候，ExchangeClient 连接关闭；
 * 当引用次数referenceCount未减到 0 的时候，底层的 ExchangeClient 不能关闭。
 * ------
 * 用于共享连接创建使用，增强
 * @author allen.wu
 */
@SuppressWarnings("deprecation")
final class ReferenceCountExchangeClient implements ExchangeClient {

    private final static Logger logger = LoggerFactory.getLogger(ReferenceCountExchangeClient.class);

    /**
     * url
     */
    private final URL url;

    /**
     * 记录该 Client 被应用的次数；
     */
    private final AtomicInteger referenceCount = new AtomicInteger(0);

    /**
     * 断开连接的次数
     */
    private final AtomicInteger disconnectCount = new AtomicInteger(0);

    private final Integer warningPeriod = 50;

    /**
     * exchange client.
     */
    private ExchangeClient client;


    /**
     * 关闭等待时间；默认为1000ms
     */
    private int shutdownWaitTime = DEFAULT_SERVER_SHUTDOWN_TIMEOUT;

    public ReferenceCountExchangeClient(ExchangeClient client, String codec) {
        this.client = client;
        this.referenceCount.incrementAndGet();
        this.url = client.getUrl();
    }

    @Override
    public void reset(URL url) {
        client.reset(url);
    }

    @Override
    public CompletableFuture<Object> request(Object request) throws RemotingException {
        return client.request(request);
    }

    @Override
    public URL getUrl() {
        return client.getUrl();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return client.getRemoteAddress();
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return client.getChannelHandler();
    }

    @Override
    public CompletableFuture<Object> request(Object request, int timeout) throws RemotingException {
        return client.request(request, timeout);
    }

    @Override
    public CompletableFuture<Object> request(Object request, ExecutorService executor) throws RemotingException {
        return client.request(request, executor);
    }

    @Override
    public CompletableFuture<Object> request(Object request, int timeout, ExecutorService executor) throws RemotingException {
        return client.request(request, timeout, executor);
    }

    @Override
    public boolean isConnected() {
        return client.isConnected();
    }

    @Override
    public void reconnect() throws RemotingException {
        client.reconnect();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return client.getLocalAddress();
    }

    @Override
    public boolean hasAttribute(String key) {
        return client.hasAttribute(key);
    }

    @Override
    public void reset(Parameters parameters) {
        client.reset(parameters);
    }

    @Override
    public void send(Object message) throws RemotingException {
        client.send(message);
    }

    @Override
    public ExchangeHandler getExchangeHandler() {
        return client.getExchangeHandler();
    }

    @Override
    public Object getAttribute(String key) {
        return client.getAttribute(key);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        client.send(message, sent);
    }

    @Override
    public void setAttribute(String key, Object value) {
        client.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        client.removeAttribute(key);
    }

    /**
     * close() is not idempotent any longer
     */
    @Override
    public void close() {
        close(0);
    }

    @Override
    public void close(int timeout) {
        closeInternal(timeout, false);
    }

    @Override
    public void closeAll(int timeout) {
        closeInternal(timeout, true);
    }

    /**
     * when destroy unused invoker, closeAll should be true
     * 当无用的invoker销毁时，closeAll应该是true
     *
     * @param timeout  timeout
     * @param closeAll closeAll
     */
    private void closeInternal(int timeout, boolean closeAll) {
        // 1. 若closeAll为true，并且referenceCount为0，则直接关闭
        if (closeAll || referenceCount.decrementAndGet() <= 0) {
            if (timeout == 0) {
                client.close();
            } else {
                client.close(timeout);
            }
            // 2. 创建幽灵连接；主要作用为：异常情况的兜底
            replaceWithLazyClient();
        }
    }

    @Override
    public void startClose() {
        client.startClose();
    }

    /**
     * when closing the client, the client needs to be set to LazyConnectExchangeClient, and if a new call is made,
     * the client will "resurrect".
     * 当关闭客户端时，需要将客户端设置为LazyConnectExchangeClient，如果进行了新调用，客户端将“复活”。
     */
    private void replaceWithLazyClient() {
        // start warning at second replaceWithLazyClient()
        if (disconnectCount.getAndIncrement() % warningPeriod == 1) {
            logger.warn(url.getAddress() + " " + url.getServiceKey() + " safe guard client , should not be called ,must have a bug.");
        }

        /*
         * the order of judgment in the if statement cannot be changed.
         * if语句中的判决顺序不能改变。
         */
        if (!(client instanceof LazyConnectExchangeClient)) {
            client = new LazyConnectExchangeClient(url, client.getExchangeHandler());
        }
    }

    @Override
    public boolean isClosed() {
        return client.isClosed();
    }

    /**
     * The reference count of current ExchangeClient, connection will be closed if all invokers destroyed.
     * 增加引用的计数
     */
    public void incrementAndGetCount() {
        referenceCount.incrementAndGet();
    }

    /**
     * 获取被引用的次数
     *
     * @return referenceCount
     */
    public int getCount() {
        return referenceCount.get();
    }

    /**
     * @return shut down wait time
     */
    public int getShutdownWaitTime() {
        return shutdownWaitTime;
    }

    /**
     * 设置shut down wait time
     *
     * @param shutdownWaitTime shutdownWaitTime
     */
    public void setShutdownWaitTime(int shutdownWaitTime) {
        this.shutdownWaitTime = shutdownWaitTime;
    }
}

