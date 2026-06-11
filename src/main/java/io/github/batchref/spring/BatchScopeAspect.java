package io.github.batchref.spring;

import io.github.batchref.autoconfigure.BatchRefProperties;
import io.github.batchref.BatchContext;
import io.github.batchref.BatchContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;

@Aspect
public class BatchScopeAspect implements Ordered {

    private final BatchRefProperties properties;

    public BatchScopeAspect(BatchRefProperties properties) {
        this.properties = properties;
    }

    @Around("@within(io.github.batchref.annotation.BatchScope) || @annotation(io.github.batchref.annotation.BatchScope)")
    public Object aroundBatchScope(ProceedingJoinPoint joinPoint) throws Throwable {
        if (BatchContextHolder.exists()) {
            return joinPoint.proceed();
        }

        BatchContext context = BatchContextHolder.push();
        try {
            Object result = joinPoint.proceed();
            if (properties.isAutoFlush()) {
                context.flush();
            }
            return result;
        } catch (Throwable ex) {
            if (properties.isFlushOnException()) {
                context.flush();
            }
            throw ex;
        } finally {
            BatchContextHolder.pop(context);
        }
    }

    @Override
    public int getOrder() {
        return properties.getAspectOrder();
    }
}
