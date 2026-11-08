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
 * <p>The declared statuses replace the 200 in the contract, so they must be exactly the ones the
 * handler answers: {@code ArchitectureTest} fails a handler whose bytecode answers a success status
 * it does not declare, or declares one it never answers, and in tests every request to a documented
 * handler that answers an undeclared success status fails ({@code DocumentedSuccessStatusFilter}).
 *
 * <p>US-29-10 (#457): the value is never empty and names only 2xx statuses. The status must be set
 * in the controller, from an {@link HttpStatus} constant or a {@code ResponseEntity}/{@code
 * VersionedResponse} factory: a response, response builder or status taken from another class fails
 * the build unless the handler is listed, with its reason, in {@code
 * ArchitectureTest.REVIEWED_EXTERNAL_RESPONSE_STATUS_HANDLERS}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SuccessStatus {

  HttpStatus[] value();
}
