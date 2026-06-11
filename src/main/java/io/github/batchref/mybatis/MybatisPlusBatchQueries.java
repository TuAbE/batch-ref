package io.github.batchref.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import io.github.batchref.BatchKeys;
import io.github.batchref.BatchQuery;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public final class MybatisPlusBatchQueries {

    private MybatisPlusBatchQueries() {
    }

    public static <K, T> BatchQuery<T> queryByKey(
            String loaderName,
            K key,
            BaseMapper<T> mapper,
            Class<K> keyType,
            SFunction<T, K> keyColumn,
            Function<T, K> keyExtractor
    ) {
        return queryByKey(loaderName, key, mapper, keyType, keyColumn, keyExtractor, wrapper -> {
        });
    }

    public static <K, T> BatchQuery<T> queryByKey(
            String loaderName,
            K key,
            BaseMapper<T> mapper,
            Class<K> keyType,
            SFunction<T, K> keyColumn,
            Function<T, K> keyExtractor,
            Consumer<LambdaQueryWrapper<T>> wrapperCustomizer
    ) {
        Function<Collection<Object>, Map<Object, T>> batchLoader = selectMapByKey(
                mapper,
                keyType,
                keyColumn,
                keyExtractor,
                wrapperCustomizer
        );
        return BatchQuery.of(
                loaderName,
                key,
                batchLoader,
                () -> batchLoader.apply(Collections.singletonList(key)).get(key)
        );
    }

    public static <K, T> Function<Collection<Object>, Map<Object, T>> selectMapByKey(
            BaseMapper<T> mapper,
            Class<K> keyType,
            SFunction<T, K> keyColumn,
            Function<T, K> keyExtractor
    ) {
        return selectMapByKey(mapper, keyType, keyColumn, keyExtractor, wrapper -> {
        });
    }

    public static <K, T> Function<Collection<Object>, Map<Object, T>> selectMapByKey(
            BaseMapper<T> mapper,
            Class<K> keyType,
            SFunction<T, K> keyColumn,
            Function<T, K> keyExtractor,
            Consumer<LambdaQueryWrapper<T>> wrapperCustomizer
    ) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        Objects.requireNonNull(keyType, "keyType must not be null");
        Objects.requireNonNull(keyColumn, "keyColumn must not be null");
        Objects.requireNonNull(keyExtractor, "keyExtractor must not be null");
        Objects.requireNonNull(wrapperCustomizer, "wrapperCustomizer must not be null");

        return keys -> {
            List<K> typedKeys = BatchKeys.cast(keys, keyType);
            if (typedKeys.isEmpty()) {
                return Collections.emptyMap();
            }

            LambdaQueryWrapper<T> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(keyColumn, typedKeys);
            wrapperCustomizer.accept(wrapper);

            List<T> values = mapper.selectList(wrapper);
            return BatchKeys.toObjectKeyMap(values, keyExtractor);
        };
    }
}
