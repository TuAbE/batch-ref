package io.github.batchref;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Describes one value that can be loaded either by a single fallback query or by a grouped batch loader.
 *
 * @param <T> loaded entity type
 */
public final class BatchQuery<T> {

    private final String loaderName;
    private final Object key;
    private final Function<Collection<Object>, Map<Object, T>> batchLoader;
    private final Supplier<T> fallbackLoader;

    private BatchQuery(
            String loaderName,
            Object key,
            Function<Collection<Object>, Map<Object, T>> batchLoader,
            Supplier<T> fallbackLoader
    ) {
        this.loaderName = requireText(loaderName, "loaderName");
        this.key = key;
        this.batchLoader = Objects.requireNonNull(batchLoader, "batchLoader must not be null");
        this.fallbackLoader = Objects.requireNonNull(fallbackLoader, "fallbackLoader must not be null");
    }

    public static <T> BatchQuery<T> of(
            String loaderName,
            Object key,
            Function<Collection<Object>, Map<Object, T>> batchLoader,
            Supplier<T> fallbackLoader
    ) {
        return new BatchQuery<>(loaderName, key, batchLoader, fallbackLoader);
    }

    public static <K, T> BatchQuery<T> ofTyped(
            String loaderName,
            K key,
            Function<Collection<K>, Map<K, T>> batchLoader,
            Supplier<T> fallbackLoader
    ) {
        Objects.requireNonNull(batchLoader, "batchLoader must not be null");
        return of(
                loaderName,
                key,
                keys -> BatchKeys.toObjectKeyMap(batchLoader.apply(BatchKeys.cast(keys))),
                fallbackLoader
        );
    }

    public String loaderName() {
        return loaderName;
    }

    public Object key() {
        return key;
    }

    public Function<Collection<Object>, Map<Object, T>> batchLoader() {
        return batchLoader;
    }

    public Supplier<T> fallbackLoader() {
        return fallbackLoader;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
