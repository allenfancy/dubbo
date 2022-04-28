/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.dubbo.common.timer;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.dubbo.common.constants.CommonConstants.OS_NAME_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.OS_WIN_PREFIX;

/**
 * A {@link Timer} optimized for approximated I/O timeout scheduling.
 * 一个优化近似IO超时调度的{@link Timer}
 * <h3>Tick Duration</h3>
 * <p>
 * As described with 'approximated', this timer does not execute the scheduled
 * {@link TimerTask} on time.
 * 正如“近似”所描述的那样，此计时器不会定时执行预定的{@link TimerTask}。
 * <p>
 * {@link HashedWheelTimer}, on every tick, will
 * check if there are any {@link TimerTask}s behind the schedule and execute
 * them.
 * <p>
 * You can increase or decrease the accuracy of the execution timing by
 * specifying smaller or larger tick duration in the constructor.
 * 可以通过在构造函数中指定更小或更大的tick 持续时间来增加或减少执行计时的准确性。
 * <p>
 * In most network applications, I/O timeout does not need to be accurate.
 * 在大多数网络应用程序中，I/O超时不需要准确。
 * <p>
 * Therefore,the default tick duration is 100 milliseconds and you will not need to try
 * different configurations in most cases.
 * 因此，默认的tick持续时间是100毫秒，在大多数情况下，您不需要尝试不同的配置。
 * <h3>Ticks per Wheel (Wheel Size)</h3>
 * <p>
 * {@link HashedWheelTimer} maintains a data structure called 'wheel'.
 * To put simply, a wheel is a hash table of {@link TimerTask}s whose hash
 * function is 'dead line of the task'.  The default number of ticks per wheel
 * (i.e. the size of the wheel) is 512.  You could specify a larger value
 * if you are going to schedule a lot of timeouts.
 *
 * <h3>Do not create many instances.</h3>
 * <p>
 * {@link HashedWheelTimer} creates a new thread whenever it is instantiated and
 * started.  Therefore, you should make sure to create only one instance and
 * share it across your application.  One of the common mistakes, that makes
 * your application unresponsive, is to create a new instance for every connection.
 *
 * <h3>Implementation Details</h3>
 * <p>
 * {@link HashedWheelTimer} is based on
 * <a href="http://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and
 * Tony Lauck's paper,
 * <a href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
 * and Hierarchical Timing Wheels: data structures to efficiently implement a
 * timer facility'</a>.  More comprehensive slides are located
 * <a href="http://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt">here</a>.
 *
 * @author allen.wu
 */
public class HashedWheelTimer implements Timer {

    /**
     * may be in spi?
     */
    public static final String NAME = "hased";

    private static final Logger logger = LoggerFactory.getLogger(HashedWheelTimer.class);

    /**
     * 静态变量 统计HashedWheelTimer数量
     */
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();

    /**
     * 静态变量 记录是否创建了太多时间轮实例
     */
    private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();

    /**
     *最多允许创建的时间轮实例是6
     */
    private static final int INSTANCE_COUNT_LIMIT = 64;

