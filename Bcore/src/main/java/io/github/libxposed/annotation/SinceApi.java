package io.github.libxposed.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the API version that a declaration was introduced in.
 * <p>
 * This is a source-level annotation vendored from the libxposed project; it is kept with
 * {@code CLASS} retention so it never appears in compiled dex and has no runtime footprint.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD,
        ElementType.PARAMETER, ElementType.PACKAGE, ElementType.ANNOTATION_TYPE})
public @interface SinceApi {
    int value();
}
