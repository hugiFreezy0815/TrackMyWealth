package com.trackmywealth.backend.web;

import com.trackmywealth.backend.error.ProblemCode;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The problem codes an endpoint can answer with beyond those its signature already implies
 * (US-29-04, #374): the business-rule codes of the services it calls ({@code ACCOUNT_ARCHIVED},
 * {@code IMPORT_BATCH_STATE}, a plain 409's {@code CONFLICT}, ...). {@link OperationProblemCodes}
 * adds the implied ones - {@code NOT_FOUND} for a path id, {@code VERSION_REQUIRED} for {@code
 * If-Match}, ... - and the OpenAPI contract lists both per operation.
 *
 * <p>{@code ProblemCodeCoverageTest} keeps the list true: it fails when a code reachable from the
 * endpoint is missing here, when one listed here is no longer reachable, or when one is implied
 * anyway.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ProblemCodes {

  ProblemCode[] value();
}
