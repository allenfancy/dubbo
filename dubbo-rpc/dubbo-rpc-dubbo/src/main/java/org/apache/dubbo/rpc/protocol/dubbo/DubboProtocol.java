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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.serialize.support.SerializableClassRegistry;
import org.apache.dubbo.common.serialize.support.SerializationOptimizer;
import org.apache.dubbo.common.url.component.ServiceConfigURL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.exchange.*;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.apache.dubbo.common.constants.CommonConstants.*;
import static org.apache.dubbo.remoting.Constants.*;
import static org.apache.dubbo.rpc.Constants.*;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.*;


/**
 * dubbo protocol support.
 * Dubbo 默认使用的 Protocol 实现类—— DubboProtocol
 *
 * @author allen.wu
 */
public class DubboProtocol extends AbstractProtocol {

    /**
     * 协议名称
     */
    public static final String NAME = "dubbo";

    /**
     * 默认端口
     */
    public static final int DEFAULT_PORT = 20880;

    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";

    /**
     * <host:port,Exchanger>
     * {@link Map<String, List<ReferenceCountExchangeClient>}
     */
    private final Map<String, Object> referenceClientMap = new ConcurrentHashMap<>();


    private static final Object PENDING_OBJECT = new Object();

    private final Set<String> optimizers = new ConcurrentHashSet<>();

    private final AtomicBoolean destroyed = new AtomicBoolean();

