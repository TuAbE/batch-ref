package io.github.batchref;

import io.github.batchref.function.BatchBiFunction;
import io.github.batchref.function.BatchFunction;
import io.github.batchref.function.TriFunction;
import io.github.batchref.function.QuadFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class BatchRef<T> {

    private final String loaderName;
    private final Object key;
    private final List<Consumer<T>> presentSteps = new ArrayList<>();
    private final List<Runnable> absentSteps = new ArrayList<>();
    private T resolvedValue;
    private boolean resolved;

    private BatchRef(String loaderName, Object key) {
        this.loaderName = loaderName;
        this.key = key;
    }

    public static <T> BatchRef<T> wrap(BatchQuery<T> query) {
        return BatchRefs.register(query);
    }

    public static <T> BatchRef<T> wrap(Supplier<T> queryMethod) {
        BatchQueryCapture.CapturedQuery<T> capturedQuery = BatchQueryCapture.capture(queryMethod);
        if (capturedQuery.query() != null) {
            return BatchRefs.register(capturedQuery.query());
        }
        if (BatchRefs.isActive()) {
            throw new BatchRefException(
                    "BatchRef.wrap(...) was called inside @BatchScope, but no BatchQuery was captured. "
                            + "Use a direct @BatchQueryMethod method reference such as service::queryMethod, "
                            + "or pass BatchQuery.of(...) explicitly."
            );
        }
        return immediate(capturedQuery.immediateValue());
    }

    public static <A, T> BatchRef<T> wrap(BatchFunction<A, T> queryMethod, A arg) {
        Objects.requireNonNull(queryMethod, "queryMethod must not be null");
        if (!BatchRefs.isActive()) {
            return immediate(queryMethod.apply(arg));
        }
        return registerMethodReference(queryMethod, new Object[]{arg});
    }

    public static <A, B, T> BatchRef<T> wrap(BatchBiFunction<A, B, T> queryMethod, A firstArg, B secondArg) {
        Objects.requireNonNull(queryMethod, "queryMethod must not be null");
        if (!BatchRefs.isActive()) {
            return immediate(queryMethod.apply(firstArg, secondArg));
        }
        return registerMethodReference(queryMethod, new Object[]{firstArg, secondArg});
    }

    public static <A, B, C, T> BatchRef<T> wrap(
            TriFunction<A, B, C, T> queryMethod,
            A firstArg,
            B secondArg,
            C thirdArg
    ) {
        Objects.requireNonNull(queryMethod, "queryMethod must not be null");
        if (!BatchRefs.isActive()) {
            return immediate(queryMethod.apply(firstArg, secondArg, thirdArg));
        }
        return registerMethodReference(queryMethod, new Object[]{firstArg, secondArg, thirdArg});
    }

    public static <A, B, C, D, T> BatchRef<T> wrap(
            QuadFunction<A, B, C, D, T> queryMethod,
            A firstArg,
            B secondArg,
            C thirdArg,
            D fourthArg
    ) {
        Objects.requireNonNull(queryMethod, "queryMethod must not be null");
        if (!BatchRefs.isActive()) {
            return immediate(queryMethod.apply(firstArg, secondArg, thirdArg, fourthArg));
        }
        return registerMethodReference(queryMethod, new Object[]{firstArg, secondArg, thirdArg, fourthArg});
    }

    @SuppressWarnings("unchecked")
    private static <T> BatchRef<T> registerMethodReference(Object queryMethod, Object[] args) {
        BatchQuery<T> query = (BatchQuery<T>) BatchQueryMethodQueries.createFromMethodReference(queryMethod, args);
        return BatchRefs.register(query);
    }

    static <T> BatchRef<T> deferred(String loaderName, Object key) {
        return new BatchRef<>(loaderName, key);
    }

    static <T> BatchRef<T> immediate(T value) {
        BatchRef<T> ref = new BatchRef<>(null, null);
        ref.resolvedValue = value;
        ref.resolved = true;
        return ref;
    }

    public BatchRef<T> whenPresent(Runnable runner) {
        Objects.requireNonNull(runner, "runner must not be null");
        return whenPresent(value -> runner.run());
    }

    public BatchRef<T> whenPresent(Consumer<T> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        return addPresentStep(consumer);
    }

    public BatchRef<T> whenAbsent(Runnable runner) {
        Objects.requireNonNull(runner, "runner must not be null");
        return addAbsentStep(runner);
    }

    public <V> SetOutStep<T, V> setOut(Consumer<V> setter) {
        Objects.requireNonNull(setter, "setter must not be null");
        return new SetOutStep<>(this, setter);
    }

    public <V> BatchRef<T> setOut(Consumer<V> setter, Function<T, V> getter) {
        Objects.requireNonNull(setter, "setter must not be null");
        Objects.requireNonNull(getter, "getter must not be null");
        return addPresentStep(value -> setter.accept(getter.apply(value)));
    }

    public <V> BatchRef<T> setOutOrDefault(Consumer<V> setter, Function<T, V> getter, V defaultValue) {
        Objects.requireNonNull(setter, "setter must not be null");
        Objects.requireNonNull(getter, "getter must not be null");
        addPresentStep(value -> setter.accept(getter.apply(value)));
        return addAbsentStep(() -> setter.accept(defaultValue));
    }

    public <V, R> BatchRef<T> setOutMapped(
            Consumer<R> setter,
            Function<T, V> getter,
            Function<V, R> mapper
    ) {
        Objects.requireNonNull(setter, "setter must not be null");
        Objects.requireNonNull(getter, "getter must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        return addPresentStep(value -> setter.accept(mapper.apply(getter.apply(value))));
    }

    public <V> BatchRef<T> whenValue(
            Function<T, V> getter,
            Predicate<V> predicate,
            Runnable runner
    ) {
        Objects.requireNonNull(getter, "getter must not be null");
        Objects.requireNonNull(predicate, "predicate must not be null");
        Objects.requireNonNull(runner, "runner must not be null");
        return addPresentStep(value -> {
            V fieldValue = getter.apply(value);
            if (predicate.test(fieldValue)) {
                runner.run();
            }
        });
    }

    public <V> BatchRef<T> whenValue(
            Function<T, V> getter,
            Predicate<V> predicate,
            Runnable trueRunner,
            Runnable falseRunner
    ) {
        Objects.requireNonNull(getter, "getter must not be null");
        Objects.requireNonNull(predicate, "predicate must not be null");
        Objects.requireNonNull(trueRunner, "trueRunner must not be null");
        Objects.requireNonNull(falseRunner, "falseRunner must not be null");
        return addPresentStep(value -> {
            V fieldValue = getter.apply(value);
            if (predicate.test(fieldValue)) {
                trueRunner.run();
            } else {
                falseRunner.run();
            }
        });
    }

    public boolean isResolved() {
        return resolved;
    }

    String loaderName() {
        return loaderName;
    }

    Object key() {
        return key;
    }

    @SuppressWarnings("unchecked")
    void replay(Object value) {
        if (resolved) {
            return;
        }
        resolvedValue = (T) value;
        resolved = true;
        if (resolvedValue == null) {
            runAbsentSteps();
        } else {
            runPresentSteps(resolvedValue);
        }
        presentSteps.clear();
        absentSteps.clear();
    }

    private BatchRef<T> addPresentStep(Consumer<T> step) {
        if (!resolved) {
            presentSteps.add(step);
            return this;
        }
        if (resolvedValue != null) {
            step.accept(resolvedValue);
        }
        return this;
    }

    private BatchRef<T> addAbsentStep(Runnable step) {
        if (!resolved) {
            absentSteps.add(step);
            return this;
        }
        if (resolvedValue == null) {
            step.run();
        }
        return this;
    }

    private void runPresentSteps(T value) {
        for (Consumer<T> presentStep : presentSteps) {
            presentStep.accept(value);
        }
    }

    private void runAbsentSteps() {
        for (Runnable absentStep : absentSteps) {
            absentStep.run();
        }
    }

    public static final class SetOutStep<T, V> {

        private final BatchRef<T> ref;
        private final Consumer<V> setter;

        private SetOutStep(BatchRef<T> ref, Consumer<V> setter) {
            this.ref = ref;
            this.setter = setter;
        }

        public BatchRef<T> from(Function<T, V> getter) {
            return ref.setOut(setter, getter);
        }

        public BatchRef<T> fromOrDefault(Function<T, V> getter, V defaultValue) {
            return ref.setOutOrDefault(setter, getter, defaultValue);
        }

        public <S> BatchRef<T> fromMapped(Function<T, S> getter, Function<S, V> mapper) {
            return ref.setOutMapped(setter, getter, mapper);
        }
    }
}
