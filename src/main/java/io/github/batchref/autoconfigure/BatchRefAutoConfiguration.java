package io.github.batchref.autoconfigure;

import io.github.batchref.BatchRef;
import io.github.batchref.spring.BatchScopeAspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass({BatchRef.class, ProceedingJoinPoint.class})
@ConditionalOnProperty(prefix = "batch-ref", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(BatchRefProperties.class)
public class BatchRefAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BatchScopeAspect batchScopeAspect(BatchRefProperties properties) {
        return new BatchScopeAspect(properties);
    }
}
