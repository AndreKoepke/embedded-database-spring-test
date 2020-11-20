/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.zonky.test.db.config;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.MultiValueMap;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Order(Ordered.LOWEST_PRECEDENCE)
class OnBeanCondition implements ConfigurationCondition {

    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        List<String> candidates = getCandidates(metadata, ConditionalOnMissingBean.class);

        for (String beanName : candidates) {
            if (beanFactory.containsBean(beanName)) {
                return false;
            }
        }

        return true;
    }

    private List<String> getCandidates(AnnotatedTypeMetadata metadata, Class<?> annotationType) {
        MultiValueMap<String, Object> attributes = metadata.getAllAnnotationAttributes(annotationType.getName(), true);
        if (attributes == null || attributes.get("name") == null) {
            throw new IllegalStateException("@ConditionalOnMissingBean did not specify a bean name");
        }
        return attributes.get("name").stream().flatMap(o -> Arrays.stream((String[]) o)).collect(Collectors.toList());
    }
}
