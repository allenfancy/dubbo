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

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ScopeModelAware;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_SERVER_SHUTDOWN_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.SHUTDOWN_WAIT_KEY;

/**
 * abstract ProtocolSupport.
 * 提供了一些 Protocol 实现需要的公共能力以及公共字段
 *
 * @author allen.wu
 */
public abstract class AbstractProtocol implements Protocol, ScopeModelAware {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 用于存储出去的服务集合，其中的 Key 通过 ProtocolUtils.serviceKey() 方法创建的服务标识，在 ProtocolUtils 中维护了多层的 Map 结构
     * 首先按照 group 分组，在实践中我们可以根据需求设置 group，例如，按照机房、地域等进行 group 划分，做到就近调用；
     * 在 GroupServiceKeyCache 中，依次按照 serviceName、serviceVersion、port 进行分类，
     * 最终缓存的 serviceKey 是前面三者拼接而成的
     */
    protected final Map<String, Exporter<?>> exporterMap = new ConcurrentHashMap<>();

    /**
     * <host:port, ProtocolServer>
     * 记录了全部的 ProtocolServer 实例，
     * 其中的 Key 是 host 和 port 组成的字符串，Value 是监听该地址的
     */
    protected final Map<String, ProtocolServer> serverMap = new ConcurrentHashMap<>();

    /**
     * 服务引用的集合;
     */
    protected final Set<Invoker<?>> invokers = new ConcurrentHashSet<>();

    protected FrameworkModel frameworkModel;

    @Override
    public void setFrameworkModel(FrameworkModel frameworkModel) {
        this.frameworkModel = frameworkModel;
    }

    /**
     * 获取service key
     *
     * @param url url
     * @return service key
     */
    protected static String serviceKey(URL url) {
        int port = url.getParameter(Constants.BIND_PORT_KEY, url.getPort());
        return serviceKey(port, url.getPath(), url.getVersion(), url.getGroup());
    }

    /**
     * 获取服务的key
     *
     * @param port           port
     * @param serviceName    serviceName
     * @param serviceVersion serviceVersion
     * @param serviceGroup   serviceGroup
     * @return service key
     */
    protected static String serviceKey(int port, String serviceName, String serviceVersion, String serviceGroup) {
        return ProtocolUtils.serviceKey(port, serviceName, serviceVersion, serviceGroup);
    }

    @Override
    public List<ProtocolServer> getServers() {
        return Collections.unmodifiableList(new ArrayList<>(serverMap.values()));
    }

    protected void loadServerProperties(ProtocolServer server) {
        // read and hold config before destroy
        int serverShutdownTimeout = ConfigurationUtils.getServerShutdownTimeout(server.getUrl().getScopeModel());
        server.getAttributes().put(SHUTDOWN_WAIT_KEY, serverShutdownTimeout);
    }

    protected int getServerShutdownTimeout(ProtocolServer server) {
        return (int) server.getAttributes().getOrDefault(SHUTDOWN_WAIT_KEY, DEFAULT_SERVER_SHUTDOWN_TIMEOUT);
    }

    @Override
    public void destroy() {
        // 1.其首先会遍历 invokers 集合，销毁全部的服务引用，然后遍历全部的 exporterMap 集合，销毁发布出去的服务
        for (Invoker<?> invoker : invokers) {
            if (invoker != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Destroy reference: " + invoker.getUrl());
                    }
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
        invokers.clear();

        exporterMap.forEach((key, exporter) -> {
            if (exporter != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Unexport service: " + exporter.getInvoker().getUrl());
                    }
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        });
        exporterMap.clear();
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        return protocolBindingRefer(type, url);
    }

    /**
     * refer() 方法的实现也是委托给了 protocolBindingRefer() 这个抽象方法,由子类实现
     *
     * @param type type
     * @param url  url
     * @param <T>  t
     * @return invoker
     * @throws RpcException rpc exception
     */
    @Deprecated
    protected abstract <T> Invoker<T> protocolBindingRefer(Class<T> type, URL url) throws RpcException;

    public Map<String, Exporter<?>> getExporterMap() {
        return exporterMap;
    }

    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }
}
