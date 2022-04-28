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

import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.telnet.TelnetHandler;

import java.util.concurrent.CompletableFuture;

/**
 * ExchangeHandler. (API, Prototype, ThreadSafe)
 * ExchangeHandler 接口是 Exchange 层与上层交互的接口之一，上层调用方可以实现该接口完成自身的功能；
 * 然后再由 HeaderExchangeHandler 修饰，具备 Exchange 层处理 Request-Response 的能力；
 * 最后再由 Transport ChannelHandler 修饰，具备 Transport 层的能力
 *
 *
 *
 *
 * @author allen.wu
 */
public interface ExchangeHandler extends ChannelHandler, TelnetHandler {

    /**
     * reply.
     * 回复
     *
     * @param channel exchange channel
     * @param request 请求体
     * @return response 响应体
     * @throws RemotingException remoting exception
     */
    CompletableFuture<Object> reply(ExchangeChannel channel, Object request) throws RemotingException;

}