    /**
     * dubbo protocol extension .
     */
    private final ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        @Override
        public CompletableFuture<Object> reply(ExchangeChannel channel, Object message) throws RemotingException {

            if (!(message instanceof Invocation)) {
                throw new RemotingException(channel, "Unsupported request: "
                        + (message == null ? null : (message.getClass().getName() + ": " + message))
                        + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
            }
            // 1 .获取此次调用Invoker对象
            Invocation inv = (Invocation) message;
            Invoker<?> invoker = getInvoker(channel, inv);
            inv.setServiceModel(invoker.getUrl().getServiceModel());
            // switch TCCL
            if (invoker.getUrl().getServiceModel() != null) {
                Thread.currentThread().setContextClassLoader(invoker.getUrl().getServiceModel().getClassLoader());
            }
            // need to consider backward-compatibility if it's a callback
            if (Boolean.TRUE.toString().equals(inv.getObjectAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
                String methodsStr = invoker.getUrl().getParameters().get("methods");
                boolean hasMethod = false;
                if (methodsStr == null || !methodsStr.contains(",")) {
                    hasMethod = inv.getMethodName().equals(methodsStr);
                } else {
                    String[] methods = methodsStr.split(",");
                    for (String method : methods) {
                        if (inv.getMethodName().equals(method)) {
                            hasMethod = true;
                            break;
                        }
                    }
                }
                if (!hasMethod) {
                    logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                            + " not found in callback service interface ,invoke will be ignored."
                            + " please update the api interface. url is:"
                            + invoker.getUrl()) + " ,invocation is :" + inv);
                    return null;
                }
            }
            // 2.将客户端的地址记录到RpcContext中
            RpcContext.getServiceContext().setRemoteAddress(channel.getRemoteAddress());
            // 3. 执行真正的调用
            Result result = invoker.invoke(inv);
            // 4. 返回结果
            return result.thenApply(Function.identity());
        }

        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);

            } else {
                super.received(channel, message);
            }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
            invoke(channel, ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isDebugEnabled()) {
                logger.debug("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, ON_DISCONNECT_KEY);
        }

        private void invoke(Channel channel, String methodKey) {
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {
                    if (Boolean.TRUE.toString().equals(invocation.getAttachment(STUB_EVENT_KEY))) {
                        tryToGetStubService(channel, invocation);
                    }
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        private void tryToGetStubService(Channel channel, Invocation invocation) throws RemotingException {
            try {
                Invoker<?> invoker = getInvoker(channel, invocation);
            } catch (RemotingException e) {
                String serviceKey = serviceKey(
                        0,
                        (String) invocation.getObjectAttachments().get(PATH_KEY),
                        (String) invocation.getObjectAttachments().get(VERSION_KEY),
                        (String) invocation.getObjectAttachments().get(GROUP_KEY)
                );
                throw new RemotingException(channel, "The stub service[" + serviceKey + "] is not found, it may not be exported yet");
            }
        }

        /**
         * FIXME channel.getUrl() always binds to a fixed service, and this service is random.
         * we can choose to use a common service to carry onConnect event if there's no easy way to get the specific
         * service this connection is binding to.
         * @param channel channel
         * @param url url
         * @param methodKey methodKey
         * @return invocation
         */
        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }

            RpcInvocation invocation = new RpcInvocation(url.getServiceModel(), method, url.getParameter(INTERFACE_KEY), "", new Class<?>[0], new Object[0]);
            invocation.setAttachment(PATH_KEY, url.getPath());
            invocation.setAttachment(GROUP_KEY, url.getGroup());
            invocation.setAttachment(INTERFACE_KEY, url.getParameter(INTERFACE_KEY));
            invocation.setAttachment(VERSION_KEY, url.getVersion());
            if (url.getParameter(STUB_EVENT_KEY, false)) {
                invocation.setAttachment(STUB_EVENT_KEY, Boolean.TRUE.toString());
            }

            return invocation;
        }
    };

    public DubboProtocol() {
    }

    /**
     * @deprecated Use {@link DubboProtocol#getDubboProtocol(ScopeModel)} instead
     */
    @Deprecated
    public static DubboProtocol getDubboProtocol() {
        return (DubboProtocol) ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME, false);
    }

    public static DubboProtocol getDubboProtocol(ScopeModel scopeModel) {
        return (DubboProtocol) scopeModel.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME, false);
    }

    @Override
    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }

    private boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(channel.getUrl().getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    /**
     * 1.根据 Invocation 携带的信息构造 ServiceKey，
     * 2.然后从 exporterMap 集合中查找对应的 DubboExporter 对象，并从中获取底层的 Invoker 对象返回
     *
     * @param channel channel
     * @param inv     inv
     * @return invoker
     * @throws RemotingException remoting exception
     */
    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke;
        boolean isStubServiceInvoke;
        int port = channel.getLocalAddress().getPort();
        String path = (String) inv.getObjectAttachments().get(PATH_KEY);

        //if it's stub service on client side(after enable stubevent, usually is set up onconnect or ondisconnect method)
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getObjectAttachments().get(STUB_EVENT_KEY));
        if (isStubServiceInvoke) {
            //when a stub service export to local, it usually can't be exposed to port
            port = 0;
        }

        // if it's callback service on client side
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if (isCallBackServiceInvoke) {
            path += "." + inv.getObjectAttachments().get(CALLBACK_SERVICE_KEY);
            inv.getObjectAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }

        String serviceKey = serviceKey(
                port,
                path,
                (String) inv.getObjectAttachments().get(VERSION_KEY),
                (String) inv.getObjectAttachments().get(GROUP_KEY)
        );
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);

        if (exporter == null) {
            throw new RemotingException(channel, "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " +
                    ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() + ", message:" + getInvocationWithoutData(inv));
        }

        return exporter.getInvoker();
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    /**
     * dubbo 协议 服务暴露具体实现逻辑
     *
     * @param invoker Service invoker
     * @param <T>     T
     * @return Exporter
     * @throws RpcException rpc exception
     */
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {

        // 1. 判断是否已经销毁
        checkDestroyed();
        // 2. 获取URL
        URL url = invoker.getUrl();
        // 3. export service key
        String key = serviceKey(url);
        // 4. new DubboExporter;然后记录到exporterMap集合中
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);

        // 5. export a stub service for dispatching event
        boolean isStubSupportEvent = url.getParameter(STUB_EVENT_KEY, DEFAULT_STUB_EVENT);
        boolean isCallbackService = url.getParameter(IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackService) {
            String stubServiceMethods = url.getParameter(STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }

            }
        }

        // 6. 启动ProtocolServer
        openServer(url);
        // 7. 进行序列化的优化处理
        optimizeSerialization(url);
        // 8. 返回exporter.
        return exporter;
    }

    private void openServer(URL url) {
        // 1. 判断是否已经启动
        checkDestroyed();
        // 2. find server.
        String key = url.getAddress();
        // 3. client can export a service which only for server to invoke；判断是否是服务端。默认值为true
        boolean isServer = url.getParameter(IS_SERVER_KEY, true);
        if (!isServer) {
            return;
        }
        // 4. 从serverMap中获取对应的server
        ProtocolServer server = serverMap.get(key);
        // 5. 判断是否为空
        if (server == null) {
            // 5.1 double check lock
            synchronized (this) {
                server = serverMap.get(key);
                if (server == null) {
                    serverMap.put(key, createServer(url));
                } else {
                    // 5.3 不为空，重置server的url
                    server.reset(url);
                }
            }
        } else {
            // server supports reset, use together with override
            server.reset(url);
        }
    }

    private void checkDestroyed() {
        if (destroyed.get()) {
            throw new IllegalStateException(getClass().getSimpleName() + " is destroyed");
        }
    }

    /**
     * 创建ProtocolServer根据URL
     *
     * @param url url
     * @return ProtocolServer
     */
    private ProtocolServer createServer(URL url) {
        // 1. 重构url
        url = URLBuilder.from(url)
                // send readonly event when server closes, it's enabled by default。 ReadOnly请求是否阻塞等待
                .addParameterIfAbsent(CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString())
                // enable heartbeat by default 默认的心跳时间间隔为 60 秒
                .addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT))
                // add codec ,use DubboCountCodec by default；Codec2扩展实现
                .addParameter(CODEC_KEY, DubboCodec.NAME)
                .build();
        // 2. 获取server key，默认netty
        String str = url.getParameter(SERVER_KEY, DEFAULT_REMOTING_SERVER);

        // 检测SERVER_KEY参数指定的Transporter扩展实现是否合法
        if (StringUtils.isNotEmpty(str) && !url.getOrDefaultFrameworkModel().getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);
        }

        // 3. 通过Exchangers门面类，创建ExchangeServer对象
        ExchangeServer server;
        try {
            // 3.1 获取server的实现类
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }
        // 4. 获取client key
        str = url.getParameter(CLIENT_KEY);
        if (StringUtils.isNotEmpty(str)) {
            Set<String> supportedTypes = url.getOrDefaultFrameworkModel().getExtensionLoader(Transporter.class).getSupportedExtensions();
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }
        // 5. new dubbo protocol server
        DubboProtocolServer protocolServer = new DubboProtocolServer(server);
        // 6. load server properties
        loadServerProperties(protocolServer);
        return protocolServer;
    }

    private void optimizeSerialization(URL url) throws RpcException {
        String className = url.getParameter(OPTIMIZER_KEY, "");
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {
                throw new RpcException("The serialization optimizer " + className + " isn't an instance of " + SerializationOptimizer.class.getName());
            }

            SerializationOptimizer optimizer = (SerializationOptimizer) clazz.newInstance();

            if (optimizer.getSerializableClasses() == null) {
                return;
            }

            for (Class c : optimizer.getSerializableClasses()) {
                SerializableClassRegistry.registerClass(c);
            }

            optimizers.add(className);

        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);

        } catch (InstantiationException | IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);

        }
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        checkDestroyed();
        return protocolBindingRefer(type, url);
    }

    @Override
    public <T> Invoker<T> protocolBindingRefer(Class<T> serviceType, URL url) throws RpcException {
        // 1.检测是否销毁
        checkDestroyed();
        // 2.进行序列化优化，注册需要优化的类
        optimizeSerialization(url);

        // 3. 创建dubbo invoker 对象
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);
        // 4. 将invoker添加到invokers中
        invokers.add(invoker);
        // 5. 返回
        return invoker;
    }

    /**
     * 创建了底层发送请求和接收响应的 Client 集合；
     * 其核心分为了两个部分，一个是针对共享连接的处理，另一个是针对独享连接的处理
     *
     * @param url url
     * @return clients
     */
    private ExchangeClient[] getClients(URL url) {
        // 1. 是否使用共享连接
        boolean useShareConnect = false;
        // 2. CONNECTIONS_KEY参数值决定了后续建立连接的数量;默认为0
        int connections = url.getParameter(CONNECTIONS_KEY, 0);
        List<ReferenceCountExchangeClient> shareClients = null;
        // if not configured, connection is shared, otherwise, one connection for one service
        // 3. 如果没有连接数的相关配置，默认使用共享连接的方式
        if (connections == 0) {
            useShareConnect = true;
            /*
             * The xml configuration should have a higher priority than properties.
             */
            // 3.1 shareconnections共享连接配置。默认为空
            String shareConnectionsStr = url.getParameter(SHARE_CONNECTIONS_KEY, (String) null);
            // 3.2 没有任何配置，共享连接数量为1个
            connections = Integer.parseInt(StringUtils.isBlank(shareConnectionsStr) ? ConfigurationUtils.getProperty(url.getOrDefaultApplicationModel(), SHARE_CONNECTIONS_KEY,
                    DEFAULT_SHARE_CONNECTIONS) : shareConnectionsStr);
            // 3.3 创建共享连接的客户端
            shareClients = getSharedClient(url, connections);
        }

        // 4. 初始化ExchangeClient数组
        ExchangeClient[] clients = new ExchangeClient[connections];
        for (int i = 0; i < clients.length; i++) {
            // 4.1 如果是共享连接，则从shareClients中获取
            if (useShareConnect) {
                clients[i] = shareClients.get(i);
            } else {
                //4.2 如果不是共享连接，则创建独享连接
                clients[i] = initClient(url);
            }
        }
        return clients;
    }

    /**
     * Get shared connection
     * 获取共享连接。
     * 它首先从 referenceClientMap 缓存（Map<String, List<ReferenceCountExchangeClient>> 类型）中查询 Key（host 和 port 拼接成的字符串）对应的共享 Client 集合，
     * 如果查找到的 Client 集合全部可用，则直接使用这些缓存的 Client，否则要创建新的 Client 来补充替换缓存中不可用的 Client
     *
     * @param url        url
     * @param connectNum connectNum must be greater than or equal to 1
     */
    @SuppressWarnings("unchecked")
    private List<ReferenceCountExchangeClient> getSharedClient(URL url, int connectNum) {

        // 1. 获取对端的地址(host:port)
        String key = url.getAddress();
        // 2. 从referenceClientMap集合中，获取与该地址连接的ReferenceCountExchangeClient集合
        Object clients = referenceClientMap.get(key);
        if (clients instanceof List) {
            List<ReferenceCountExchangeClient> typedClients = (List<ReferenceCountExchangeClient>) clients;
            //2.1 checkClientCanUse()方法中会检测clients集合中的客户端是否全部可用
            if (checkClientCanUse(typedClients)) {
                // 2.2  客户端全部可用时。直接返回
                batchClientRefIncr(typedClients);
                return typedClients;
            }
        }

        // 3. 进行创建逻辑
        List<ReferenceCountExchangeClient> typedClients = null;

        synchronized (referenceClientMap) {
            for (; ; ) {
                // 3.1. 从referenceClientMap中获取Clients
                clients = referenceClientMap.get(key);
                // 3.2 如果是列表类型
                if (clients instanceof List) {
                    typedClients = (List<ReferenceCountExchangeClient>) clients;
                    if (checkClientCanUse(typedClients)) {
                        batchClientRefIncr(typedClients);
                        return typedClients;
                    } else {
                        referenceClientMap.put(key, PENDING_OBJECT);
                        break;
                    }
                } else if (clients == PENDING_OBJECT) {
                    try {
                        referenceClientMap.wait();
                    } catch (InterruptedException ignored) {
                    }
                } else {
                    referenceClientMap.put(key, PENDING_OBJECT);
                    break;
                }
            }
        }

        try {
            // connectNum must be greater than or equal to 1
            connectNum = Math.max(connectNum, 1);

            // If the clients is empty, then the first initialization is
            if (CollectionUtils.isEmpty(typedClients)) {
                typedClients = buildReferenceCountExchangeClientList(url, connectNum);
            } else {
                for (int i = 0; i < typedClients.size(); i++) {
                    ReferenceCountExchangeClient referenceCountExchangeClient = typedClients.get(i);
                    // If there is a client in the list that is no longer available, create a new one to replace him.
                    if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                        typedClients.set(i, buildReferenceCountExchangeClient(url));
                        continue;
                    }
                    referenceCountExchangeClient.incrementAndGetCount();
                }
            }
        } finally {
            synchronized (referenceClientMap) {
                if (typedClients == null) {
                    referenceClientMap.remove(key);
                } else {
                    referenceClientMap.put(key, typedClients);
                }
                referenceClientMap.notifyAll();
            }
        }
        return typedClients;

    }

    /**
     * Check if the client list is all available
     * 检测client list是否全部可用
     *
     * @param referenceCountExchangeClients referenceCountExchangeClients
     * @return true-available，false-unavailable
     */
    private boolean checkClientCanUse(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return false;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            // As long as one client is not available, you need to replace the unavailable client with the available one.
            if (referenceCountExchangeClient == null || referenceCountExchangeClient.getCount() <= 0 || referenceCountExchangeClient.isClosed()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Increase the reference Count if we create new invoker shares same connection, the connection will be closed without any reference.
     * 增加引用计数，如果我们创建新的调用程序共享相同的连接，连接将在没有任何引用的情况下关闭。
     *
     * @param referenceCountExchangeClients referenceCountExchangeClients
     */
    private void batchClientRefIncr(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            if (referenceCountExchangeClient != null) {
                referenceCountExchangeClient.incrementAndGetCount();
            }
        }
    }

    /**
     * Bulk build client
     *
     * @param url        url
     * @param connectNum connectNum
     * @return list
     */
    private List<ReferenceCountExchangeClient> buildReferenceCountExchangeClientList(URL url, int connectNum) {
        List<ReferenceCountExchangeClient> clients = new ArrayList<>();

        for (int i = 0; i < connectNum; i++) {
            clients.add(buildReferenceCountExchangeClient(url));
        }

        return clients;
    }

    /**
     * Build a single client
     *
     * @param url url
     * @return ReferenceCountExchangeClient
     */
    private ReferenceCountExchangeClient buildReferenceCountExchangeClient(URL url) {

        // 1. 初始化ExchangeClient
        ExchangeClient exchangeClient = initClient(url);
        // 2. 初始化ReferenceCountExchangeClient
        ReferenceCountExchangeClient client = new ReferenceCountExchangeClient(exchangeClient, DubboCodec.NAME);
        // 3. read configs
        int shutdownTimeout = ConfigurationUtils.getServerShutdownTimeout(url.getScopeModel());
        client.setShutdownWaitTime(shutdownTimeout);
        // 4. 返回client
        return client;
    }

    /**
     * Create new connection
     * 创建一个新的连接
     *
     * @param url url
     */
    private ExchangeClient initClient(URL url) {

        /*
         * Instance of url is InstanceAddressURL, so addParameter actually adds parameters into ServiceInstance,
         * which means params are shared among different services. Since client is shared among services this is currently not a problem.
         */
        // 1. 从url中获取client，默认是server的默认client
        String str = url.getParameter(CLIENT_KEY, url.getParameter(SERVER_KEY, DEFAULT_REMOTING_CLIENT));

        // BIO is not allowed since it has severe performance issue.BIO是不允许的，因为它有严重的性能问题。
        if (StringUtils.isNotEmpty(str) && !url.getOrDefaultFrameworkModel().getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " + StringUtils.join(url.getOrDefaultFrameworkModel().getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }
        // 2. ExchangeClient 实例
        ExchangeClient client;
        try {
            // 2.1 Replace InstanceAddressURL with ServiceConfigURL. 将InstanceAddressURL替换为ServiceConfigURL。
            url = new ServiceConfigURL(DubboCodec.NAME, url.getUsername(), url.getPassword(), url.getHost(), url.getPort(), url.getPath(), url.getAllParameters());
            url = url.addParameter(CODEC_KEY, DubboCodec.NAME);
            // enable heartbeat by default
            url = url.addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT));

            // 2.2 懒加载
            if (url.getParameter(LAZY_CONNECT_KEY, false)) {
                client = new LazyConnectExchangeClient(url, requestHandler);
            } else {
                //2.3 直接创建
                client = Exchangers.connect(url, requestHandler);
            }

        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }

        return client;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Destroying protocol [" + this.getClass().getSimpleName() + "] ...");
        }
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ProtocolServer protocolServer = serverMap.remove(key);

            if (protocolServer == null) {
                continue;
            }
            RemotingServer server = protocolServer.getRemotingServer();
            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Closing dubbo server: " + server.getLocalAddress());
                }
                // 在close()方法中，发送ReadOnly请求、阻塞指定时间、关闭底层的定时任务、关闭相关线程池，最终，会断开所有连接，关闭Server。
                server.close(getServerShutdownTimeout(protocolServer));
            } catch (Throwable t) {
                logger.warn("Close dubbo server [" + server.getLocalAddress() + "] failed: " + t.getMessage(), t);
            }
        }
        serverMap.clear();

        // 遍历referenceClientMap，对共享连接进行处理
        for (String key : new ArrayList<>(referenceClientMap.keySet())) {
            Object clients = referenceClientMap.remove(key);
            if (clients instanceof List) {
                List<ReferenceCountExchangeClient> typedClients = (List<ReferenceCountExchangeClient>) clients;

                if (CollectionUtils.isEmpty(typedClients)) {
                    continue;
                }

                for (ReferenceCountExchangeClient client : typedClients) {
                    closeReferenceCountExchangeClient(client);
                }
            }
        }
        referenceClientMap.clear();

        super.destroy();
    }

    /**
     * close ReferenceCountExchangeClient
     *
     * @param client client
     */
    private void closeReferenceCountExchangeClient(ReferenceCountExchangeClient client) {
        if (client == null) {
            return;
        }

        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
            }

            client.close(client.getShutdownWaitTime());

            // TODO
            /*
             * At this time, ReferenceCountExchangeClient#client has been replaced with LazyConnectExchangeClient.
             * Do you need to call client.close again to ensure that LazyConnectExchangeClient is also closed?
             */

        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    /**
     * only log body in debugger mode for size & security consideration.
     *
     * @param invocation invocation
     * @return Invocation
     */
    private Invocation getInvocationWithoutData(Invocation invocation) {
        if (logger.isDebugEnabled()) {
            return invocation;
        }
        if (invocation instanceof RpcInvocation) {
            RpcInvocation rpcInvocation = (RpcInvocation) invocation;
            rpcInvocation.setArguments(null);
            return rpcInvocation;
        }
        return invocation;
    }
}
