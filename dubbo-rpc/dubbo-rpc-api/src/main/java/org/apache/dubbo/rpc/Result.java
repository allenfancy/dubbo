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

import org.apache.dubbo.common.Experimental;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Function;


/**
 * (API, Prototype, NonThreadSafe)
 * 抽象了一次调用的返回值，其中包含了被调用方返回值（或是异常）以及附加信息;
 * <p>
 * 也可以添加回调方法，在 RPC 调用方法结束时会触发这些回调
 * An RPC {@link Result}.
 * <p>
 * Known implementations are:
 * 1. {@link AsyncRpcResult}, it's a {@link CompletionStage} whose underlying value signifies the return value of an RPC call.
 * 2. {@link AppResponse}, it inevitably inherits {@link CompletionStage} and {@link Future}, but you should never treat AppResponse as a type of Future,
 * instead, it is a normal concrete type.
 *
 * @author allen.wu
 * @serial Don't change the class name and package name.
 * @see org.apache.dubbo.rpc.Invoker#invoke(Invocation)
 * @see AppResponse
 */
public interface Result extends Serializable {

    /**
     * Get invoke result.
     *
     * @return result. if no result return null.
     */
    Object getValue();

    /**
     * 设置value
     *
     * @param value value
     */
    void setValue(Object value);

    /**
     * Get exception.
     * 获取异常
     *
     * @return exception. if no exception return null.
     */
    Throwable getException();

    /**
     * 设置异常
     *
     * @param t t
     */
    void setException(Throwable t);

    /**
     * Has exception.
     * 是否存在异常
     *
     * @return has exception.
     */
    boolean hasException();

    /**
     * Recreate.
     * <p>
     * <code>
     * if (hasException()) {
     * throw getException();
     * } else {
     * return getValue();
     * }
     * </code>
     *
     * @return result.
     * @throws Throwable t has exception throw it.
     */
    Object recreate() throws Throwable;

    /**
     * get attachments.
     * 获取附件信息
     *
     * @return attachments.
     */
    Map<String, String> getAttachments();

    /**
     * get attachments.
     * 获取附件对象信息
     *
     * @return attachments.
     */
    @Experimental("Experiment api for supporting Object transmission")
    Map<String, Object> getObjectAttachments();

    /**
     * Add the specified map to existing attachments in this instance.
     * 添加附件信息
     *
     * @param map map
     */
    void addAttachments(Map<String, String> map);

    /**
     * Add the specified map to existing attachments in this instance.
     * 添加附件信息
     *
     * @param map map
     */
    @Experimental("Experiment api for supporting Object transmission")
    void addObjectAttachments(Map<String, Object> map);

    /**
     * Replace the existing attachments with the specified param.
     * 替换附件信息
     *
     * @param map map
     */
    void setAttachments(Map<String, String> map);

    /**
     * Replace the existing attachments with the specified param.
     * 设置附件对象信息
     *
     * @param map map
     */
    @Experimental("Experiment api for supporting Object transmission")
    void setObjectAttachments(Map<String, Object> map);

    /**
     * get attachment by key.
     * 根据key获取附件信息
     *
     * @param key key
     * @return attachment value.
     */
    String getAttachment(String key);

    /**
     * get attachment by key.
     * 根据key获取附件对象
     *
     * @param key key
     * @return attachment value.
     */
    @Experimental("Experiment api for supporting Object transmission")
    Object getObjectAttachment(String key);

    /**
     * get attachment by key with default value.
     *
     * @param key          key
     * @param defaultValue default value
     * @return attachment value.
     */
    String getAttachment(String key, String defaultValue);

    /**
     * get attachment by key with default value.
     * 获取附件信息通过key和默认值
     *
     * @param key          key
     * @param defaultValue default value
     * @return attachment value.
     */
    @Experimental("Experiment api for supporting Object transmission")
    Object getObjectAttachment(String key, Object defaultValue);

    /**
     * 设置附件信息
     *
     * @param key   key
     * @param value value
     */
    void setAttachment(String key, String value);

    /**
     * set attachment by key.
     *
     * @param key   key
     * @param value value
     */
    @Experimental("Experiment api for supporting Object transmission")
    void setAttachment(String key, Object value);

    /**
     * 设置对象附件
     *
     * @param key   key
     * @param value value
     */
    @Experimental("Experiment api for supporting Object transmission")
    void setObjectAttachment(String key, Object value);

    /**
     * Add a callback which can be triggered when the RPC call finishes.
     * <p>
     * Just as the method name implies, this method will guarantee the callback being triggered under the same context as when the call was started,
     * see implementation in {@link Result#whenCompleteWithContext(BiConsumer)}
     *
     * @param fn fn
     * @return result
     */
    Result whenCompleteWithContext(BiConsumer<Result, Throwable> fn);

    /**
     * 稍后apply
     *
     * @param fn  fn
     * @param <U> u
     * @return u
     */
    <U> CompletableFuture<U> thenApply(Function<Result, ? extends U> fn);

    /**
     * 获取返回结果
     *
     * @return Result
     * @throws InterruptedException interrupted
     * @throws ExecutionException   execution
     */
    Result get() throws InterruptedException, ExecutionException;

    /**
     * 获取返回结果
     *
     * @param timeout timeout
     * @param unit    unit
     * @return Result
     * @throws InterruptedException interrupted
     * @throws ExecutionException   execution
     * @throws TimeoutException     timeout
     */
    Result get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException;
}
