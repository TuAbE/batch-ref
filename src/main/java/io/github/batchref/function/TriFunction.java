package io.github.batchref.function;

import java.io.Serializable;

@FunctionalInterface
public interface TriFunction<A, B, C, R> extends Serializable {

    R apply(A first, B second, C third);
}
