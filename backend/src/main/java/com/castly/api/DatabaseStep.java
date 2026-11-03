package com.castly.api;

import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;

/** Stable diagnostic step only; never log SQL, parameters, tokens or user IDs. */
public final class DatabaseStep extends RuntimeException {
  private final String operation;
  private final DataAccessException failure;
  private final BookingUpdateDiagnostics.Evidence evidence;

  private DatabaseStep(
      String operation, DataAccessException failure, BookingUpdateDiagnostics.Evidence evidence) {
    super("Business database operation failed");
    this.operation = operation;
    this.failure = failure;
    this.evidence = evidence;
  }

  public String operation() {
    return operation;
  }

  public DataAccessException failure() {
    return failure;
  }

  public BookingUpdateDiagnostics.Evidence evidence() {
    return evidence;
  }

  public static <T> T run(String operation, Supplier<T> work) {
    return run(operation, null, work);
  }

  public static <T> T run(
      String operation, BookingUpdateDiagnostics.Evidence evidence, Supplier<T> work) {
    try {
      return work.get();
    } catch (DataAccessException error) {
      throw new DatabaseStep(operation, error, evidence);
    }
  }
}
