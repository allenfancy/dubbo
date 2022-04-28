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

/**
 * A handle associated with a {@link TimerTask} that is returned by a {@link Timer}.
 * 与{@link TimerTask}关联的句柄，由{@link Timer}返回。
 *
 * @author allen
 */
public interface Timeout {

    /**
     * Returns the {@link Timer} that created this handle.
     * 创建这个handle的timer
     *
     * @return timer timer
     */
    Timer timer();

    /**
     * Returns the {@link TimerTask} which is associated with this handle.
     * 返回timer关联的timer task.
     *
     * @return task task
     */
    TimerTask task();

    /**
     * Returns {@code true} if and only if the {@link TimerTask} associated
     * with this handle has been expired.
     * 返回是否过期
     *
     * @return True if the task has been expired, otherwise false
     */
    boolean isExpired();

    /**
     * Returns {@code true} if and only if the {@link TimerTask} associated
     * with this handle has been cancelled.
     * 返回是否已取消
     *
     * @return True if the task has been cancelled, otherwise false
     */
    boolean isCancelled();

    /**
     * Attempts to cancel the {@link TimerTask} associated with this handle.
     * If the task has been executed or cancelled already, it will return with
     * no side effect.
     * 尝试取消timer task
     *
     * @return True if the cancellation completed successfully, otherwise false
     */
    boolean cancel();
}
