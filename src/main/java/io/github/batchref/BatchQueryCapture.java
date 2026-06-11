package io.github.batchref;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Supplier;

public final class BatchQueryCapture {

    private static final ThreadLocal<Deque<CaptureFrame>> CAPTURES = ThreadLocal.withInitial(ArrayDeque::new);

    private BatchQueryCapture() {
    }

    static <T> CapturedQuery<T> capture(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier must not be null");
        CaptureFrame frame = new CaptureFrame();
        CAPTURES.get().push(frame);
        try {
            T immediateValue = supplier.get();
            @SuppressWarnings("unchecked")
            BatchQuery<T> query = (BatchQuery<T>) frame.query;
            return new CapturedQuery<>(query, immediateValue);
        } finally {
            Deque<CaptureFrame> frames = CAPTURES.get();
            frames.pop();
            if (frames.isEmpty()) {
                CAPTURES.remove();
            }
        }
    }

    public static boolean isCapturing() {
        Deque<CaptureFrame> frames = CAPTURES.get();
        return !frames.isEmpty();
    }

    public static void captured(BatchQuery<?> query) {
        Objects.requireNonNull(query, "query must not be null");
        Deque<CaptureFrame> frames = CAPTURES.get();
        if (frames.isEmpty()) {
            throw new BatchRefException("No active BatchQuery capture.");
        }
        CaptureFrame frame = frames.peek();
        if (frame.query != null) {
            throw new BatchRefException("Only one @BatchQueryMethod call can be captured by one BatchRef.wrap call.");
        }
        frame.query = query;
    }

    record CapturedQuery<T>(BatchQuery<T> query, T immediateValue) {
    }

    private static final class CaptureFrame {
        private BatchQuery<?> query;
    }
}
