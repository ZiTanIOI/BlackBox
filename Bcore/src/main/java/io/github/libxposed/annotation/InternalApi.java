package io.github.libxposed.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks declarations that are internal to a framework implementation and may change without
 * compatibility guarantees. Vendored from the libxposed project; {@code CLASS} retention keeps
 * it out of compiled dex.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD,
        ElementType.PACKAGE, ElementType.ANNOTATION_TYPE})
public @interface InternalApi {
}
