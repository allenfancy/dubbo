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

import org.apache.dubbo.common.URL;

import java.net.InetSocketAddress;

/**
 * Endpoint. (API/SPI, Prototype, ThreadSafe)
 *
 * @author allen.wu
 * @see org.apache.dubbo.remoting.Channel
 * @see org.apache.dubbo.remoting.Client
 * @see RemotingServer
 */
public interface Endpoint {

    /**
     * get url.
     *
     * @return url
     */
    URL getUrl();

    /**
     * get channel handler.
     *
     * @return channel handler
     */
    ChannelHandler getChannelHandler();

    /**
     * get local address.
     *
     * @return local address.
     */
    InetSocketAddress getLocalAddress();

    /**
     * send message.
     *
     * @param message message
     * @throws RemotingException remoting exception
     */
    void send(Object message) throws RemotingException;

    /**
     * send message.
     * 发送消息
     *
     * @param message message
     * @param sent    already sent to socket?
     * @throws RemotingException remoting exception
     */
    void send(Object message, boolean sent) throws RemotingException;

    /**
     * close the channel.
     * 强制关闭channel
     */
    void close();

    /**
     * Graceful close the channel.
     * 安静关闭channel
     *
     * @param timeout 超时时间
     */
    void close(int timeout);

    /**
     * 开始关闭channel
     */
    void startClose();

    /**
     * is closed.
     * 是否已关闭
     *
     * @return closed
     */
    boolean isClosed();

}
