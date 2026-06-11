package io.github.batchref;

import java.util.ArrayDeque;
import java.util.Deque;

public final class BatchContextHolder {

    private static final ThreadLocal<Deque<BatchContext>> CONTEXTS = ThreadLocal.withInitial(ArrayDeque::new);

    private BatchContextHolder() {
    }

    public static boolean exists() {
        return currentOrNull() != null;
    }

    public static BatchContext currentOrNull() {
        Deque<BatchContext> contexts = CONTEXTS.get();
        return contexts.peek();
    }

    public static BatchContext push() {
        BatchContext context = new BatchContext();
        CONTEXTS.get().push(context);
        return context;
    }

    public static void pop(BatchContext expected) {
        Deque<BatchContext> contexts = CONTEXTS.get();
        BatchContext actual = contexts.poll();
        if (actual != expected) {
            contexts.clear();
            CONTEXTS.remove();
            throw new IllegalStateException("BatchRef context stack is corrupted.");
        }
        if (contexts.isEmpty()) {
            CONTEXTS.remove();
        }
    }
}
