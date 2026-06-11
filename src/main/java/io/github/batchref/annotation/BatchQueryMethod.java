package io.github.batchref.annotation;

import org.intellij.lang.annotations.Language;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BatchQueryMethod {

    @Language(
            value = "JAVA",
            prefix = "class BatchQueryMethods { java.util.Map ",
            suffix = "(java.util.Collection keys) { return null; } }"
    )
    String batchMethod();
}
