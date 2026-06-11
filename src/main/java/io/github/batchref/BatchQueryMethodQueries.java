package io.github.batchref;

import io.github.batchref.annotation.BatchQueryMethod;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
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

public final class BatchQueryMethodQueries {

    private BatchQueryMethodQueries() {
    }

    public static BatchQuery<?> createFromMethodReference(Object methodReference, Object[] args) {
        Objects.requireNonNull(methodReference, "methodReference must not be null");
        SerializedLambda lambda = serializedLambda(methodReference);
        if (lambda.getCapturedArgCount() != 1) {
            throw new BatchRefException(
                    "BatchRef.wrap(...) in @BatchScope requires a direct bound method reference, "
                            + "for example service::queryMethod."
            );
        }

        Object target = lambda.getCapturedArg(0);
        if (target == null) {
            throw new BatchRefException("BatchRef.wrap(...) cannot resolve target object from method reference.");
        }

        String methodName = lambda.getImplMethodName();
        if (methodName.startsWith("lambda$")) {
            throw new BatchRefException(
                    "BatchRef.wrap(...) in @BatchScope requires a direct @BatchQueryMethod method reference, "
                            + "not a lambda expression."
            );
        }

        Object invocationTarget = invocationTarget(target);
        Class<?> targetClass = userClass(target);
        Method method = resolveMethodReferenceMethod(lambda, targetClass, target.getClass());
        BatchQueryMethod annotation = resolveAnnotation(method, targetClass);
        if (annotation == null) {
            throw new BatchRefException(
                    "Cannot create batch query from " + targetClass.getName() + "#" + method.getName()
                            + ". Add @BatchQueryMethod to the referenced method, or use BatchQuery.of(...) directly."
            );
        }

        return create(invocationTarget, targetClass, method, args, annotation);
    }

    public static BatchQuery<?> create(
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

    public static BatchQueryMethod resolveAnnotation(Method method, Class<?> targetClass) {
        BatchQueryMethod annotation = AnnotatedElementUtils.findMergedAnnotation(method, BatchQueryMethod.class);
        if (annotation != null) {
            return annotation;
        }

        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);
        if (!specificMethod.equals(method)) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(specificMethod, BatchQueryMethod.class);
            if (annotation != null) {
                return annotation;
            }
        }

        for (Class<?> interfaceClass : ClassUtils.getAllInterfacesForClass(targetClass)) {
            Method interfaceMethod = findDeclaredMethod(interfaceClass, method.getName(), method.getParameterTypes());
            if (interfaceMethod == null) {
                continue;
            }
            annotation = AnnotatedElementUtils.findMergedAnnotation(interfaceMethod, BatchQueryMethod.class);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    public static Object captureReturnValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == Void.TYPE) {
            throw new BatchRefException("@BatchQueryMethod cannot be used on void method " + method);
        }
        if (returnType.isPrimitive()) {
            throw new BatchRefException("@BatchQueryMethod cannot be used on primitive return method " + method);
        }
        return null;
    }

    public static Class<?> userClass(Object target) {
        Class<?> targetClass = AopUtils.getTargetClass(target);
        if (targetClass == null) {
            targetClass = target.getClass();
        }
        return ClassUtils.getUserClass(targetClass);
    }

    private static Object invocationTarget(Object target) {
        if (!(target instanceof Advised advised)) {
            return target;
        }
        try {
            Object actualTarget = advised.getTargetSource().getTarget();
            return actualTarget == null ? target : actualTarget;
        } catch (Exception ex) {
            throw new BatchRefException("Cannot resolve Spring AOP target for batch query method reference.", ex);
        }
    }

    private static SerializedLambda serializedLambda(Object methodReference) {
        try {
            Method writeReplace = methodReference.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            Object replacement = writeReplace.invoke(methodReference);
            if (replacement instanceof SerializedLambda serializedLambda) {
                return serializedLambda;
            }
            throw new BatchRefException("Method reference did not produce SerializedLambda.");
        } catch (NoSuchMethodException ex) {
            throw new BatchRefException(
                    "BatchRef.wrap(...) in @BatchScope requires a serializable method reference.", ex
            );
        } catch (IllegalAccessException ex) {
            throw new BatchRefException("Cannot access method reference metadata.", ex);
        } catch (InvocationTargetException ex) {
            throw new BatchRefException("Cannot read method reference metadata.", ex.getCause());
        }
    }

    private static Method resolveMethodReferenceMethod(
            SerializedLambda lambda,
            Class<?> targetClass,
            Class<?> runtimeClass
    ) {
        Class<?>[] parameterTypes = parameterTypes(lambda, runtimeClass.getClassLoader());
        Method method = findDeclaredMethod(targetClass, lambda.getImplMethodName(), parameterTypes);
        if (method == null) {
            method = findDeclaredMethod(runtimeClass, lambda.getImplMethodName(), parameterTypes);
        }
        if (method == null) {
            method = findDeclaredMethod(implClass(lambda, runtimeClass.getClassLoader()), lambda.getImplMethodName(), parameterTypes);
        }
        if (method == null) {
            throw new BatchRefException(
                    "Cannot resolve method reference " + lambda.getImplClass().replace('/', '.')
                            + "#" + lambda.getImplMethodName()
            );
        }
        method = AopUtils.getMostSpecificMethod(method, targetClass);
        method.setAccessible(true);
        return method;
    }

    private static Class<?>[] parameterTypes(SerializedLambda lambda, ClassLoader classLoader) {
        MethodType methodType = MethodType.fromMethodDescriptorString(lambda.getImplMethodSignature(), classLoader);
        return methodType.parameterArray();
    }

    private static Class<?> implClass(SerializedLambda lambda, ClassLoader classLoader) {
        String className = lambda.getImplClass().replace('/', '.');
        try {
            return ClassUtils.forName(className, classLoader);
        } catch (ClassNotFoundException ex) {
            throw new BatchRefException("Cannot resolve method reference class " + className, ex);
        }
    }

    private static Method findDeclaredMethod(Class<?> type, String methodName, Class<?>[] parameterTypes) {
        Class<?> currentClass = type;
        while (currentClass != null && currentClass != Object.class) {
            try {
                Method method = currentClass.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                currentClass = currentClass.getSuperclass();
            }
        }
        return null;
    }

    private static String loaderName(Class<?> targetClass, Method method) {
        String parameterTypes = Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.joining(","));
        return targetClass.getName() + "#" + method.getName() + "(" + parameterTypes + ")";
    }

    private static Object toKey(Object[] args) {
        if (args.length == 0) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return args[0];
        }
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(args)));
    }

    private static Method findBatchMethod(Class<?> targetClass, Method queryMethod, String configuredBatchMethodName) {
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

    private static List<String> batchMethodNames(Method queryMethod, String configuredBatchMethodName) {
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

    private static String pluralizeLastToken(String value) {
        if (value.endsWith("Id")) {
            return value + "s";
        }
        return value + "List";
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static boolean isBatchMethod(Method method, List<String> batchMethodNames) {
        return batchMethodNames.contains(method.getName())
                && method.getParameterCount() == 1
                && Collection.class.isAssignableFrom(method.getParameterTypes()[0])
                && Map.class.isAssignableFrom(method.getReturnType());
    }

    private static Map<Object, Object> invokeBatchMethod(Object target, Method batchMethod, Collection<Object> keys) {
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

    private static Object invokeSingleMethod(Object target, Method method, Object[] args) {
        return invoke(target, method, args);
    }

    private static Object invoke(Object target, Method method, Object[] args) {
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
}
