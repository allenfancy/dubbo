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
package org.apache.dubbo.registry;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.extension.ExtensionScope.APPLICATION;

/**
 * RegistryFactory. (SPI, Singleton, ThreadSafe)
 * Registry 的工厂接口，负责创建 Registry 对象
 *
 * @author qinjian
 * @see org.apache.dubbo.registry.support.AbstractRegistryFactory
 */
@SPI(scope = APPLICATION)
public interface RegistryFactory {

    /**
     * Connect to the registry
     * 连接注册中心
     * <p>
     * Connecting the registry needs to support the contract: <br>
     * 连接注册中心需要支持契约:<br>
     * 1. When the check=false is set, the connection is not checked, otherwise the exception is thrown when disconnection <br>
     *  1. 当设置check=false时，不检查连接，否则断开连接时会引发异常<br>
     * 2. Support username:password authority authentication on URL.<br>
     *  2. 支持用户名:密码授权URL认证<br>
     * 3. Support the backup=10.20.153.10 candidate registry cluster address.<br>
     *  3.支持backup=10.20.153.10候选注册表集群地址。<br>
     * 4. Support file=registry.cache local disk file cache.<br>
     *  4.支持文件=注册表。缓存本地磁盘文件缓存。<br>
     * 5. Support the timeout=1000 request timeout setting.<br>
     *  5.支持timeout=1000请求超时设置
     * 6. Support session=60000 session timeout or expiration settings.<br>
     *  6.支持session=60000会话超时或过期设置<br>
     *
     * @param url Registry address, is not allowed to be empty
     * @return Registry reference, never return empty value
     */
    @Adaptive({PROTOCOL_KEY})
    Registry getRegistry(URL url);

}
