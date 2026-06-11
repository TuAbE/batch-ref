package io.github.batchref.function;

import java.io.Serializable;
import java.util.function.BiFunction;

@FunctionalInterface
public interface BatchBiFunction<A, B, R> extends BiFunction<A, B, R>, Serializable {
}
