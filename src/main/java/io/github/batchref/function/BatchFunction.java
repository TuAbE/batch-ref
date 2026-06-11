package io.github.batchref.function;

import java.io.Serializable;
import java.util.function.Function;

@FunctionalInterface
public interface BatchFunction<A, R> extends Function<A, R>, Serializable {
}
