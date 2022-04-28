/*
 * Copyright 2014 The Netty Project
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

package org.apache.dubbo.common.threadlocal;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The internal data structure that stores the threadLocal variables for Netty and all {@link InternalThread}s.
 * Note that this class is for internal use only. Use {@link InternalThread}
 * unless you know what you are doing.
 * 数组结构存储数据,直接通过下标访问，性能由于Hash算法。
 *
 * @author allen.wu
 */
public final class InternalThreadLocalMap {

    /**
     * 用于存储绑定到当前线程的数据
     */
    private Object[] indexedVariables;

    /**
     * 自增索引，用于计算下次存储到 indexedVariables 数组中的位置，这是一个静态字段
     */
    private static final AtomicInteger NEXT_INDEX = new AtomicInteger();

    /**
     * 当使用原生 Thread 的时候，会使用该 ThreadLocal 存储 InternalThreadLocalMap，这是一个降级策略
     */
    private static ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = new ThreadLocal<InternalThreadLocalMap>();

    /**
     * 当一个与线程绑定的值被删除之后，会被设置为 UNSET 值
     */
    static final Object UNSET = new Object();

    // Reference: https://hg.openjdk.java.net/jdk8/jdk8/jdk/file/tip/src/share/classes/java/util/ArrayList.java#l229
    private static final int ARRAY_LIST_CAPACITY_MAX_SIZE = Integer.MAX_VALUE - 8;

    private static final int ARRAY_LIST_CAPACITY_EXPAND_THRESHOLD = 1 << 30;

    public static InternalThreadLocalMap getIfSet() {
        // 1. 获取当前线程
        Thread thread = Thread.currentThread();
        // 2. 如果是InternalThread类型
        if (thread instanceof InternalThread) {
            // 2.1 直接获取InternalThreadLocalMap
            return ((InternalThread) thread).threadLocalMap();
        }
        // 3. 原生Thread则需要通过ThreadLocal获取InternalThreadLocalMap
        return slowThreadLocalMap.get();
    }

    public static InternalThreadLocalMap get() {
        // 1. 获取当前线程
        Thread thread = Thread.currentThread();
        // 2. 如果是InternalThread类型
        if (thread instanceof InternalThread) {
            // 2.1 直接获取快速获取
            return fastGet((InternalThread) thread);
        }
        // 3. 慢速后去
        return slowGet();
    }

    public static void remove() {
        // 1. 获取当前线程
        Thread thread = Thread.currentThread();
        // 2. 如果是InternalThread类型
        if (thread instanceof InternalThread) {
            ((InternalThread) thread).setThreadLocalMap(null);
        } else {
            // 3. slowThreadLocalMap 移除
            slowThreadLocalMap.remove();
        }
    }

    public static void destroy() {
        // slowThreadLocalMap设置为null.
        slowThreadLocalMap = null;
    }


    /**
     * 获取下一个可用下标
     * @return int
     */
    public static int nextVariableIndex() {
        int index = NEXT_INDEX.getAndIncrement();
        if (index >= ARRAY_LIST_CAPACITY_MAX_SIZE || index < 0) {
            NEXT_INDEX.set(ARRAY_LIST_CAPACITY_MAX_SIZE);
            throw new IllegalStateException("Too many thread-local indexed variables");
        }
        return index;
    }

    public static int lastVariableIndex() {
        return NEXT_INDEX.get() - 1;
    }

    private InternalThreadLocalMap() {
        indexedVariables = newIndexedVariableTable();
    }

    /**
     * 根据下标获取对应的值
     * @param index index
     * @return object
     */
    public Object indexedVariable(int index) {
        Object[] lookup = indexedVariables;
        return index < lookup.length ? lookup[index] : UNSET;
    }

    /**
     * 根据下标设置value
     *
     * @param index index
     * @param value value
     * @return {@code true} if and only if a new thread-local variable has been created
     */
    public boolean setIndexedVariable(int index, Object value) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object oldValue = lookup[index];
            lookup[index] = value;
            return oldValue == UNSET;
        } else {
            expandIndexedVariableTableAndSet(index, value);
            return true;
        }
    }

    /**
     * 移除
     *
     * @param index index
     * @return object
     */
    public Object removeIndexedVariable(int index) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object v = lookup[index];
            lookup[index] = UNSET;
            return v;
        } else {
            return UNSET;
        }
    }

    public int size() {
        int count = 0;
        for (Object o : indexedVariables) {
            if (o != UNSET) {
                ++count;
            }
        }

        //the fist element in `indexedVariables` is a set to keep all the InternalThreadLocal to remove
        //look at method `addToVariablesToRemove`
        return count - 1;
    }

    private static Object[] newIndexedVariableTable() {
        int variableIndex = NEXT_INDEX.get();
        int newCapacity = variableIndex < 32 ? 32 : newCapacity(variableIndex);
        Object[] array = new Object[newCapacity];
        Arrays.fill(array, UNSET);
        return array;
    }

    private static int newCapacity(int index) {
        int newCapacity;
        if (index < ARRAY_LIST_CAPACITY_EXPAND_THRESHOLD) {
            newCapacity = index;
            newCapacity |= newCapacity >>> 1;
            newCapacity |= newCapacity >>> 2;
            newCapacity |= newCapacity >>> 4;
            newCapacity |= newCapacity >>> 8;
            newCapacity |= newCapacity >>> 16;
            newCapacity++;
        } else {
            newCapacity = ARRAY_LIST_CAPACITY_MAX_SIZE;
        }
        return newCapacity;
    }

    private static InternalThreadLocalMap fastGet(InternalThread thread) {
        InternalThreadLocalMap threadLocalMap = thread.threadLocalMap();
        if (threadLocalMap == null) {
            thread.setThreadLocalMap(threadLocalMap = new InternalThreadLocalMap());
        }
        return threadLocalMap;
    }

    private static InternalThreadLocalMap slowGet() {
        ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = InternalThreadLocalMap.slowThreadLocalMap;
        InternalThreadLocalMap ret = slowThreadLocalMap.get();
        if (ret == null) {
            ret = new InternalThreadLocalMap();
            slowThreadLocalMap.set(ret);
        }
        return ret;
    }

    private void expandIndexedVariableTableAndSet(int index, Object value) {
        Object[] oldArray = indexedVariables;
        final int oldCapacity = oldArray.length;
        int newCapacity = newCapacity(index);
        Object[] newArray = Arrays.copyOf(oldArray, newCapacity);
        Arrays.fill(newArray, oldCapacity, newArray.length, UNSET);
        newArray[index] = value;
        indexedVariables = newArray;
    }
}
