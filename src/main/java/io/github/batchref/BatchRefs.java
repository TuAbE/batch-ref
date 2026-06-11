package io.github.batchref;

import java.util.Objects;
import java.util.function.Supplier;

public final class BatchRefs {

    private BatchRefs() {
    }

    public static <T> BatchRef<T> register(BatchQuery<T> query) {
        Objects.requireNonNull(query, "query must not be null");
        BatchContext context = BatchContextHolder.currentOrNull();
        if (context == null) {
            return BatchRef.immediate(query.fallbackLoader().get());
        }
        BatchRef<T> ref = BatchRef.deferred(query.loaderName(), query.key());
        context.register(query, ref);
        return ref;
    }

    public static void flush() {
        BatchContext context = BatchContextHolder.currentOrNull();
        if (context != null) {
            context.flush();
        }
    }

    public static boolean isActive() {
        return BatchContextHolder.exists();
    }

    public static boolean hasPendingRefs() {
        BatchContext context = BatchContextHolder.currentOrNull();
        return context != null && context.hasPendingRefs();
    }

    public static <T> T runInScope(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier must not be null");
        if (BatchContextHolder.exists()) {
            return supplier.get();
        }
        BatchContext context = BatchContextHolder.push();
        try {
            T result = supplier.get();
            context.flush();
            return result;
        } finally {
            BatchContextHolder.pop(context);
        }
    }

    public static void runInScope(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        runInScope(() -> {
            runnable.run();
            return null;
        });
    }
}
