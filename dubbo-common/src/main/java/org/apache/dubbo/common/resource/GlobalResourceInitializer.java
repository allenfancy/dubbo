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
package org.apache.dubbo.common.resource;

import org.apache.dubbo.common.concurrent.CallableSafeInitializer;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * A initializer to release resource automatically on dubbo shutdown。
 * 在dubbo关闭时自动释放资源的初始化器
 *
 * @author allen.wu
 */
public class GlobalResourceInitializer<T> extends CallableSafeInitializer<T> {

    /**
     * The Dispose action to be executed on shutdown.
     * 在关机时执行的Dispose操作。
     */
    private Consumer<T> disposeAction;

    /**
     * Dispose
     */
    private Disposable disposable;

    public GlobalResourceInitializer(Callable<T> initializer) {
        super(initializer);
    }

    public GlobalResourceInitializer(Callable initializer, Consumer<T> disposeAction) {
        super(initializer);
        this.disposeAction = disposeAction;
    }

    public GlobalResourceInitializer(Callable<T> callable, Disposable disposable) {
        super(callable);
        this.disposable = disposable;
    }

    @Override
    protected T initialize() {
        T value = super.initialize();
        // 1. register disposable to release automatically
        if (this.disposable != null) {
            GlobalResourcesRepository.getInstance().registerDisposable(this.disposable);
        } else {
            GlobalResourcesRepository.getInstance().registerDisposable(() -> this.remove(disposeAction));
        }
        return value;
    }

    /**
     * 销毁处理器
     *
     * @param <T>
     */
    public interface DestroyHandler<T> {

        /**
         * Destroy the resource
         *
         * @param initializer initializer
         */
        void dispose(GlobalResourceInitializer<T> initializer);
    }
}
