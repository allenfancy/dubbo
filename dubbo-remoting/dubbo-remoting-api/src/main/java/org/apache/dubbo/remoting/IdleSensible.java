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

/**
 * Indicate whether the implementation (for both server and client) has the ability to sense and handle idle connection.
 * 表示实现(对于服务器和客户机)是否具有感知和处理空闲连接的能力。
 * If the server has the ability to handle idle connection, it should close the connection when it happens, and if
 * the client has the ability to handle idle connection, it should send the heartbeat to the server.
 * 如果服务器有能力处理空闲连接，它应该在连接发生时关闭连接，如果客户端有能力处理空闲连接，它应该向服务器发送心跳。
 *
 * @author allen.wu
 */
public interface IdleSensible {

    /**
     * Whether the implementation can sense and handle the idle connection.
     * 实现是否能够感知并处理空闲连接。
     * <p>
     * By default, it's false, the implementation relies on dedicated timer to take care of idle connection.
     * 默认为false，实现依赖于专用定时器来处理空闲连接。
     *
     * @return whether it has the ability to handle idle connection
     */
    default boolean canHandleIdle() {
        return false;
    }
}
