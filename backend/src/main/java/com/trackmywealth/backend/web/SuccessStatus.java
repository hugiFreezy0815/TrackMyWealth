package com.trackmywealth.backend.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.http.HttpStatus;

/**
 * US-29-02 (#372): the success status(es) an endpoint answers with, when not 200. springdoc cannot
 * see the status a handler puts into its {@code ResponseEntity} and documents every success as 200;
 * {@code ApiDocumentationConfig} documents these instead, keeping the response body. A 201 created
 * resource, a 204 without a body - or both 201 and 200 for a find-or-create.
 *
 * <p>{@code ArchitectureTest} fails a handler that answers 201, 202 or 204 without declaring it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SuccessStatus {

  HttpStatus[] value();
}
