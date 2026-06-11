package io.github.batchref;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
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
        return immediate(capturedQuery.immediateValue());
    }

    public static <A, T> BatchRef<T> wrap(Function<A, T> queryMethod, A arg) {
        Objects.requireNonNull(queryMethod, "queryMethod must not be null");
        return wrap(() -> queryMethod.apply(arg));
    }

    public static <A, B, T> BatchRef<T> wrap(BiFunction<A, B, T> queryMethod, A firstArg, B secondArg) {
        Objects.requireNonNull(queryMethod, "queryMethod must not be null");
        return wrap(() -> queryMethod.apply(firstArg, secondArg));
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
        return addPresentStep(value -> runner.run());
    }

    public BatchRef<T> whenAbsent(Runnable runner) {
        Objects.requireNonNull(runner, "runner must not be null");
        return addAbsentStep(runner);
    }

    public <V> BatchRef<T> setOut(Function<T, V> getter, Consumer<V> setter) {
        Objects.requireNonNull(getter, "getter must not be null");
        Objects.requireNonNull(setter, "setter must not be null");
        return addPresentStep(value -> setter.accept(getter.apply(value)));
    }

    public <V> BatchRef<T> setOutOrDefault(Function<T, V> getter, Consumer<V> setter, V defaultValue) {
        Objects.requireNonNull(getter, "getter must not be null");
        Objects.requireNonNull(setter, "setter must not be null");
        addPresentStep(value -> setter.accept(getter.apply(value)));
        return addAbsentStep(() -> setter.accept(defaultValue));
    }

    public <V, R> BatchRef<T> setOutMapped(
            Function<T, V> getter,
            Function<V, R> mapper,
            Consumer<R> setter
    ) {
        Objects.requireNonNull(getter, "getter must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        Objects.requireNonNull(setter, "setter must not be null");
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
}
