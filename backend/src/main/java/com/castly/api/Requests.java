package com.castly.api;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;

public final class Requests {
  private Requests() {}

  public record Onboard(
      @NotBlank @Pattern(regexp = "client|talent") String selectedRole,
      @NotBlank @Size(min = 2, max = 100) String accountName) {}

  public record Rename(@NotBlank @Size(min = 2, max = 100) String displayName) {}

  public record Talent(
      @NotBlank @Size(min = 2, max = 100) String displayName,
      @NotBlank @Pattern(regexp = "actors|models|creators|musicians|photographers")
          String categoryId,
      @NotBlank @Size(min = 2, max = 100) String city,
      @NotNull @Min(0) @Max(10000000) Integer dayRate,
      @NotNull @Size(max = 2000) String bio,
      @NotNull @Size(max = 20) List<@NotBlank @Size(max = 80) String> skills,
      @NotNull Boolean available,
      @NotNull Boolean published,
      @Size(max = 1024) String portraitPath) {}

  public record Portfolio(
      @NotBlank @Size(max = 1024) String path,
      @NotBlank @Size(max = 160) String label,
      @Min(0) int position) {}

  public record EditPortfolio(@NotBlank @Size(max = 160) String label, @Min(0) int position) {}

  public record CreateBooking(
      @NotNull UUID talentId,
      @NotBlank @Size(min = 2, max = 100) String projectType,
      @NotBlank @Size(min = 2, max = 200) String location,
      @NotBlank @Size(min = 10, max = 4000) String requirements,
      @NotNull @FutureOrPresent LocalDate proposedDate,
      @NotNull @Min(1) @Max(10000000) Integer indicativeBudget) {}

  public record SendMessage(@NotBlank @Size(max = 4000) String body) {}

  public record OpenConversation(@NotNull UUID bookingId) {}
}
