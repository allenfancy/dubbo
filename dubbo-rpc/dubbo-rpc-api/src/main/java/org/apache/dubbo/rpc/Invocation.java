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

import org.apache.dubbo.common.Experimental;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ScopeModelUtil;
import org.apache.dubbo.rpc.model.ServiceModel;

import java.util.Map;
import java.util.stream.Stream;

/**
 * Invocation. (API, Prototype, NonThreadSafe)
 * <p>
 * 抽象了一次 RPC 调用的目标服务和方法信息、相关参数信息、具体的参数值以及一些附加信息 *
 * @author allen.wu
 * @serial Don't change the class name and package name.
 * @see org.apache.dubbo.rpc.Invoker#invoke(Invocation)
 * @see org.apache.dubbo.rpc.RpcInvocation
 */
public interface Invocation {

    /**
     * 获取模块信息
     *
     * @return string
     */
    String getTargetServiceUniqueName();

    /**
     * 获取 protocol service key.
     *
     * @return string
     */
    String getProtocolServiceKey();

    /**
     * get method name.
     * 获取method name
     *
     * @return method name.
     */
    String getMethodName();


    /**
     * get the interface name
     * 获取接口名称
     *
     * @return service name
     */
    String getServiceName();

    /**
     * get parameter types.
     * 获取参数类型
     *
     * @return parameter types.
     */
    Class<?>[] getParameterTypes();

    /**
     * get parameter's signature, string representation of parameter types.
     * 获取参数类型的签名
     *
     * @return parameter's signature
     */
    default String[] getCompatibleParamSignatures() {
        return Stream.of(getParameterTypes())
                .map(Class::getName)
                .toArray(String[]::new);
    }

    /**
     * get arguments.
     * 获取参数
     *
     * @return arguments.
     */
    Object[] getArguments();

    /**
     * get attachments.
     * 获取附件
     *
     * @return attachments.
     */
    Map<String, String> getAttachments();

    /**
     * 获取模块信息
     *
     * @return map
     */
    @Experimental("Experiment api for supporting Object transmission")
    Map<String, Object> getObjectAttachments();

    /**
     * 设置附件
     *
     * @param key   key
     * @param value value
     */
    void setAttachment(String key, String value);

    /**
     * 设置附件
     *
     * @param key   key
     * @param value value
     */
    @Experimental("Experiment api for supporting Object transmission")
    void setAttachment(String key, Object value);

    /**
     * 设置模块信息
     *
     * @param key   key
     * @param value value
     */
    @Experimental("Experiment api for supporting Object transmission")
    void setObjectAttachment(String key, Object value);

    /**
     * 设置附件
     *
     * @param key   key
     * @param value value
     */
    void setAttachmentIfAbsent(String key, String value);

    /**
     * 设置附件
     *
     * @param key   key
     * @param value value
     */
    @Experimental("Experiment api for supporting Object transmission")
    void setAttachmentIfAbsent(String key, Object value);

    /**
     * 设置模块信息
     *
     * @param key   key
     * @param value value
     */
    @Experimental("Experiment api for supporting Object transmission")
    void setObjectAttachmentIfAbsent(String key, Object value);

    /**
     * get attachment by key.
     * 根据key获取附件
     *
     * @return attachment value.
     */
    String getAttachment(String key);

    /**
     * get attachment by key.
     *
     * @param key key
     * @return Object
     */
    @Experimental("Experiment api for supporting Object transmission")
    Object getObjectAttachment(String key);

    /**
     * get attachment by key.
     *
     * @param key key
     * @return object
     */
    @Experimental("Experiment api for supporting Object transmission")
    default Object getObjectAttachmentWithoutConvert(String key) {
        return getObjectAttachment(key);
    }

    /**
     * get attachment by key with default value.
     *
     * @param key          key
     * @param defaultValue default value
     * @return attachment value.
     */
    String getAttachment(String key, String defaultValue);

    /**
     * get attachment by key with default value.
     *
     * @param key          key
     * @param defaultValue defaultValue
     * @return object
     */
    @Experimental("Experiment api for supporting Object transmission")
    Object getObjectAttachment(String key, Object defaultValue);

    /**
     * get the invoker in current context.
     * 获取当前上下文的invoker
     *
     * @return invoker.
     */
    Invoker<?> getInvoker();

    /**
     * get the invoker in current context.
     *
     * @param serviceModel serviceModel
     */
    void setServiceModel(ServiceModel serviceModel);

    /**
     * get the invoker in current context.
     *
     * @return ServiceModel
     */
    ServiceModel getServiceModel();

    /**
     * 获取模型
     *
     * @return ModuleModel
     */
    default ModuleModel getModuleModel() {
        return ScopeModelUtil.getModuleModel(getServiceModel() == null ? null : getServiceModel().getModuleModel());
    }

    /**
     * 放入调用上下文
     *
     * @param key   key
     * @param value value
     * @return Object
     */
    Object put(Object key, Object value);

    /**
     * 获取对象
     *
     * @param key key
     * @return Object
     */
    Object get(Object key);

    /**
     * 获取属性
     *
     * @return Map
     */
    Map<Object, Object> getAttributes();
}
