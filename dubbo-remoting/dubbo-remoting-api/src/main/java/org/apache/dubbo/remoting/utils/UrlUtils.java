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

package org.apache.dubbo.remoting.utils;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.Constants;

/**
 * url utils;主要用于获取心跳间隔时间和空闲连接销毁时间
 *
 * @author allen.wu
 */
public class UrlUtils {

    private UrlUtils(){}

    /**
     * 获取空闲连接超时时间
     *
     * @param url url
     * @return int
     */
    public static int getIdleTimeout(URL url) {
        // 1. 心跳间隔时间：60 * 1000
        int heartBeat = getHeartbeat(url);
        // idleTimeout should be at least more than twice heartBeat because possible retries of client.
        // 2. idleTime，默认： 3* 60 * 1000
        int idleTimeout = url.getParameter(Constants.HEARTBEAT_TIMEOUT_KEY, heartBeat * 3);
        // 3. 判断空闲超时时间和心跳时间
        if (idleTimeout < heartBeat * 2) {
            throw new IllegalStateException("idleTimeout < heartbeatInterval * 2");
        }
        return idleTimeout;
    }

    /**
     * 获取心跳时间间隔，默认为：60 * 1000
     *
     * @param url url
     * @return int
     */
    public static int getHeartbeat(URL url) {
        return url.getParameter(Constants.HEARTBEAT_KEY, Constants.DEFAULT_HEARTBEAT);
    }
}
