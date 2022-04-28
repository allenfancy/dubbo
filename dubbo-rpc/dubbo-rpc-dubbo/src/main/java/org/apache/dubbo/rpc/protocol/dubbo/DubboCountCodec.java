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

package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec2;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.MultiMessage;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.FrameworkModel;

import java.io.IOException;

import static org.apache.dubbo.rpc.Constants.INPUT_KEY;
import static org.apache.dubbo.rpc.Constants.OUTPUT_KEY;

/**
 * Dubbo protocol codec的默认实现。
 * DubboCountCodec：只负责在解码过程中 ChannelBuffer 的 readerIndex 指针控制
 * DubboCodec： 负责具体编解码的能力
 *
 * @author allen.wu
 */
public final class DubboCountCodec implements Codec2 {

    /**
     * 负责编解码的能力
     */
    private final DubboCodec codec;

    private final FrameworkModel frameworkModel;

    public DubboCountCodec(FrameworkModel frameworkModel) {
        this.frameworkModel = frameworkModel;
        codec = new DubboCodec(frameworkModel);
    }

    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        codec.encode(channel, buffer, msg);
    }

    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        // 1. 首先保存readerIndex指针位置
        int save = buffer.readerIndex();
        // 2. 创建MultiMessage对象，其中可以存储多条消息
        MultiMessage result = MultiMessage.create();
        do {
            // 3. 通过DubboCodec提供的解码能力解码一条消息
            Object obj = codec.decode(channel, buffer);
            // 4. 如果可读字节数不足一条消息，则会重置readerIndex指针
            if (Codec2.DecodeResult.NEED_MORE_INPUT == obj) {
                buffer.readerIndex(save);
                break;
            } else {
                // 5. 将成功解码的消息添加到MultiMessage中暂存
                result.addMessage(obj);
                logMessageLength(obj, buffer.readerIndex() - save);
                save = buffer.readerIndex();
            }
        } while (true);
        // 6. 一条消息也未解码出来，则返回NEED_MORE_INPUT错误码
        if (result.isEmpty()) {
            return Codec2.DecodeResult.NEED_MORE_INPUT;
        }
        // 7. 只解码出来一条消息，则直接返回该条消息
        if (result.size() == 1) {
            return result.get(0);
        }
        // 8. 解码出多条消息的话，会将MultiMessage返回
        return result;
    }

    private void logMessageLength(Object result, int bytes) {
        if (bytes <= 0) {
            return;
        }
        if (result instanceof Request) {
            try {
                ((RpcInvocation) ((Request) result).getData()).setAttachment(INPUT_KEY, String.valueOf(bytes));
            } catch (Throwable e) {
                /* ignore */
            }
        } else if (result instanceof Response) {
            try {
                ((AppResponse) ((Response) result).getResult()).setAttachment(OUTPUT_KEY, String.valueOf(bytes));
            } catch (Throwable e) {
                /* ignore */
            }
        }
    }

}
