package io.github.batchref.spring;

import io.github.batchref.BatchQuery;
import io.github.batchref.BatchQueryCapture;
import io.github.batchref.BatchRefException;
import io.github.batchref.annotation.BatchQueryMethod;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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

        BatchQuery<?> query = createQuery(target, targetClass, method, joinPoint.getArgs(), annotation);
        BatchQueryCapture.captured(query);
        return captureReturnValue(method);
    }

    private BatchQuery<?> createQuery(
            Object target,
            Class<?> targetClass,
            Method method,
            Object[] args,
            BatchQueryMethod annotation
    ) {
        Object[] capturedArgs = args == null ? new Object[0] : Arrays.copyOf(args, args.length);
        Object key = toKey(capturedArgs);
        Method batchMethod = findBatchMethod(targetClass, method, annotation.batchMethod());
        String loaderName = loaderName(targetClass, method);

        return BatchQuery.<Object>of(
                loaderName,
                key,
                keys -> invokeBatchMethod(target, batchMethod, keys),
                () -> invokeSingleMethod(target, method, capturedArgs)
        );
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

    private String loaderName(Class<?> targetClass, Method method) {
        String parameterTypes = Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.joining(","));
        return targetClass.getName() + "#" + method.getName() + "(" + parameterTypes + ")";
    }

    private Object toKey(Object[] args) {
        if (args.length == 0) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return args[0];
        }
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(args)));
    }

    private Method findBatchMethod(Class<?> targetClass, Method queryMethod, String configuredBatchMethodName) {
        List<String> batchMethodNames = batchMethodNames(queryMethod, configuredBatchMethodName);
        Class<?> currentClass = targetClass;
        while (currentClass != null && currentClass != Object.class) {
            for (Method method : currentClass.getDeclaredMethods()) {
                if (isBatchMethod(method, batchMethodNames)) {
                    method.setAccessible(true);
                    return method;
                }
            }
            currentClass = currentClass.getSuperclass();
        }

        throw new BatchRefException(
                "Cannot find batch method. Tried " + batchMethodNames
                        + "(Collection<...>) returning Map on " + targetClass.getName()
        );
    }

    private List<String> batchMethodNames(Method queryMethod, String configuredBatchMethodName) {
        if (configuredBatchMethodName != null && !configuredBatchMethodName.isBlank()) {
            return List.of(configuredBatchMethodName);
        }

        String queryMethodName = queryMethod.getName();
        List<String> names = new ArrayList<>();
        int byIndex = queryMethodName.indexOf("By");
        if (byIndex > 0) {
            String prefix = queryMethodName.substring(0, byIndex);
            String suffix = queryMethodName.substring(byIndex + 2);
            names.add(prefix + "MapBy" + pluralizeLastToken(suffix));
        }
        names.add(queryMethodName + "Map");
        names.add(queryMethodName + "Batch");
        names.add("batch" + capitalize(queryMethodName));
        return names;
    }

    private String pluralizeLastToken(String value) {
        if (value.endsWith("Id")) {
            return value + "s";
        }
        return value + "List";
    }

    private String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private boolean isBatchMethod(Method method, List<String> batchMethodNames) {
        return batchMethodNames.contains(method.getName())
                && method.getParameterCount() == 1
                && Collection.class.isAssignableFrom(method.getParameterTypes()[0])
                && Map.class.isAssignableFrom(method.getReturnType());
    }

    private Map<Object, Object> invokeBatchMethod(Object target, Method batchMethod, Collection<Object> keys) {
        Object result = invoke(target, batchMethod, new Object[]{keys});
        if (result == null) {
            return Collections.emptyMap();
        }
        Map<?, ?> loadedMap = (Map<?, ?>) result;
        Map<Object, Object> objectKeyMap = new LinkedHashMap<>(loadedMap.size());
        for (Map.Entry<?, ?> entry : loadedMap.entrySet()) {
            objectKeyMap.put(entry.getKey(), entry.getValue());
        }
        return objectKeyMap;
    }

    private Object invokeSingleMethod(Object target, Method method, Object[] args) {
        return invoke(target, method, args);
    }

    private Object invoke(Object target, Method method, Object[] args) {
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException ex) {
            throw new BatchRefException("Cannot invoke " + method, ex);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new BatchRefException("Cannot invoke " + method, cause);
        }
    }

    private Object captureReturnValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == Void.TYPE) {
            throw new BatchRefException("@BatchQueryMethod cannot be used on void method " + method);
        }
        if (returnType.isPrimitive()) {
            throw new BatchRefException("@BatchQueryMethod cannot be used on primitive return method " + method);
        }
        return null;
    }
}
