package com.trackmywealth.backend.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint that may answer a 503 the client retries unchanged, with a {@code Retry-After}
 * header when waiting helps (e.g. {@code IMPORT_PDF_BUSY}). {@code ApiDocumentationConfig}
 * documents that response in the OpenAPI contract.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RetryableWhenBusy {}
