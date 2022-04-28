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
package org.apache.dubbo.common.threadpool;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * The most important difference between this Executor and other normal Executor is that this one doesn't manage any thread.
 * 这个Executor与其他普通Executor之间最重要的区别是，这个Executor不管理任何线程。
 * <p>
 * Tasks submitted to this executor through {@link #execute(Runnable)} will not get scheduled to a specific thread, though normal executors always do the schedule.
 * Those tasks are stored in a blocking queue and will only be executed when a thread calls {@link #waitAndDrain()}, the thread executing the task
 * is exactly the same as the one calling waitAndDrain.
 *
 * @author allen.wu
 */
public class ThreadlessExecutor extends AbstractExecutorService {
    private static final Logger logger = LoggerFactory.getLogger(ThreadlessExecutor.class.getName());

    /**
     * 阻塞队列，用来在 IO 线程和业务线程之间传递任务
     */
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

    /**
     * 指向请求对应的 DefaultFuture 对象
     */
    private CompletableFuture<?> waitingFuture;

    /**
     * 若为true，则此次调用直接返回
     */
    private boolean finished = false;
    private volatile boolean waiting = true;

    private final Object lock = new Object();

    public CompletableFuture<?> getWaitingFuture() {
        return waitingFuture;
    }

    public void setWaitingFuture(CompletableFuture<?> waitingFuture) {
        this.waitingFuture = waitingFuture;
    }

    private boolean isFinished() {
        return finished;
    }

    private void setFinished(boolean finished) {
        this.finished = finished;
    }

    public boolean isWaiting() {
        return waiting;
    }

    private void setWaiting(boolean waiting) {
        this.waiting = waiting;
    }

    /**
     * Waits until there is a task, executes the task and all queued tasks (if there're any).
     * 等待直到有任务，执行任务和所有排队的任务(如果有)。
     * <p>
     * The task is either a normal response or a timeout response.
     * 该任务要么是正常响应，要么是超时响应。
     */
    public void waitAndDrain() throws InterruptedException {
        /*
         * Usually, {@link #waitAndDrain()} will only get called once. It blocks for the response for the first time,
         * once the response (the task) reached and being executed waitAndDrain will return, the whole request process
         * then finishes. Subsequent calls on {@link #waitAndDrain()} (if there're any) should return immediately.
         *
         * There's no need to worry that {@link #finished} is not thread-safe. Checking and updating of
         * 'finished' only appear in waitAndDrain, since waitAndDrain is binding to one RPC call (one thread), the call
         * of it is totally sequential.
         */
        // 1. 如果已经完成，直接返回
        if (isFinished()) {
            return;
        }

        // 2. 从阻塞队列中取出任务
        Runnable runnable;
        try {
            runnable = queue.take();
        } catch (InterruptedException e) {
            // 2.1 如果被中断，则抛出异常
            setWaiting(false);
            throw e;
        }

        // 3. 同步锁
        synchronized (lock) {
            // 3.1 修改waiting状态
            setWaiting(false);
            // 3.2 执行任务
            runnable.run();
        }

        // 4. 如果阻塞队列中还有其他任务，也需要一并执行
        runnable = queue.poll();
        while (runnable != null) {
            runnable.run();
            runnable = queue.poll();
        }
        // 5. 修改finished状态
        setFinished(true);
    }

    /**
     * If the calling thread is still waiting for a callback task, add the task into the blocking queue to wait for schedule.
     * Otherwise, submit to shared callback executor directly.
     *
     * @param runnable
     */
    @Override
    public void execute(Runnable runnable) {
        // 1. 包装runnable
        runnable = new RunnableWrapper(runnable);
        synchronized (lock) {
            // 2. 判断业务线程是否还在等待响应结果
            if (!isWaiting()) {
                // 2.1 不等待，直接运行
                runnable.run();
                return;
            }
            // 3. 业务线程还在等待，则将任务写入队列，然后由业务线程自己执行
            queue.add(runnable);
        }
    }

    /**
     * tells the thread blocking on {@link #waitAndDrain()} to return, despite of the current status, to avoid endless waiting.
     */
    public void notifyReturn(Throwable t) {
        // an empty runnable task.
        execute(() -> {
            waitingFuture.completeExceptionally(t);
        });
    }

    /**
     * The following methods are still not supported
     */

    @Override
    public void shutdown() {
        shutdownNow();
    }

    @Override
    public List<Runnable> shutdownNow() {
        notifyReturn(new IllegalStateException("Consumer is shutting down and this call is going to be stopped without " +
                "receiving any result, usually this is called by a slow provider instance or bad service implementation."));
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return false;
    }

    /**
     * 运行线程包装类
     */
    private static class RunnableWrapper implements Runnable {
        private final Runnable runnable;

        public RunnableWrapper(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public void run() {
            try {
                runnable.run();
            } catch (Throwable t) {
                logger.info(t);
            }
        }
    }
}
