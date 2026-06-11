package io.github.batchref.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.Ordered;

@ConfigurationProperties(prefix = "batch-ref")
public class BatchRefProperties {

    /**
     * Whether BatchRef auto-configuration is enabled.
     */
    private boolean enabled = true;

    /**
     * Whether @BatchScope should flush pending refs before the annotated method returns.
     */
    private boolean autoFlush = true;

    /**
     * Whether @BatchScope should flush pending refs when the annotated method throws.
     */
    private boolean flushOnException = false;

    /**
     * Aspect order for @BatchScope.
     */
    private int aspectOrder = Ordered.LOWEST_PRECEDENCE - 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAutoFlush() {
        return autoFlush;
    }

    public void setAutoFlush(boolean autoFlush) {
        this.autoFlush = autoFlush;
    }

    public boolean isFlushOnException() {
        return flushOnException;
    }

    public void setFlushOnException(boolean flushOnException) {
        this.flushOnException = flushOnException;
    }

    public int getAspectOrder() {
        return aspectOrder;
    }

    public void setAspectOrder(int aspectOrder) {
        this.aspectOrder = aspectOrder;
    }
}
