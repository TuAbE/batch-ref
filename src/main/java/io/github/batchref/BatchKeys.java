package io.github.batchref;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class BatchKeys {

    private BatchKeys() {
    }

    @SuppressWarnings("unchecked")
    public static <K> List<K> cast(Collection<Object> keys) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<K> typedKeys = new ArrayList<>(keys.size());
        for (Object key : keys) {
            typedKeys.add((K) key);
        }
        return typedKeys;
    }

    public static <K> List<K> cast(Collection<Object> keys, Class<K> keyType) {
        Objects.requireNonNull(keyType, "keyType must not be null");
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<K> typedKeys = new ArrayList<>(keys.size());
        for (Object key : keys) {
            if (key == null) {
                continue;
            }
            if (!keyType.isInstance(key)) {
                throw new IllegalArgumentException(
                        "Expected batch key type " + keyType.getName() + " but got " + key.getClass().getName()
                );
            }
            typedKeys.add(keyType.cast(key));
        }
        return typedKeys;
    }

    public static <K, T> Map<Object, T> toObjectKeyMap(Map<K, T> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Object, T> result = new LinkedHashMap<>(source.size());
        for (Map.Entry<K, T> entry : source.entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public static <K, T> Map<Object, T> toObjectKeyMap(Collection<T> values, Function<T, K> keyExtractor) {
        Objects.requireNonNull(keyExtractor, "keyExtractor must not be null");
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Object, T> result = new LinkedHashMap<>();
        for (T value : values) {
            if (value == null) {
                continue;
            }
            result.putIfAbsent(keyExtractor.apply(value), value);
        }
        return result;
    }
}
