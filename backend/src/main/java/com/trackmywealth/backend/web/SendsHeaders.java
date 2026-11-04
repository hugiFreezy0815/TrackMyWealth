package com.trackmywealth.backend.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * US-29-09 (#455): the response headers a handler sends on success, for the API contract. springdoc
 * cannot see headers set on a {@code ResponseEntity}; {@code ApiDocumentationConfig} documents the
 * declared ones, as required, on the success responses each comes with ({@link
 * ResponseHeader#comesWith}): the {@code ETag} on every one with a body, the {@code Location} on
 * the 201.
 *
 * <p>The declared headers must be exactly the ones the handler sends. {@code ArchitectureTest}
 * fails a handler whose bytecode sends a header it does not declare, or declares one it never
 * sends. In tests, every request to a documented handler whose success response lacks a declared
 * header or carries an undeclared one fails ({@code DocumentedSuccessStatusFilter}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SendsHeaders {

  ResponseHeader[] value();
}
