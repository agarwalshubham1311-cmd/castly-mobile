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

  @ExceptionHandler(DataAccessException.class)
  ProblemDetail database(DataAccessException e) {
    if (e instanceof DataIntegrityViolationException)
      return ProblemDetail.forStatusAndDetail(
          HttpStatus.CONFLICT, "The change conflicts with existing data or constraints.");
    Throwable root = e.getMostSpecificCause();
    if (root instanceof java.sql.SQLException sql) {
      if ("42501".equals(sql.getSQLState()))
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Not permitted");
      if ("P0001".equals(sql.getSQLState()))
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, "The requested change is not allowed");
    }
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
