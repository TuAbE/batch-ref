package io.github.batchref;

import io.github.batchref.autoconfigure.BatchRefAutoConfiguration;
import io.github.batchref.spring.BatchScopeAspect;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class BatchRefAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BatchRefAutoConfiguration.class));

    @Test
    void createsBatchScopeAspectByDefault() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(BatchScopeAspect.class));
    }

    @Test
    void canDisableAutoConfiguration() {
        contextRunner.withPropertyValues("batch-ref.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(BatchScopeAspect.class));
    }
}
