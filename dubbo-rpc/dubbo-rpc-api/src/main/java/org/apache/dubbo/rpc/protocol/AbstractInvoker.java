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
package org.apache.dubbo.rpc.protocol;

import org.apache.dubbo.common.Node;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.ThreadlessExecutor;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.protocol.dubbo.FutureAdapter;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_REMOTING_SERIALIZATION;
import static org.apache.dubbo.remoting.Constants.SERIALIZATION_KEY;
import static org.apache.dubbo.rpc.Constants.SERIALIZATION_ID_KEY;

/**
 * This Invoker works on Consumer side.
 * abstract Invoker 在消费侧工作
 *
 * @author allen.wu
 */
public abstract class AbstractInvoker<T> implements Invoker<T> {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractInvoker.class);

    /**
     * Service interface type
     * 该Invoker对象封装的业务接口类型
     */
    private final Class<T> type;

    /**
     * 与当前 Invoker 关联的 URL 对象，其中包含了全部的配置信息
     * {@link Node} url
     */
    private final URL url;

    /**
     * 当前 Invoker 关联的一些附加信息，这些附加信息可以来自关联的 URL。
     * 在 AbstractInvoker 的构造函数的某个重载中，会调用 convertAttachment() 方法，其中就会从关联的 URL 对象获取指定的 KV 值记录到 attachment 集合中
     * {@link Invoker} default attachment
     */
    private final Map<String, Object> attachment;

    /**
     * 当前available=true && destroyed = false 时，该 Invoker可用
     * 当前available=false && destroyed = true 时，该 Invoker不可用
     * {@link Node} available
     */
    private volatile boolean available = true;

    /**
     * 当前available=true && destroyed = false 时，该 Invoker可用
     * 当前available=false && destroyed = true 时，该 Invoker不可用
     * <p>
     * {@link Node} destroy
     */
    private boolean destroyed = false;

    /**
     * Whether set future to Thread Local when invocation mode is sync
     * future.sync.set如果没有值，默认为true。
     */
    private static final boolean SET_FUTURE_WHEN_SYNC = Boolean.parseBoolean(System.getProperty(CommonConstants.SET_FUTURE_IN_SYNC_MODE, "true"));

    // -- Constructor

    public AbstractInvoker(Class<T> type, URL url) {
        this(type, url, (Map<String, Object>) null);
    }

    public AbstractInvoker(Class<T> type, URL url, String[] keys) {
        this(type, url, convertAttachment(url, keys));
    }

    public AbstractInvoker(Class<T> type, URL url, Map<String, Object> attachment) {
        if (type == null) {
            throw new IllegalArgumentException("service type == null");
        }
        if (url == null) {
            throw new IllegalArgumentException("service url == null");
        }
        this.type = type;
        this.url = url;
        this.attachment = attachment == null
                ? null
                : Collections.unmodifiableMap(attachment);
    }

    private static Map<String, Object> convertAttachment(URL url, String[] keys) {
        if (ArrayUtils.isEmpty(keys)) {
            return null;
        }
        Map<String, Object> attachment = new HashMap<>();
        for (String key : keys) {
            String value = url.getParameter(key);
            if (value != null && value.length() > 0) {
                attachment.put(key, value);
            }
        }
        return attachment;
    }

    // -- Public api

    @Override
    public Class<T> getInterface() {
        return type;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public void destroy() {
        this.destroyed = true;
        setAvailable(false);
    }

    protected void setAvailable(boolean available) {
        this.available = available;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + (getUrl() == null ? "" : getUrl().getAddress());
    }

    @Override
    public Result invoke(Invocation inv) throws RpcException {
        // if invoker is destroyed due to address refresh from registry, let's allow the current invoke to proceed
        // 如果由于注册表中的地址刷新而销毁了调用程序，那么让我们允许当前调用继续进行
        if (isDestroyed()) {
            logger.warn("Invoker for service " + this + " on consumer " + NetUtils.getLocalHost() + " is destroyed, "
                    + ", dubbo version is " + Version.getVersion() + ", this invoker should not be used any longer");
        }

        // 1. 首先将传入的Invocation转换为RpcInvocation
        RpcInvocation invocation = (RpcInvocation) inv;

        // 2. 预处理 rpc invocation
        prepareInvocation(invocation);

        // 3. do invoke rpc invocation and return async result
        AsyncRpcResult asyncResult = doInvokeAndReturn(invocation);

        // 4. wait rpc result if sync
        waitForResultIfSync(asyncResult, invocation);
        // 5.
        return asyncResult;
    }

    private void prepareInvocation(RpcInvocation inv) {
        inv.setInvoker(this);

        // 1. 将前文介绍的attachment集合添加为Invocation的附加信息
        addInvocationAttachments(inv);
        // 2. 设置此次调用的模式，异步还是同步
        inv.setInvokeMode(RpcUtils.getInvokeMode(url, inv));
        // 3. 如果是异步调用，给这次调用添加一个唯一ID
        RpcUtils.attachInvocationIdIfAsync(getUrl(), inv);
        // 4. 设置序列化id 如果serialization没有设置，则默认hessian2
        Byte serializationId = CodecSupport.getIDByName(getUrl().getParameter(SERIALIZATION_KEY, DEFAULT_REMOTING_SERIALIZATION));
        if (serializationId != null) {
            inv.put(SERIALIZATION_ID_KEY, serializationId);
        }
    }

    private void addInvocationAttachments(RpcInvocation invocation) {
        // 1. invoker attachment
        if (CollectionUtils.isNotEmptyMap(attachment)) {
            invocation.addObjectAttachmentsIfAbsent(attachment);
        }

        // 2. client context attachment
        Map<String, Object> clientContextAttachments = RpcContext.getClientAttachment().getObjectAttachments();
        if (CollectionUtils.isNotEmptyMap(clientContextAttachments)) {
            invocation.addObjectAttachmentsIfAbsent(clientContextAttachments);
        }
    }

    private AsyncRpcResult doInvokeAndReturn(RpcInvocation invocation) {
        AsyncRpcResult asyncResult;
        try {
            // 1. 调用子类实现的doInvoke()方法
            asyncResult = (AsyncRpcResult) doInvoke(invocation);
        } catch (InvocationTargetException e) {
            // 2. 异常处理逻辑
            Throwable te = e.getTargetException();
            if (te != null) {
                // 2.1 如果是RpcException，即BizException，设置Code=3
                if (te instanceof RpcException) {
                    ((RpcException) te).setCode(RpcException.BIZ_EXCEPTION);
                }
                // 2.2 newDefaultAsyncResult
                asyncResult = AsyncRpcResult.newDefaultAsyncResult(null, te, invocation);
            } else {
                //2.3 如果不是RpcException，直接新建newDefaultAsyncResult
                asyncResult = AsyncRpcResult.newDefaultAsyncResult(null, e, invocation);
            }
        } catch (RpcException e) {
            // 2.4 如果抛出的直接是RpcException;并code=3 ，直接newDefaultAsyncResult
            if (e.isBiz()) {
                asyncResult = AsyncRpcResult.newDefaultAsyncResult(null, e, invocation);
            } else {
                //2.5 否则直接爆出异常
                throw e;
            }
        } catch (Throwable e) {
            // 2.6 如果是Throwable异常，直接新建一个异常
            asyncResult = AsyncRpcResult.newDefaultAsyncResult(null, e, invocation);
        }

        // 3. 不是同步调用
        if (SET_FUTURE_WHEN_SYNC || invocation.getInvokeMode() != InvokeMode.SYNC) {
            // set server context
            RpcContext.getServiceContext().setFuture(new FutureAdapter<>(asyncResult.getResponseFuture()));
        }
        // 4. 返回结果
        return asyncResult;
    }

    private void waitForResultIfSync(AsyncRpcResult asyncResult, RpcInvocation invocation) {
        // 1. 如果不是同步调用，直接返回
        if (InvokeMode.SYNC != invocation.getInvokeMode()) {
            return;
        }
        // 2. tongue调用
        try {
            /*
             * NOTICE!
             * must call {@link java.util.concurrent.CompletableFuture#get(long, TimeUnit)} because
             * {@link java.util.concurrent.CompletableFuture#get()} was proved to have serious performance drop.
             * 必须使用CompletableFuture#get(long,TimeUnit),因为Block的被证明有严重的性能下降。
             */
            // 1. 获取超时时间
            Object timeout = invocation.getObjectAttachment(TIMEOUT_KEY);
            if (timeout instanceof Integer) {
                asyncResult.get((Integer) timeout, TimeUnit.MILLISECONDS);
            } else {
                asyncResult.get(Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            throw new RpcException("Interrupted unexpectedly while waiting for remote result to return! method: " +
                    invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        } catch (ExecutionException e) {
            Throwable rootCause = e.getCause();
            if (rootCause instanceof TimeoutException) {
                throw new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout. method: " +
                        invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
            } else if (rootCause instanceof RemotingException) {
                throw new RpcException(RpcException.NETWORK_EXCEPTION, "Failed to invoke remote method: " +
                        invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
            } else {
                throw new RpcException(RpcException.UNKNOWN_EXCEPTION, "Fail to invoke remote method: " +
                        invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
            }
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout. method: " +
                    invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        } catch (Throwable e) {
            throw new RpcException(e.getMessage(), e);
        }
    }

    // -- Protected api

    protected ExecutorService getCallbackExecutor(URL url, Invocation inv) {
        // 1. 如果是同步模型，则使用ThreadlessExecutor线程池
        if (InvokeMode.SYNC == RpcUtils.getInvokeMode(getUrl(), inv)) {
            return new ThreadlessExecutor();
        }
        // 2. 否则通过url获取线程池
        return url.getOrDefaultApplicationModel().getExtensionLoader(ExecutorRepository.class)
                .getDefaultExtension()
                .getExecutor(url);
    }

    /**
     * Specific implementation of the {@link #invoke(Invocation)} method
     *
     * @param invocation in
     * @return Result
     * @throws Throwable t
     */
    protected abstract Result doInvoke(Invocation invocation) throws Throwable;
}
