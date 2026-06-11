package io.github.batchref.function;

import java.io.Serializable;

@FunctionalInterface
public interface QuadFunction<A, B, C, D, R> extends Serializable {

    R apply(A first, B second, C third, D fourth);
}
