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

import org.apache.dubbo.common.Resetable;

import java.net.InetSocketAddress;
import java.util.Collection;

/**
 * Remoting Server. (API/SPI, Prototype, ThreadSafe)
 * 远程 remoting server.
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Client%E2%80%93server_model">Client/Server</a>
 *
 * @author allen.wu
 * @see org.apache.dubbo.remoting.Transporter#bind(org.apache.dubbo.common.URL, ChannelHandler)
 */
public interface RemotingServer extends Endpoint, Resetable, IdleSensible {

    /**
     * is bound.
     * 是否绑定
     * @return bound
     */
    boolean isBound();

    /**
     * get channels.
     * 获取Server端服务的所有Channel
     *
     * @return channels
     */
    Collection<Channel> getChannels();

    /**
     * get channel.
     * 获取指定的Channel
     *
     * @param remoteAddress remoteAddress
     * @return channel
     */
    Channel getChannel(InetSocketAddress remoteAddress);

    /**
     * 重置
     * @param parameters
     */
    @Deprecated
    void reset(org.apache.dubbo.common.Parameters parameters);

}
