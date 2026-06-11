package io.github.batchref;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchRefTest {

    @Test
    void fallbackLoaderRunsWithoutScopeAndStepsApplyImmediately() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        AtomicInteger batchCalls = new AtomicInteger();
        Holder holder = new Holder();

        BatchRef<Row> ref = BatchRef.wrap(BatchQuery.of(
                "row.byId",
                1L,
                keys -> {
                    batchCalls.incrementAndGet();
                    return Map.<Object, Row>of();
                },
                () -> {
                    fallbackCalls.incrementAndGet();
                    return new Row(1L, "one", 1);
                }
        ));

        ref.whenPresent(() -> holder.present = true)
                .setOut(Row::name, value -> holder.name = value)
                .whenValue(Row::status, status -> status == 1, () -> holder.enabled = true);

        assertThat(fallbackCalls).hasValue(1);
        assertThat(batchCalls).hasValue(0);
        assertThat(holder.present).isTrue();
        assertThat(holder.name).isEqualTo("one");
        assertThat(holder.enabled).isTrue();
        assertThat(ref.unsafeGet().id()).isEqualTo(1L);
    }

    @Test
    void flushGroupsRefsByLoaderNameAndReplaysInRegistrationOrder() {
        AtomicInteger batchCalls = new AtomicInteger();
        Holder first = new Holder();
        Holder second = new Holder();
        Holder repeated = new Holder();

        BatchRefs.runInScope(() -> {
            ref(1L, batchCalls).setOut(Row::name, value -> first.name = value);
            ref(2L, batchCalls).setOut(Row::name, value -> second.name = value);
            ref(1L, batchCalls).setOut(Row::name, value -> repeated.name = value);

            assertThat(first.name).isNull();
            BatchRefs.flush();

            assertThat(first.name).isEqualTo("row-1");
            assertThat(second.name).isEqualTo("row-2");
            assertThat(repeated.name).isEqualTo("row-1");
        });

        assertThat(batchCalls).hasValue(1);
    }

    @Test
    void absentStepsAndDefaultValuesRunWhenValueIsMissing() {
        Holder holder = new Holder();

        BatchRefs.runInScope(() -> {
            BatchRef.wrap(BatchQuery.of(
                    "row.missing",
                    404L,
                    keys -> Map.<Object, Row>of(),
                    () -> null
            )).whenAbsent(() -> holder.present = false)
                    .setOutOrDefault(Row::name, value -> holder.name = value, "missing");

            BatchRefs.flush();
        });

        assertThat(holder.present).isFalse();
        assertThat(holder.name).isEqualTo("missing");
    }

    @Test
    void stepsAddedAfterFlushUseResolvedValueImmediately() {
        Holder holder = new Holder();
        BatchRef<Row> ref = BatchRefs.runInScope(() -> {
            BatchRef<Row> scopedRef = ref(7L, new AtomicInteger());
            BatchRefs.flush();
            return scopedRef;
        });

        ref.setOut(Row::name, value -> holder.name = value);

        assertThat(holder.name).isEqualTo("row-7");
    }

    @Test
    void unsafeGetBeforeFlushFails() {
        BatchRefs.runInScope(() -> {
            BatchRef<Row> ref = ref(1L, new AtomicInteger());
            assertThatThrownBy(ref::unsafeGet)
                    .isInstanceOf(BatchRefException.class)
                    .hasMessageContaining("flush");
            BatchRefs.flush();
        });
    }

    private static BatchRef<Row> ref(Long id, AtomicInteger batchCalls) {
        return BatchRef.wrap(BatchQuery.of(
                "row.byId",
                id,
                keys -> {
                    batchCalls.incrementAndGet();
                    return rows(keys);
                },
                () -> new Row(id, "fallback-" + id, 1)
        ));
    }

    private static Map<Object, Row> rows(Collection<Object> keys) {
        Map<Object, Row> rows = new LinkedHashMap<>();
        assertThat(keys).containsExactlyElementsOf(List.copyOf(keys));
        for (Object key : keys) {
            Long id = (Long) key;
            rows.put(id, new Row(id, "row-" + id, 1));
        }
        return rows;
    }

    private record Row(Long id, String name, Integer status) {
    }

    private static final class Holder {
        private boolean present;
        private boolean enabled;
        private String name;
    }
}
