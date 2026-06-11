package io.github.batchref;

public class BatchRefException extends RuntimeException {

    public BatchRefException(String message) {
        super(message);
    }

    public BatchRefException(String message, Throwable cause) {
        super(message, cause);
    }
}
