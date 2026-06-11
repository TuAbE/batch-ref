package io.github.batchref.spring;

import io.github.batchref.BatchQuery;
import io.github.batchref.BatchQueryCapture;
import io.github.batchref.BatchQueryMethodQueries;
import io.github.batchref.BatchRefException;
import io.github.batchref.annotation.BatchQueryMethod;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

@Aspect
public class BatchQueryMethodAspect {

    @Around("@annotation(io.github.batchref.annotation.BatchQueryMethod)")
    public Object aroundBatchQueryMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!BatchQueryCapture.isCapturing()) {
            return joinPoint.proceed();
        }

        Object target = joinPoint.getTarget();
        Class<?> targetClass = ClassUtils.getUserClass(target);
        Method method = resolveMethod(joinPoint, targetClass);
        BatchQueryMethod annotation = resolveAnnotation(joinPoint, method);
        if (annotation == null) {
            throw new BatchRefException("@BatchQueryMethod metadata was not found on " + method);
        }

        BatchQuery<?> query = BatchQueryMethodQueries.create(target, targetClass, method, joinPoint.getArgs(), annotation);
        BatchQueryCapture.captured(query);
        return BatchQueryMethodQueries.captureReturnValue(method);
    }

    private Method resolveMethod(ProceedingJoinPoint joinPoint, Class<?> targetClass) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = AopUtils.getMostSpecificMethod(signature.getMethod(), targetClass);
        method.setAccessible(true);
        return method;
    }

    private BatchQueryMethod resolveAnnotation(ProceedingJoinPoint joinPoint, Method method) {
        BatchQueryMethod annotation = AnnotatedElementUtils.findMergedAnnotation(method, BatchQueryMethod.class);
        if (annotation != null) {
            return annotation;
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return AnnotatedElementUtils.findMergedAnnotation(signature.getMethod(), BatchQueryMethod.class);
    }

}
