package com.castly.discovery;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Confirmed, bounded facts. No arbitrary instructions or sensitive targeting fields. */
public record Campaign(
    String category,
    List<String> skills,
    String city,
    String region,
    boolean remote,
    LocalDate from,
    LocalDate through,
    Integer maxBudget,
    String budgetUnit,
    List<String> deliverables,
    String format,
    String audience,
    boolean confirmed,
    boolean allowUnknownAvailability,
    boolean personalize,
    boolean allowExternalAi,
    List<String> preferredSkills) {
  private static final Set<String> CATEGORIES =
      Set.of("actors", "models", "creators", "musicians", "photographers");
  private static final Set<String> FORMATS = Set.of("portrait", "landscape", "square", "unspecified");
  private static final Set<String> AUDIENCES = Set.of("general", "business", "families", "unspecified");
  private static final Set<String> DELIVERABLES = Set.of("reel", "video", "photos", "post", "performance");
  private static final Set<String> SKILLS =
      Set.of("video", "acting", "photography", "fashion", "music", "editing", "dance", "makeup");

  public Campaign {
    skills = skills == null ? List.of() : List.copyOf(skills);
    deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
    preferredSkills = preferredSkills == null ? List.of() : List.copyOf(preferredSkills);
    category = clean(category);
    city = clean(city);
    region = clean(region);
    format = clean(format);
    budgetUnit = clean(budgetUnit);
    audience = clean(audience);
    if (!confirmed) bad("Confirm the structured campaign fields before matching");
    if (category != null && !CATEGORIES.contains(category)) bad("Unsupported creator category");
    if (skills.size() > 10 || !SKILLS.containsAll(skills)) bad("Unsupported skill filter");
    if (preferredSkills.size() > 10 || !SKILLS.containsAll(preferredSkills)) bad("Unsupported preference");
    if (deliverables.size() > 5 || !DELIVERABLES.containsAll(deliverables)) bad("Unsupported deliverable");
    if (format != null && !FORMATS.contains(format)) bad("Unsupported format");
    if (audience != null && !AUDIENCES.contains(audience)) bad("Unsupported audience");
    if (city != null && city.length() > 100 || region != null && region.length() > 100)
      bad("Location must be city or region only");
    if (maxBudget != null && (maxBudget < 1 || maxBudget > 10_000_000)) bad("Invalid budget");
    if (maxBudget != null && (budgetUnit == null || !Set.of("per_day", "campaign_total").contains(budgetUnit))) bad("Confirm whether the budget is per day or total campaign");
    if ((from == null) != (through == null)) bad("Provide both dates for a window");
    if (from != null && (from.isBefore(LocalDate.now()) || through.isBefore(from)
        || ChronoUnit.DAYS.between(from, through) > 30 || through.isAfter(LocalDate.now().plusYears(2))))
      bad("Date window must be 1–31 future days");
    if (personalize && preferredSkills.isEmpty()) bad("Choose explicit preferences for personalization");
  }

  private static String clean(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private static void bad(String reason) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }

  public static Set<String> skillTaxonomy() { return SKILLS; }
  public static Set<String> categoryTaxonomy() { return CATEGORIES; }
  public static Set<String> deliverableTaxonomy() { return DELIVERABLES; }
}
