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
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.RemotingServer;

import java.util.Map;

/**
 * Distinct from {@link RemotingServer}, each protocol holds one or more ProtocolServers(the number usually decides by port numbers),
 * while each ProtocolServer holds zero or one RemotingServer.
 * 协议层Server端的顶级接口设计；
 * 不同于{@link RemotingServer}，每个协议保存一个或多个ProtocolServers(number通常由端口号决定)，而每个协议服务器保存0或1个RemotingServer。
 *
 * @author allen.wu
 */
public interface ProtocolServer {

    /**
     * 获取 remoting server
     *
     * @return RemotingServer remoting server
     */
    default RemotingServer getRemotingServer() {
        return null;
    }

    /**
     * 设置 remoting server
     *
     * @param server server
     */
    default void setRemotingServers(RemotingServer server) {
    }

    /**
     * 获取地址
     *
     * @return String address
     */
    String getAddress();

    /**
     * 设置地址
     *
     * @param address address
     */
    void setAddress(String address);

    /**
     * 获取URL
     *
     * @return url
     */
    default URL getUrl() {
        return null;
    }

    /**
     * 重置url
     *
     * @param url url
     */
    default void reset(URL url) {
    }

    /**
     * 关闭
     */
    void close();

    /**
     * 获取attribute map
     *
     * @return Map attribute map
     */
    Map<String, Object> getAttributes();
}
