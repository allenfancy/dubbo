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
package org.apache.dubbo.remoting;

import org.apache.dubbo.common.extension.ExtensionScope;
import org.apache.dubbo.common.extension.SPI;


/**
 * ChannelHandler. (API, Prototype, ThreadSafe)
 * channel handler.
 *
 * @author allen.wu
 * @see org.apache.dubbo.remoting.Transporter#bind(org.apache.dubbo.common.URL, ChannelHandler)
 * @see org.apache.dubbo.remoting.Transporter#connect(org.apache.dubbo.common.URL, ChannelHandler)
 */
@SPI(scope = ExtensionScope.FRAMEWORK)
public interface ChannelHandler {

    /**
     * on channel connected.
     * 在通道连接。
     *
     * @param channel channel.
     * @throws RemotingException remoting exception.
     */
    void connected(Channel channel) throws RemotingException;

    /**
     * on channel disconnected.
     * channel断开连接。
     *
     * @param channel channel.
     * @throws RemotingException remoting exception.
     */
    void disconnected(Channel channel) throws RemotingException;

    /**
     * on message sent.
     * 在消息发送。
     *
     * @param channel channel.
     * @param message message.
     * @throws RemotingException remoting exception.
     */
    void sent(Channel channel, Object message) throws RemotingException;

    /**
     * on message received.
     * 接收到消息。
     *
     * @param channel channel.
     * @param message message.
     * @throws RemotingException remoting exception.
     */
    void received(Channel channel, Object message) throws RemotingException;

    /**
     * on exception caught.
     * 在异常捕获。
     *
     * @param channel   channel.
     * @param exception exception.
     * @throws RemotingException remoting exception.
     */
    void caught(Channel channel, Throwable exception) throws RemotingException;

}
