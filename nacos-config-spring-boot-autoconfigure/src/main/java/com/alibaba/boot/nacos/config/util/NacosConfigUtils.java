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
package com.alibaba.boot.nacos.config.util;

import com.alibaba.boot.nacos.config.properties.NacosConfigProperties;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.ConfigType;
import com.alibaba.nacos.spring.core.env.NacosPropertySource;
import com.alibaba.nacos.spring.core.env.NacosPropertySourcePostProcessor;
import com.alibaba.nacos.spring.util.NacosUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import static com.alibaba.nacos.spring.util.NacosUtils.buildDefaultPropertySourceName;

/**
 * @author <a href="mailto:liaochunyhm@live.com">liaochuntao</a>
 * @since
 */
public class NacosConfigUtils {

    private final Logger logger = LoggerFactory.getLogger(NacosConfigUtils.class);

    private final NacosConfigProperties nacosConfigProperties;
    private final ConfigurableEnvironment environment;
    private Function<Properties, ConfigService> builder;
    private List<DeferNacosPropertySource> nacosPropertySources = new LinkedList<>();

    public NacosConfigUtils(NacosConfigProperties nacosConfigProperties, ConfigurableEnvironment environment, Function<Properties, ConfigService> builder) {
        this.nacosConfigProperties = nacosConfigProperties;
        this.environment = environment;
        this.builder = builder;
    }

    public void loadConfig() {
        Properties globalProperties = buildGlobalNacosProperties();
        MutablePropertySources mutablePropertySources = environment.getPropertySources();
        List<NacosPropertySource> sources = reqGlobalNacosConfig(globalProperties, nacosConfigProperties.getType());
        for (NacosConfigProperties.Config config : nacosConfigProperties.getExtConfig()) {
            List<NacosPropertySource> elements = reqSubNacosConfig(config, globalProperties, config.getType());
            sources.addAll(sources.size(), elements);
        }
        CompositePropertySource compositePropertySource = new CompositePropertySource("nacosCompositePropertySource");
        for (NacosPropertySource propertySource : sources) {
            compositePropertySource.addPropertySource(propertySource);
        }
        mutablePropertySources.addLast(compositePropertySource);
    }

    private Properties buildGlobalNacosProperties() {
        return NacosPropertiesBuilder.buildNacosProperties(nacosConfigProperties.getServerAddr(), nacosConfigProperties.getNamespace(),
                nacosConfigProperties.getEndpoint(), nacosConfigProperties.getSecretKey(), nacosConfigProperties.getAccessKey(), nacosConfigProperties.getRamRoleName(),
                nacosConfigProperties.getConfigLongPollTimeout(), nacosConfigProperties.getConfigRetryTime(),
                nacosConfigProperties.getMaxRetry(), nacosConfigProperties.isEnableRemoteSyncConfig());
    }

    private Properties buildSubNacosProperties(Properties globalProperties, NacosConfigProperties.Config config) {
        return getProperties(globalProperties, config);
    }

    private static Properties getProperties(Properties globalProperties, NacosConfigProperties.Config config) {
        if (StringUtils.isEmpty(config.getServerAddr())) {
            return globalProperties;
        }
        Properties sub = NacosPropertiesBuilder.buildNacosProperties(config.getServerAddr(), config.getNamespace(),
                config.getEndpoint(), config.getSecretKey(), config.getAccessKey(), config.getRamRoleName(), config.getConfigLongPollTimeout(),
                config.getConfigRetryTime(), config.getMaxRetry(), config.isEnableRemoteSyncConfig());
        NacosPropertiesBuilder.merge(sub, globalProperties);
        return sub;
    }

