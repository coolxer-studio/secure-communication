package com.abc.sc;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(value = ScServiceProperties.class)
@ConditionalOnClass(ScServiceFilter.class)
public class ScServiceAutoConfiguration {

    @Autowired
    private ScServiceProperties scServiceProperties;
    @Bean
    @ConditionalOnMissingBean(ScServiceFilter.class)
    public ScServiceFilter scServiceFilter() {
        return new ScServiceFilter(scServiceProperties);
    }
}