    /**
     * workerState变量的更新器
     */
    private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimer.class, "workerState");

    /**
     * 工作线程
     */
    private final Worker worker = new Worker();
    private final Thread workerThread;

    /**
     * 工作状态：初始化
     */
    private static final int WORKER_STATE_INIT = 0;

    /**
     * 工作状态：启动
     */
    private static final int WORKER_STATE_STARTED = 1;

    /**
     *工作状态：关闭
     */
    private static final int WORKER_STATE_SHUTDOWN = 2;

    /**
     * 0 - init, 1 - started, 2 - shut down
     */
    @SuppressWarnings({"unused", "FieldMayBeFinal"})
    private volatile int workerState;

    /**
     * 时间轮转动周期
     */
    private final long tickDuration;

    /**
     * 时间轮数据结构：数组+链表
     */
    private final HashedWheelBucket[] wheel;

    /**
     * 数组长度减1
     */
    private final int mask;

    /**
     * 初始化时间轮开始时间时进行阻塞
     */
    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);

    /**
     *保存新添加的Timeout
     */
    private final Queue<HashedWheelTimeout> timeouts = new LinkedBlockingQueue<>();

    /**
     * 保存被取消的Timeout
     */
    private final Queue<HashedWheelTimeout> cancelledTimeouts = new LinkedBlockingQueue<>();

    /**
     * 记录添加的Timeout数量
     */
    private final AtomicLong pendingTimeouts = new AtomicLong(0);

    /**
     * 最多允许添加的Timeout数量
     */
    private final long maxPendingTimeouts;

    /**
     * 时间轮开始转动时间
     */
    private volatile long startTime;

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}), default tick duration, and
     * default number of ticks per wheel.
     */
    public HashedWheelTimer() {
        this(Executors.defaultThreadFactory());
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}) and default number of ticks
     * per wheel.
     *
     * @param tickDuration the duration between tick
     * @param unit         the time unit of the {@code tickDuration}
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit) {
        this(Executors.defaultThreadFactory(), tickDuration, unit);
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}).
     *
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel);
    }

    /**
     * Creates a new timer with the default tick duration and default number of
     * ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public HashedWheelTimer(ThreadFactory threadFactory) {
        this(threadFactory, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new timer with the default number of ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
        this(threadFactory, tickDuration, unit, 512);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, -1);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory      a {@link ThreadFactory} that creates a
     *                           background {@link Thread} which is dedicated to
     *                           {@link TimerTask} execution.
     * @param tickDuration       the duration between tick
     * @param unit               the time unit of the {@code tickDuration}
     * @param ticksPerWheel      the size of the wheel
     * @param maxPendingTimeouts The maximum number of pending timeouts after which call to
     *                           {@code newTimeout} will result in
     *                           {@link java.util.concurrent.RejectedExecutionException}
     *                           being thrown. No maximum pending timeouts limit is assumed if
     *                           this value is 0 or negative.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel,
            long maxPendingTimeouts) {

        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        if (tickDuration <= 0) {
            throw new IllegalArgumentException("tickDuration must be greater than 0: " + tickDuration);
        }
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }

        // Normalize ticksPerWheel to power of two and initialize the wheel.Bucket数组长度 类似HashMap#tableSizeFor() 会计算一个2的N次方
        // 默认512
        wheel = createWheel(ticksPerWheel);
        mask = wheel.length - 1;

        // Convert tickDuration to nanos. 将转动周期转为纳秒
        this.tickDuration = unit.toNanos(tickDuration);

        // Prevent overflow. 防止溢出
        if (this.tickDuration >= Long.MAX_VALUE / wheel.length) {
            throw new IllegalArgumentException(String.format(
                    "tickDuration: %d (expected: 0 < tickDuration in nanos < %d",
                    tickDuration, Long.MAX_VALUE / wheel.length));
        }

        //创建工作线程 默认工厂是Executors.defaultThreadFactory
        workerThread = threadFactory.newThread(worker);

        // 默认是-1 当为0或者-1时代表不限制
        this.maxPendingTimeouts = maxPendingTimeouts;

        // 如果已经创建了太多的时间轮实例 打印日志提醒
        if (INSTANCE_COUNTER.incrementAndGet() > INSTANCE_COUNT_LIMIT &&
                WARNED_TOO_MANY_INSTANCES.compareAndSet(false, true)) {
            reportTooManyInstances();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            // This object is going to be GCed and it is assumed the ship has sailed to do a proper shutdown. If
            // we have not yet shutdown then we want to make sure we decrement the active instance count.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }
        }
    }

    private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException(
                    "ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }
        if (ticksPerWheel > 1073741824) {
            throw new IllegalArgumentException(
                    "ticksPerWheel may not be greater than 2^30: " + ticksPerWheel);
        }

        ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
        HashedWheelBucket[] wheel = new HashedWheelBucket[ticksPerWheel];
        for (int i = 0; i < wheel.length; i++) {
            wheel[i] = new HashedWheelBucket();
        }
        return wheel;
    }

    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = ticksPerWheel - 1;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 1;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 2;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 4;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 8;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 16;
        return normalizedTicksPerWheel + 1;
    }

    /**
     * Starts the background thread explicitly.  The background thread will
     * start automatically on demand even if you did not call this method.
     *
     * @throws IllegalStateException if this timer has been
     *                               {@linkplain #stop() stopped} already
     */
    public void start() {
        switch (WORKER_STATE_UPDATER.get(this)) {
            // 通过UPDATER获取workState状态
            case WORKER_STATE_INIT:
                // CAS修改状态成功后 启动工作线程
                if (WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_INIT, WORKER_STATE_STARTED)) {
                    workerThread.start();
                }
                break;
            case WORKER_STATE_STARTED:
                break;
            case WORKER_STATE_SHUTDOWN:
                throw new IllegalStateException("cannot be started once stopped");
            default:
                throw new Error("Invalid WorkerState");
        }

        // Wait until the startTime is initialized by the worker.必要情况下阻塞主线程 等待工作线程初始化startTime
        while (startTime == 0) {
            try {
                startTimeInitialized.await();
            } catch (InterruptedException ignore) {
                // Ignore - it will be ready very soon.
            }
        }
    }

    @Override
    public Set<Timeout> stop() {
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException(
                    HashedWheelTimer.class.getSimpleName() +
                            ".stop() cannot be called from " +
                            TimerTask.class.getSimpleName());
        }

        if (!WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_STARTED, WORKER_STATE_SHUTDOWN)) {
            // workerState can be 0 or 2 at this moment - let it always be 2.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }

            return Collections.emptySet();
        }

        try {
            boolean interrupted = false;
            while (workerThread.isAlive()) {
                workerThread.interrupt();
                try {
                    workerThread.join(100);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            INSTANCE_COUNTER.decrementAndGet();
        }
        return worker.unprocessedTimeouts();
    }

    @Override
    public boolean isStop() {
        return WORKER_STATE_SHUTDOWN == WORKER_STATE_UPDATER.get(this);
    }

    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        // 增加Timeout计数器
        long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();

        // 如果限制Timeout数量 且当前添加数量超过了限制数量 则直接抛异常
        if (maxPendingTimeouts > 0 && pendingTimeoutsCount > maxPendingTimeouts) {
            pendingTimeouts.decrementAndGet();
            throw new RejectedExecutionException("Number of pending timeouts ("
                    + pendingTimeoutsCount + ") is greater than or equal to maximum allowed pending "
                    + "timeouts (" + maxPendingTimeouts + ")");
        }

        // 开始时间轮的运转 完成startTime初始化
        start();

        // Add the timeout to the timeout queue which will be processed on the next tick.
        // During processing all the queued HashedWheelTimeouts will be added to the correct HashedWheelBucket.
        // 计算这个timeout的deadline
        long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;

        // Guard against overflow.
        // 如果delay大于0  deadline一般不会小于0；如果真的小于0 则说明这个timeout已经过期 设置最长等待时间
        if (delay > 0 && deadline < 0) {
            deadline = Long.MAX_VALUE;
        }
        // 创建HashedWheelTimeout加入队列
        HashedWheelTimeout timeout = new HashedWheelTimeout(this, task, deadline);
        timeouts.add(timeout);
        return timeout;
    }

    /**
     * Returns the number of pending timeouts of this {@link Timer}.
     */
    public long pendingTimeouts() {
        return pendingTimeouts.get();
    }

    private static void reportTooManyInstances() {
        String resourceType = ClassUtils.simpleClassName(HashedWheelTimer.class);
        logger.error("You are creating too many " + resourceType + " instances. " +
                resourceType + " is a shared resource that must be reused across the JVM," +
                "so that only a few instances are created.");
    }

    private final class Worker implements Runnable {

        private final Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();

        private long tick;

        @Override
        public void run() {
            // Initialize the startTime. 初始化启动时间    单位是纳秒
            startTime = System.nanoTime();
            if (startTime == 0) {
                // We use 0 as an indicator for the uninitialized value here, so make sure it's not 0 when initialized.
                startTime = 1;
            }

            // Notify the other threads waiting for the initialization at start().
            // startTime初始化完成 结束主线程的阻塞
            startTimeInitialized.countDown();

            do {
                // 等待直到下一次tick来临 返回tick时刻
                final long deadline = waitForNextTick();
                if (deadline > 0) {
                    // 计算Bucket索引
                    int idx = (int) (tick & mask);
                    // 处理被取消的任务 就是将cancelledTimeouts队列中的timeout移除
                    processCancelledTasks();
                    // 寻址拿到目标桶
                    HashedWheelBucket bucket = wheel[idx];
                    // 1. 将timeouts队列中的timeout 转移到Bucket上
                    // 2. 为了避免工作线程转移时 其余线程循环添加新的timeout 导致无法结束这个转移过程
                    // 3. 所以一次tick最多转移10万个timeout
                    transferTimeoutsToBuckets();
                    // 对目标桶上的timeout进行处理
                    bucket.expireTimeouts(deadline);
                    //tick次数加1
                    tick++;
                }
            } while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);

            // Fill the unprocessedTimeouts so we can return them from stop() method.
            for (HashedWheelBucket bucket : wheel) {
                bucket.clearTimeouts(unprocessedTimeouts);
            }
            for (; ; ) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    break;
                }
                if (!timeout.isCancelled()) {
                    unprocessedTimeouts.add(timeout);
                }
            }
            processCancelledTasks();
        }

        private void transferTimeoutsToBuckets() {
            // transfer only max. 100000 timeouts per tick to prevent a thread to stale the workerThread when it just
            // adds new timeouts in a loop.
            // 最多转移10万个Timeout
            for (int i = 0; i < 100000; i++) {
                // 取出队列中的timeout
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                if (timeout.state() == HashedWheelTimeout.ST_CANCELLED) {
                    // Was cancelled in the meantime. 如果timeout被取消了 则不处理
                    continue;
                }

                // timeout执行时刻处理tick间隔 得到执行前需要的tick次数
                long calculated = timeout.deadline / tickDuration;
                // 需要tick的次数减去已经tick的次数 再除以Bucket数组长度 拿到需要等待的圈数
                timeout.remainingRounds = (calculated - tick) / wheel.length;

                // Ensure we don't schedule for past. 重新计算一个新的Bucket索引
                final long ticks = Math.max(calculated, tick);
                int stopIndex = (int) (ticks & mask);

                // 从Bucket数组中取出这个Bucket 然后将上面取出来的Timeout添加到桶中
                HashedWheelBucket bucket = wheel[stopIndex];
                bucket.addTimeout(timeout);
            }
        }

        private void processCancelledTasks() {
            for (; ; ) {
                // 循环取出取消timeout队列中的元素 然后从Bucket上移除
                HashedWheelTimeout timeout = cancelledTimeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                try {
                    timeout.remove();
                } catch (Throwable t) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("An exception was thrown while process a cancellation task", t);
                    }
                }
            }
        }

        /**
         * calculate goal nanoTime from startTime and current tick number,
         * then wait until that goal has been reached.
         *
         * @return Long.MIN_VALUE if received a shutdown request,
         * current time otherwise (with Long.MIN_VALUE changed by +1)
         */
        private long waitForNextTick() {
            // 下一次时间轮转动的时刻
            long deadline = tickDuration * (tick + 1);

            for (; ; ) {
                // 计算当前时刻
                final long currentTime = System.nanoTime() - startTime;
                // 计算需要休眠的时间
                long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;

                // 不需要休眠 立即返回当前时刻
                if (sleepTimeMs <= 0) {
                    if (currentTime == Long.MIN_VALUE) {
                        return -Long.MAX_VALUE;
                    } else {
                        return currentTime;
                    }
                }
                // Windows平台对休眠时间特殊处理
                if (isWindows()) {
                    sleepTimeMs = sleepTimeMs / 10 * 10;
                }

                try {
                    // 进行休眠 休眠结束后通过下一次的for循环来结束这个方法
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException ignored) {
                    // 如果工作线程被中断 则修改workState状态为已停止
                    if (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_SHUTDOWN) {
                        return Long.MIN_VALUE;
                    }
                }
            }
        }

        Set<Timeout> unprocessedTimeouts() {
            return Collections.unmodifiableSet(unprocessedTimeouts);
        }
    }

    private static final class HashedWheelTimeout implements Timeout {

        /**
         * 初始化
         */
        private static final int ST_INIT = 0;

        /**
         * 取消
         */
        private static final int ST_CANCELLED = 1;

        /**
         * 过期
         */
        private static final int ST_EXPIRED = 2;

        /**
         * state成员变量的更新器
         */
        private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimeout.class, "state");

        /**
         * 绑定的定时器
         */
        private final HashedWheelTimer timer;

        /**
         * 绑定的任务
         */
        private final TimerTask task;

        /**
         * timeout处理任务的deadline
         */
        private final long deadline;

        @SuppressWarnings({"unused", "FieldMayBeFinal", "RedundantFieldInitialization"})
        private volatile int state = ST_INIT;

        /**
         * RemainingRounds will be calculated and set by Worker.transferTimeoutsToBuckets() before the
         * HashedWheelTimeout will be added to the correct HashedWheelBucket.
         * 剩余圈数，当剩余圈数大于0时，当前timeout才能去执行任务
         */
        long remainingRounds;

        /*
         * This will be used to chain timeouts in HashedWheelTimerBucket via a double-linked-list.
         * 维护双向链表的指针
         * As only the workerThread will act on it there is no need for synchronization / volatile.
         */

        /**
         * 下一个节点
         */
        HashedWheelTimeout next;

        /**
         * 上一个节点
         */
        HashedWheelTimeout prev;

        /**
         * The bucket to which the timeout was added
         * 用来保存当前TimeOut的桶
         */
        HashedWheelBucket bucket;

        HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
            this.timer = timer;
            this.task = task;
            this.deadline = deadline;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public boolean cancel() {
            // only update the state it will be removed from HashedWheelBucket on next tick.
            if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
                return false;
            }
            // If a task should be canceled we put this to another queue which will be processed on each tick.
            // So this means that we will have a GC latency of max. 1 tick duration which is good enough. This way we
            // can make again use of our LinkedBlockingQueue and so minimize the locking / overhead as much as possible.
            timer.cancelledTimeouts.add(this);
            return true;
        }

        /**
         * 将当前Timeout从保存它的桶中移除 具体看下面HashedWheelBucket分
         */
        void remove() {
            HashedWheelBucket bucket = this.bucket;
            if (bucket != null) {
                bucket.remove(this);
            } else {
                timer.pendingTimeouts.decrementAndGet();
            }
        }

        public boolean compareAndSetState(int expected, int state) {
            return STATE_UPDATER.compareAndSet(this, expected, state);
        }

        public int state() {
            return state;
        }

        @Override
        public boolean isCancelled() {
            return state() == ST_CANCELLED;
        }

        @Override
        public boolean isExpired() {
            return state() == ST_EXPIRED;
        }

        public void expire() {
            //1.通过UPDATER修改state为expired状态 修改失败直接return
            if (!compareAndSetState(ST_INIT, ST_EXPIRED)) {
                return;
            }

            try {
                // 2.执行任务 具体可以参考RetryTimerTask#run()
                task.run(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', t);
                }
            }
        }

        @Override
        public String toString() {
            final long currentTime = System.nanoTime();
            long remaining = deadline - currentTime + timer.startTime;
            String simpleClassName = ClassUtils.simpleClassName(this.getClass());

            StringBuilder buf = new StringBuilder(192)
                    .append(simpleClassName)
                    .append('(')
                    .append("deadline: ");
            if (remaining > 0) {
                buf.append(remaining)
                        .append(" ns later");
            } else if (remaining < 0) {
                buf.append(-remaining)
                        .append(" ns ago");
            } else {
                buf.append("now");
            }

            if (isCancelled()) {
                buf.append(", cancelled");
            }

            return buf.append(", task: ")
                    .append(task())
                    .append(')')
                    .toString();
        }
    }

    /**
     * Bucket that stores HashedWheelTimeouts.
     * 桶用于存储HashedWheelTimeout
     * These are stored in a linked-list like datastructure to allow easy removal of HashedWheelTimeouts in the middle.
     * 它们被存储在一个类似链表的数据结构中，这样可以方便地删除中间的hashhedwheeltimeouts。
     * Also the HashedWheelTimeout act as nodes themself and so no extra object creation is needed.
     * 另外，hashhedwheeltimeout本身充当节点，因此不需要创建额外的对象。
     *
     * @author allen.wu
     */
    private static final class HashedWheelBucket {

        /**
         * Used for the linked-list datastructure
         * 使用链表数据结构
         */
        private HashedWheelTimeout head;
        private HashedWheelTimeout tail;

        /**
         * Add {@link HashedWheelTimeout} to this bucket.
         * 注意:Timeout一开始是放在队列中的，并不是在时间轮上，是在Tick触发时才将队列中的元素散列到时间轮底层的桶上.
         * 且添加到链表尾部
         *
         * @param timeout HashWheelTimeout
         */
        void addTimeout(HashedWheelTimeout timeout) {
            assert timeout.bucket == null;
            timeout.bucket = this;
            if (head == null) {
                head = tail = timeout;
            } else {
                tail.next = timeout;
                timeout.prev = tail;
                tail = timeout;
            }
        }

        /**
         * Expire all {@link HashedWheelTimeout}s for the given {@code deadline}.
         * 作用：遍历Bucket双向链表上的Timeout
         *
         * 1.如果Timeout的remainingRounds不大于0，且Timeout已经到了执行时间，则从Bucket中删除这个Timeout，然后执行这个任务
         * 2.如果Timeout已经被取消，直接从从Bucket中删除
         * 如果Timeout没有被取消，同时remainingRounds大于0，则说明本次尚不执行这个Timeout，只是将它的remainingRounds减1
         */
        void expireTimeouts(long deadline) {
            HashedWheelTimeout timeout = head;

            // process all timeouts
            while (timeout != null) {
                HashedWheelTimeout next = timeout.next;
                if (timeout.remainingRounds <= 0) {
                    next = remove(timeout);
                    if (timeout.deadline <= deadline) {
                        timeout.expire();
                    } else {
                        // The timeout was placed into a wrong slot. This should never happen.
                        throw new IllegalStateException(String.format(
                                "timeout.deadline (%d) > deadline (%d)", timeout.deadline, deadline));
                    }
                } else if (timeout.isCancelled()) {
                    next = remove(timeout);
                } else {
                    timeout.remainingRounds--;
                }
                timeout = next;
            }
        }

        /**
         * 移除
         *
         * @param timeout timeout
         * @return HashWheelTimeout
         */
        public HashedWheelTimeout remove(HashedWheelTimeout timeout) {
            // 1. remove from linked-list
            HashedWheelTimeout next = timeout.next;
            // remove timeout that was either processed or cancelled by updating the linked-list
            if (timeout.prev != null) {
                timeout.prev.next = next;
            }
            if (timeout.next != null) {
                timeout.next.prev = timeout.prev;
            }
            // 2. 如果timeout是head，则更新head
            if (timeout == head) {
                // if timeout is also the tail we need to adjust the entry too
                // 如果是也是尾指针，则更新tail和head
                if (timeout == tail) {
                    tail = null;
                    head = null;
                } else {
                    // 将头指针指向下一个节点
                    head = next;
                }
            } else if (timeout == tail) {
                // if the timeout is the tail modify the tail to be the prev node.
                // timeout是尾部指针，则timeout.prev是尾部指针的前一个节点
                tail = timeout.prev;
            }
            // null out prev, next and bucket to allow for GC.
            timeout.prev = null;
            timeout.next = null;
            timeout.bucket = null;
            timeout.timer.pendingTimeouts.decrementAndGet();
            return next;
        }

        /**
         * Clear this bucket and return all not expired / cancelled {@link Timeout}s.
         */
        void clearTimeouts(Set<Timeout> set) {
            for (; ; ) {
                HashedWheelTimeout timeout = pollTimeout();
                if (timeout == null) {
                    return;
                }
                if (timeout.isExpired() || timeout.isCancelled()) {
                    continue;
                }
                set.add(timeout);
            }
        }

        private HashedWheelTimeout pollTimeout() {
            // 1.当前bucket中的头节点
            HashedWheelTimeout head = this.head;
            if (head == null) {
                return null;
            }
            // 2.链表维护
            HashedWheelTimeout next = head.next;
            if (next == null) {
                tail = this.head = null;
            } else {
                this.head = next;
                next.prev = null;
            }

            // null out prev and next to allow for GC.
            // 3.引用设置为NULL，有助于GC
            head.next = null;
            head.prev = null;
            head.bucket = null;
            // 4. 返回head
            return head;
        }
    }

    private static final boolean IS_OS_WINDOWS = System.getProperty(OS_NAME_KEY, "").toLowerCase(Locale.US).contains(OS_WIN_PREFIX);

    private boolean isWindows() {
        return IS_OS_WINDOWS;
    }
}