    private List<NacosPropertySource> reqGlobalNacosConfig(Properties globalProperties, ConfigType type) {
        List<String> dataIds = new ArrayList<>();
        // Loads all dataid information into the list in the list
        if (StringUtils.isEmpty(nacosConfigProperties.getDataId())) {
            dataIds.addAll(Arrays.asList(nacosConfigProperties.getDataIds()));
        } else {
            dataIds.add(nacosConfigProperties.getDataId());
        }
        final String groupName = nacosConfigProperties.getGroup();
        List<NacosPropertySource> results = Arrays.asList(reqNacosConfig(globalProperties, dataIds.toArray(new String[0]), groupName, type));
        for (NacosPropertySource propertySource : results) {
            DeferNacosPropertySource defer = new DeferNacosPropertySource(propertySource, globalProperties, environment);
            nacosPropertySources.add(defer);
        }
        return results;
    }

    private List<NacosPropertySource> reqSubNacosConfig(NacosConfigProperties.Config config, Properties globalProperties, ConfigType type) {
        Properties subConfigProperties = buildSubNacosProperties(globalProperties, config);
        ArrayList<String> dataIds = new ArrayList<>();
        if (StringUtils.isEmpty(config.getDataId())) {
            dataIds.addAll(Arrays.asList(config.getDataIds()));
        } else {
            dataIds.add(config.getDataId());
        }
        final String groupName = config.getGroup();
        List<NacosPropertySource> results = Arrays.asList(reqNacosConfig(subConfigProperties, dataIds.toArray(new String[0]), groupName, type));
        for (NacosPropertySource propertySource : results) {
            DeferNacosPropertySource defer = new DeferNacosPropertySource(propertySource, subConfigProperties, environment);
            nacosPropertySources.add(defer);
        }
        return results;
    }

    private NacosPropertySource[] reqNacosConfig(Properties configProperties, String[] dataIds, String groupId, ConfigType type) {
        NacosPropertySource[] propertySources = new NacosPropertySource[dataIds.length];
        for (int i = 0; i < dataIds.length; i ++) {
            final String dataId = dataIds[i];
            String config = NacosUtils.getContent(builder.apply(configProperties), dataId, groupId);
            NacosPropertySource nacosPropertySource = new NacosPropertySource(dataId, groupId,
                    buildDefaultPropertySourceName(dataId, groupId, configProperties), config, type.getType());
            nacosPropertySource.setDataId(dataId);
            nacosPropertySource.setType(type.getType());
            nacosPropertySource.setGroupId(groupId);
            logger.info("load config from nacos, data-id is : {}, group is : {}", nacosPropertySource.getDataId(), nacosPropertySource.getGroupId());
            propertySources[i] = nacosPropertySource;
        }
        return propertySources;
    }

    public void addListenerIfAutoRefreshed() {
        addListenerIfAutoRefreshed(nacosPropertySources);
    }

    public void addListenerIfAutoRefreshed(final List<DeferNacosPropertySource> deferNacosPropertySources) {
        for (DeferNacosPropertySource deferNacosPropertySource : deferNacosPropertySources) {
            NacosPropertySourcePostProcessor.addListenerIfAutoRefreshed(deferNacosPropertySource.getNacosPropertySource(),
                    deferNacosPropertySource.getProperties(), deferNacosPropertySource.getEnvironment());
        }
    }

    public List<DeferNacosPropertySource> getNacosPropertySources() {
        return nacosPropertySources;
    }

    // Delay Nacos configuration data source object, used for log level of loading time,
    // the cache configuration, wait for after the completion of the Spring Context
    // created in the release

    public static class DeferNacosPropertySource {

        private final NacosPropertySource nacosPropertySource;
        private final ConfigurableEnvironment environment;
        private final Properties properties;

        DeferNacosPropertySource(NacosPropertySource nacosPropertySource, Properties properties, ConfigurableEnvironment environment) {
            this.nacosPropertySource = nacosPropertySource;
            this.properties = properties;
            this.environment = environment;
        }

        NacosPropertySource getNacosPropertySource() {
            return nacosPropertySource;
        }

        ConfigurableEnvironment getEnvironment() {
            return environment;
        }

        public Properties getProperties() {
            return properties;
        }
    }
}
