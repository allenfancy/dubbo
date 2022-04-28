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
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec2;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ScopeModelAware;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;

/**
 * AbstractCodec
 * 抽象的codec2的类.
 *
 * @author allen.wu
 */
public abstract class AbstractCodec implements Codec2, ScopeModelAware {

    private static final Logger logger = LoggerFactory.getLogger(AbstractCodec.class);

    /**
     * 客户端
     */
    private static final String CLIENT_SIDE = "client";

    /**
     * 服务端
     */
    private static final String SERVER_SIDE = "server";

    /**
     * frame work model.
     */
    protected FrameworkModel frameworkModel;

    @Override
    public void setFrameworkModel(FrameworkModel frameworkModel) {
        this.frameworkModel = frameworkModel;
    }

    protected static void checkPayload(Channel channel, long size) throws IOException {
        int payload = getPayload(channel);
        boolean overPayload = isOverPayload(payload, size);
        if (overPayload) {
            ExceedPayloadLimitException e = new ExceedPayloadLimitException("Data length too large: " + size + ", max payload: " + payload + ", channel: " + channel);
            logger.error(e);
            throw e;
        }
    }

    protected static int getPayload(Channel channel) {
        int payload = Constants.DEFAULT_PAYLOAD;
        if (channel != null && channel.getUrl() != null) {
            payload = channel.getUrl().getParameter(Constants.PAYLOAD_KEY, Constants.DEFAULT_PAYLOAD);
        }
        return payload;
    }

    protected static boolean isOverPayload(int payload, long size) {
        return payload > 0 && size > payload;
    }

    /**
     * 获取序列化实现类
     *
     * @param channel channel
     * @param req     req
     * @return Serialization
     */
    protected Serialization getSerialization(Channel channel, Request req) {
        return CodecSupport.getSerialization(channel.getUrl());
    }

    /**
     * 获取序列化实现类
     *
     * @param channel channel
     * @param res     res
     * @return Serialization
     */
    protected Serialization getSerialization(Channel channel, Response res) {
        return CodecSupport.getSerialization(channel.getUrl());
    }

    /**
     * 获取序列化实现类
     *
     * @param channel channel
     * @return Serialization
     */
    protected Serialization getSerialization(Channel channel) {
        return CodecSupport.getSerialization(channel.getUrl());
    }

    /**
     * 是否是客户端
     *
     * @param channel channel
     * @return 如果为true表示客户端，否则不是客户端
     */
    protected boolean isClientSide(Channel channel) {
        //1. 从channel的attribute中获取side字段
        String side = (String) channel.getAttribute(SIDE_KEY);
        if (CLIENT_SIDE.equals(side)) {
            return true;
        } else if (SERVER_SIDE.equals(side)) {
            return false;
        } else {
            //2.1 从channel的remoteAddress中获取address
            InetSocketAddress address = channel.getRemoteAddress();
            URL url = channel.getUrl();
            //2.2 判断url的端口和remoteAddress端口并且url的IP和remoteAddress中的host address的IP一致
            boolean isClient = url.getPort() == address.getPort() && NetUtils.filterLocalHost(url.getIp()).equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
            //2.3 设置side字段
            channel.setAttribute(SIDE_KEY, isClient ? CLIENT_SIDE : SERVER_SIDE);
            return isClient;
        }
    }

    /**
     * 是否是服务端
     *
     * @param channel channel
     * @return 如果不是客户端，即为服务端
     */
    protected boolean isServerSide(Channel channel) {
        return !isClientSide(channel);
    }

}
