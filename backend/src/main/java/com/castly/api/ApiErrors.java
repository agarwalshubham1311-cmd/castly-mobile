package com.castly.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(ResponseStatusException.class)
  ProblemDetail status(ResponseStatusException e) {
    return ProblemDetail.forStatusAndDetail(
        e.getStatusCode(), e.getReason() == null ? "Request failed" : e.getReason());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    HandlerMethodValidationException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  ProblemDetail invalid(Exception e) {
    Throwable cause = e;
    for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause())
      if (cause instanceof com.castly.trust.EvidenceUploadLimitFilter.TooLarge)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.PAYLOAD_TOO_LARGE, "Evidence request exceeds size limit");
    var result =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Invalid request. Check required fields, formats and limits.");
    if (e instanceof MethodArgumentNotValidException validation)
      result.setProperty(
          "fields",
          validation.getBindingResult().getFieldErrors().stream()
              .map(error -> error.getField())
              .distinct()
              .toList());
    return result;
  }

  @ExceptionHandler(DatabaseStep.class)
  ProblemDetail databaseStep(DatabaseStep error) {
    String reference = java.util.UUID.randomUUID().toString();
    var result = databaseResponse(error.failure());
    result.setProperty("operation", error.operation());
    result.setProperty("reference", reference);
    var cause = error.failure().getMostSpecificCause();
    String state = SqlFailureKind.sqlState(error.failure());
    org.slf4j.LoggerFactory.getLogger(ApiErrors.class)
        .warn(
            "Business DB failure reference={} operation={} sqlstate={} exception_class={}"
                + " cause_class={} reason={} booking_diagnostics={}",
            reference,
            error.operation(),
            state,
            error.failure().getClass().getSimpleName(),
            cause.getClass().getSimpleName(),
            SqlFailureKind.of(error.failure()),
            error.evidence() == null ? "disabled_or_not_applicable" : error.evidence());
    return result;
  }

  @ExceptionHandler(DataAccessException.class)
  ProblemDetail database(DataAccessException e) {
    logConnectionFailure(e, "database.operation");
    return databaseResponse(e);
  }

  @ExceptionHandler(org.springframework.transaction.CannotCreateTransactionException.class)
  ProblemDetail transaction(org.springframework.transaction.CannotCreateTransactionException e) {
    logConnectionFailure(e, "database.transaction.begin");
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.SERVICE_UNAVAILABLE, "Castly is temporarily unavailable. Please retry.");
  }

  private void logConnectionFailure(RuntimeException failure, String operation) {
    org.slf4j.LoggerFactory.getLogger(ApiErrors.class)
        .warn(
            "Database failure reference={} operation={} sqlstate={} exception_class={} reason={}",
            java.util.UUID.randomUUID(),
            operation,
            SqlFailureKind.sqlState(failure),
            failure.getClass().getSimpleName(),
            SqlFailureKind.of(failure));
  }

  private ProblemDetail databaseResponse(DataAccessException e) {
    if (e instanceof DataIntegrityViolationException)
      return ProblemDetail.forStatusAndDetail(
          HttpStatus.CONFLICT, "The change conflicts with existing data or constraints.");
    if ("42501".equals(SqlFailureKind.sqlState(e)))
      return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Not permitted");
    if ("P0001".equals(SqlFailureKind.sqlState(e)))
      return ProblemDetail.forStatusAndDetail(
          HttpStatus.CONFLICT, "The requested change is not allowed");
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.SERVICE_UNAVAILABLE, "Castly is temporarily unavailable. Please retry.");
  }

  @ExceptionHandler({
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.servlet.NoHandlerFoundException.class
  })
  ProblemDetail missing(Exception e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found");
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ProblemDetail method(Exception e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "Method not supported");
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception e) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.INTERNAL_SERVER_ERROR, "Request could not be completed");
  }
}
