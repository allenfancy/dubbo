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

import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionScope;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;

import java.io.IOException;

/**
 * codec2 用于字节和有效的字符串转换
 *
 * @author allen.wu
 */
@SPI(scope = ExtensionScope.FRAMEWORK)
public interface Codec2 {

    /**
     * 编码
     *
     * @param channel channel
     * @param buffer  buffer
     * @param message message
     * @throws IOException io exception
     */
    @Adaptive({Constants.CODEC_KEY})
    void encode(Channel channel, ChannelBuffer buffer, Object message) throws IOException;

    /**
     * 解码
     *
     * @param channel channel
     * @param buffer  buffer
     * @return object
     * @throws IOException io exception
     */
    @Adaptive({Constants.CODEC_KEY})
    Object decode(Channel channel, ChannelBuffer buffer) throws IOException;


    /**
     * decode 结果
     */
    enum DecodeResult {
        /**
         * 1.需要更多输入
         * 2.跳过更多输入
         */
        NEED_MORE_INPUT, SKIP_SOME_INPUT
    }

}

