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
package org.apache.dubbo.remoting.exchange;

import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * ExchangeChannel. (API/SPI, Prototype, ThreadSafe)
 * Channel 接口之上抽象了 Exchange 层的网络连接
 *
 * @author allen.wu
 */
public interface ExchangeChannel extends Channel {

    /**
     * send request.
     *
     * @param request 请求对象
     * @return response future
     * @throws RemotingException 远程调用异常
     */
    @Deprecated
    CompletableFuture<Object> request(Object request) throws RemotingException;

    /**
     * send request.
     *
     * @param request 请求对象
     * @param timeout 请求超时
     * @return response future 响应 future
     * @throws RemotingException 远程调用异常
     */
    @Deprecated
    CompletableFuture<Object> request(Object request, int timeout) throws RemotingException;

    /**
     * send request.
     * 发送请求对象
     *
     * @param request  request
     * @param executor executor 执行器
     * @return response future
     * @throws RemotingException 远程调用异常
     */
    CompletableFuture<Object> request(Object request, ExecutorService executor) throws RemotingException;

    /**
     * send request.
     *
     * @param request  request
     * @param timeout  timeout
     * @param executor executor
     * @return response future
     * @throws RemotingException 远程调用异常
     */
    CompletableFuture<Object> request(Object request, int timeout, ExecutorService executor) throws RemotingException;

    /**
     * get message handler.
     * 获取message handler.
     *
     * @return message handler
     */
    ExchangeHandler getExchangeHandler();

    /**
     * graceful close.
     * 优雅关闭
     *
     * @param timeout timeout
     */
    @Override
    void close(int timeout);
}
