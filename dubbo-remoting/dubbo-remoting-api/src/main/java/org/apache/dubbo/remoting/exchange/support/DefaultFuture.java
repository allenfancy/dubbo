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
package org.apache.dubbo.remoting.exchange.support;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.resource.GlobalResourceInitializer;
import org.apache.dubbo.common.threadpool.ThreadlessExecutor;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.timer.Timeout;
import org.apache.dubbo.common.timer.Timer;
import org.apache.dubbo.common.timer.TimerTask;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * DefaultFuture.
 * 表示此次请求-响应是否完成，也就是说，要收到响应为 Future 才算完成
 *
 * @author allen.wu
 */
public class DefaultFuture extends CompletableFuture<Object> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultFuture.class);

    /**
     * Dubbo channel map;
     * 管理请求与 Channel 之间的关联关系;
     * key:请求 ID
     * val:发送请求的Channel
     */
    private static final Map<Long, Channel> CHANNELS = new ConcurrentHashMap<>();

    /**
     * future map
     * 管理请求与 DefaultFuture 之间的关联关系;
     * key:请求 ID
     * val:请求对应的 Future
     */
    private static final Map<Long, DefaultFuture> FUTURES = new ConcurrentHashMap<>();

    /**
     * 全局初始化器.
     */
    private static final GlobalResourceInitializer<Timer> TIME_OUT_TIMER = new GlobalResourceInitializer<>(() -> new HashedWheelTimer(new NamedThreadFactory("dubbo-future-timeout", true), 30, TimeUnit.MILLISECONDS), DefaultFuture::destroy);

    /**
     * 对应请求的ID
     */
    private final Long id;
    /**
     * 对应请求体
     */
    private final Request request;

    /**
     * 发送请求的 Channel
     */
    private final Channel channel;

    /**
     * 整个请求-响应交互完成的超时时间
     */
    private final int timeout;

    /**
     * 该DefaultFuture 的创建时间
     */
    private final long start = System.currentTimeMillis();

    /**
     * 请求发送的时间
     */
    private volatile long sent;

    /**
     * 该定时任务到期时，表示对端响应超时
     */
    private Timeout timeoutCheckTask;

    /**
     * 请求关联的线程池
     */
    private ExecutorService executor;

    public ExecutorService getExecutor() {
        return executor;
    }

    public void setExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    private DefaultFuture(Channel channel, Request request, int timeout) {
        this.channel = channel;
        this.request = request;
        this.id = request.getId();
        // 默认超时时间为：1000ms
        this.timeout = timeout > 0 ? timeout : channel.getUrl().getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);
        // put into waiting map.
        FUTURES.put(id, this);
        CHANNELS.put(id, channel);
    }

    /**
     * check time out of the future
     * 检测此future超时
     */
    private static void timeoutCheck(DefaultFuture future) {
        TimeoutCheckTask task = new TimeoutCheckTask(future.getId());
        future.timeoutCheckTask = TIME_OUT_TIMER.get().newTimeout(task, future.getTimeout(), TimeUnit.MILLISECONDS);
    }

    public static void destroy() {
        TIME_OUT_TIMER.remove(Timer::stop);
        FUTURES.clear();
        CHANNELS.clear();
    }

    /**
     * init a DefaultFuture
     * 初始化 DefaultFuture
     * 1.init a DefaultFuture
     * 1. 初始化一个 DefaultFuture
     * 2.timeout check
     * 2. 初始化timeout check
     *
     * @param channel channel
     * @param request the request
     * @param timeout timeout
     * @return a new DefaultFuture
     */
    public static DefaultFuture newFuture(Channel channel, Request request, int timeout, ExecutorService executor) {
        // 1. new DefaultFuture
        final DefaultFuture future = new DefaultFuture(channel, request, timeout);
        // 2. set executor
        future.setExecutor(executor);
        // ThreadlessExecutor needs to hold the waiting future in case of circuit return.
        // 3.ThreadlessExecutor 需要保持等待的future，以防电路返回。
        // 对于ThreadlessExecutor的特殊处理，ThreadlessExecutor可以关联一个waitingFuture，就是这里创建DefaultFuture对象的future
        if (executor instanceof ThreadlessExecutor) {
            ((ThreadlessExecutor) executor).setWaitingFuture(future);
        }
        // 4. timeout check
        timeoutCheck(future);
        return future;
    }

    public static DefaultFuture getFuture(long id) {
        return FUTURES.get(id);
    }

    public static boolean hasFuture(Channel channel) {
        return CHANNELS.containsValue(channel);
    }

    /**
     * sent request
     *
     * @param channel channel
     * @param request request
     */
    public static void sent(Channel channel, Request request) {
        // 1. 根据ID获取对应的 DefaultFuture
        DefaultFuture future = FUTURES.get(request.getId());
        if (future == null) {
            return;
        }
        future.doSent();
    }

    /**
     * close a channel when a channel is inactive directly return the unfinished requests.
     * 当channel处于非活动状态时关闭channel，直接返回未完成的请求。
     *
     * @param channel channel to close
     */
    public static void closeChannel(Channel channel) {
        // 1. get all the waiting futures
        for (Map.Entry<Long, Channel> entry : CHANNELS.entrySet()) {
            if (!channel.equals(entry.getValue())) {
                continue;
            }
            DefaultFuture future = getFuture(entry.getKey());
            // 1.1 如果future不为空并且未完成，则设置为失败
            if (future != null && !future.isDone()) {
                Response disconnectResponse = new Response(future.getId());
                disconnectResponse.setStatus(Response.CHANNEL_INACTIVE);
                disconnectResponse.setErrorMessage("Channel " + channel + " is inactive. Directly return the unFinished request : " + (logger.isDebugEnabled() ? future.getRequest() : future.getRequest().copyWithoutData()));
                DefaultFuture.received(channel, disconnectResponse);
            }
        }
    }

    public static void received(Channel channel, Response response) {
        received(channel, response, false);
    }

    public static void received(Channel channel, Response response, boolean timeout) {
        try {
            // 1. 移除此channel的等待future
            DefaultFuture future = FUTURES.remove(response.getId());
            // 2. 如果future存在，进行处理
            if (future != null) {
                Timeout t = future.timeoutCheckTask;
                // 2.1 未超时，取消定时任务
                if (!timeout) {
                    // decrease Time
                    t.cancel();
                }
                // 2.2 通过future的doReceived方法处理response
                future.doReceived(response);
            } else {
                logger.warn("The timeout response finally returned at " + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date())) + ", response status is " + response.getStatus() + (channel == null ? "" : ", channel: " + channel.getLocalAddress() + " -> " + channel.getRemoteAddress()) + ", please check provider side for detailed result.");
            }
        } finally {
            CHANNELS.remove(response.getId());
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        // 1. new response
        Response errorResult = new Response(id);
        // 2. set status to client error.
        errorResult.setStatus(Response.CLIENT_ERROR);
        errorResult.setErrorMessage("request future has been canceled.");
        // 3. received
        this.doReceived(errorResult);
        FUTURES.remove(id);
        CHANNELS.remove(id);
        timeoutCheckTask.cancel();
        return true;
    }

    public void cancel() {
        this.cancel(true);
    }

    private void doReceived(Response res) {
        // 1. 如果 response 为空，则直接返回
        if (res == null) {
            throw new IllegalStateException("response cannot be null");
        }
        // 2.1 如果响应状态为OK，则进行处理
        if (res.getStatus() == Response.OK) {
            this.complete(res.getResult());
            // 2.2 如果响应为客户端超时或者服务端超时，则进行异常处理
        } else if (res.getStatus() == Response.CLIENT_TIMEOUT || res.getStatus() == Response.SERVER_TIMEOUT) {
            this.completeExceptionally(new TimeoutException(res.getStatus() == Response.SERVER_TIMEOUT, channel, res.getErrorMessage()));
        } else {
            // 2.3 进行其他异常处理
            this.completeExceptionally(new RemotingException(channel, res.getErrorMessage()));
        }

        // the result is returning, but the caller thread may still waiting to avoid endless waiting for whatever reason, notify caller thread to return.
        // 结果正在返回，但调用方线程可能仍在等待，以避免由于任何原因而没完没了地等待，通知调用方线程返回。
        // 针对ThreadlessExecutor的兜底处理，主要是防止业务线程一直阻塞在ThreadlessExecutor上
        if (executor != null && executor instanceof ThreadlessExecutor) {
            ThreadlessExecutor threadlessExecutor = (ThreadlessExecutor) executor;
            if (threadlessExecutor.isWaiting()) {
                threadlessExecutor.notifyReturn(new IllegalStateException("The result has returned, but the biz thread is still waiting" + " which is not an expected state, interrupt the thread manually by returning an exception."));
            }
        }
    }

    private long getId() {
        return id;
    }

    private Channel getChannel() {
        return channel;
    }

    private boolean isSent() {
        return sent > 0;
    }

    public Request getRequest() {
        return request;
    }

    private int getTimeout() {
        return timeout;
    }

    private void doSent() {
        sent = System.currentTimeMillis();
    }

    private String getTimeoutMessage(boolean scan) {
        long nowTimestamp = System.currentTimeMillis();
        // 1. 如果消息已发出去，则server超时, 否则client超时
        return (sent > 0 ? "Waiting server-side response timeout" : "Sending request timeout in client-side") + (scan ? " by scan timer" : "") + ". start time: " + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(start))) + ", end time: " + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(nowTimestamp))) + "," + (sent > 0 ? " client elapsed: " + (sent - start) + " ms, server elapsed: " + (nowTimestamp - sent) : " elapsed: " + (nowTimestamp - start)) + " ms, timeout: " + timeout + " ms, request: " + (logger.isDebugEnabled() ? request : request.copyWithoutData()) + ", channel: " + channel.getLocalAddress() + " -> " + channel.getRemoteAddress();
    }


    /**
     * timeout check task.
     */
    private static class TimeoutCheckTask implements TimerTask {

        /**
         * 请求ID
         */
        private final Long requestID;

        TimeoutCheckTask(Long requestID) {
            this.requestID = requestID;
        }

        @Override
        public void run(Timeout timeout) {
            // 1. 通过requestId获取到对应的future
            DefaultFuture future = DefaultFuture.getFuture(requestID);
            // 2. 如果future为空或已完成，直接返回
            if (future == null || future.isDone()) {
                return;
            }

            // 3. 如果executor不为空，则调用executor的notifyTimeout方法
            if (future.getExecutor() != null) {
                future.getExecutor().execute(() -> notifyTimeout(future));
            } else {
                notifyTimeout(future);
            }
        }

        private void notifyTimeout(DefaultFuture future) {
            // 1. create exception response.
            Response timeoutResponse = new Response(future.getId());
            // 2. set timeout status. 如果已发送，则设服务端超时，否则设客户端超时
            timeoutResponse.setStatus(future.isSent() ? Response.SERVER_TIMEOUT : Response.CLIENT_TIMEOUT);
            // 3. set timeout message.
            timeoutResponse.setErrorMessage(future.getTimeoutMessage(true));
            // 4. handle response.
            DefaultFuture.received(future.getChannel(), timeoutResponse, true);
        }
    }
}
