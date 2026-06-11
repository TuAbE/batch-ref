package io.github.batchref.function;

@FunctionalInterface
public interface TriFunction<A, B, C, R> {

    R apply(A first, B second, C third);
}
