package io.github.batchref;

record BatchRegistration<T>(BatchQuery<T> query, BatchRef<T> ref) {
}
