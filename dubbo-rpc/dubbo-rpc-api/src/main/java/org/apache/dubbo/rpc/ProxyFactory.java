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

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

import static org.apache.dubbo.common.extension.ExtensionScope.FRAMEWORK;
import static org.apache.dubbo.rpc.Constants.PROXY_KEY;

/**
 * ProxyFactory. (API/SPI, Singleton, ThreadSafe)
 * 作为创建代理对象的工厂。ProxyFactory 接口是一个扩展接口，
 * getProxy() 方法为 Invoker 创建代理对象，
 * getInvoker() 方法将代理对象反向封装成 Invoker 对象
 *
 * @author allen.wu
 */
@SPI(value = "javassist", scope = FRAMEWORK)
public interface ProxyFactory {

    /**
     * create proxy.
     * 为传入的Invoker对象创建代理对象
     * @param invoker invoker
     * @return proxy proxy
     * @throws RpcException rpc exception
     */
    @Adaptive({PROXY_KEY})
    <T> T getProxy(Invoker<T> invoker) throws RpcException;

    /**
     * create proxy.
     * 为传入的Invoker对象创建代理对象
     * @param invoker invoker
     * @param generic if generic
     * @return proxy
     * @throws RpcException rpc exception
     */
    @Adaptive({PROXY_KEY})
    <T> T getProxy(Invoker<T> invoker, boolean generic) throws RpcException;

    /**
     * create invoker.
     * 将传入的代理对象封装成Invoker对象，可以暂时理解为getProxy()的逆操作
     * @param <T>   <T>
     * @param proxy proxy
     * @param type  type
     * @param url   url
     * @return invoker
     * @throws RpcException rpc exception
     */
    @Adaptive({PROXY_KEY})
    <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) throws RpcException;

}
