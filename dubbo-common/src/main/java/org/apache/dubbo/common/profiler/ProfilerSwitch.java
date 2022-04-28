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
package org.apache.dubbo.common.profiler;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TODO
 * Profiler
 *
 * @author allen.wu
 */
public class ProfilerSwitch {
    private final static AtomicBoolean ENABLE_DETAIL_PROFILER = new AtomicBoolean(false);

    private final static AtomicBoolean ENABLE_SIMPLE_PROFILER = new AtomicBoolean(true);

    private final static AtomicReference<Double> WARN_PERCENT = new AtomicReference<>(0.75);

    public static void enableSimpleProfiler() {
        ENABLE_SIMPLE_PROFILER.set(true);
    }

    public static void disableSimpleProfiler() {
        ENABLE_SIMPLE_PROFILER.set(false);
    }

    public static void enableDetailProfiler() {
        ENABLE_DETAIL_PROFILER.set(true);
    }

    public static void disableDetailProfiler() {
        ENABLE_DETAIL_PROFILER.set(false);
    }

    public static boolean getEnableDetailProfiler() {
        return ENABLE_DETAIL_PROFILER.get() && ENABLE_SIMPLE_PROFILER.get();
    }

    public static boolean getEnableSimpleProfiler() {
        return ENABLE_SIMPLE_PROFILER.get();
    }

    public static double getWarnPercent() {
        return WARN_PERCENT.get();
    }

    public static void setWarnPercent(double percent) {
        WARN_PERCENT.set(percent);
    }
}
