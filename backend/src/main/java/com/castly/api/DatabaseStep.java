package com.castly.api;

import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;

/** Stable diagnostic step only; never log SQL, parameters, tokens or user IDs. */
public final class DatabaseStep extends RuntimeException {
  private final String operation;
  private final DataAccessException failure;

  private DatabaseStep(String operation, DataAccessException failure) {
    super("Business database operation failed");
    this.operation = operation;
    this.failure = failure;
  }

  public String operation() {
    return operation;
  }

  public DataAccessException failure() {
    return failure;
  }

  public static <T> T run(String operation, Supplier<T> work) {
    try {
      return work.get();
    } catch (DataAccessException error) {
      throw new DatabaseStep(operation, error);
    }
  }
}
