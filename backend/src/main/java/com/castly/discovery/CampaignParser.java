package com.castly.discovery;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CampaignParser {
  private static final Pattern BUDGET = Pattern.compile("(?i)(?:under|budget|up to)\\s*(?:inr|rs\\.?|₹)?\\s*([0-9]{1,8})");
  private static final Pattern DATE = Pattern.compile("\\b(20[2-9][0-9]-[01][0-9]-[0-3][0-9])\\b");
  private static final Pattern LOCATION = Pattern.compile("(?i)\\bin\\s+([a-z][a-z .-]{1,60})");
  private static final Pattern SENSITIVE = Pattern.compile("(?i)\\b(race|religion|caste|ethnicity|pregnan(?:t|cy)|disability|sexual orientation|skin colo(?:u)?r|age|gender|nationality)\\b");
  private static final Pattern UNSUPPORTED_CURRENCY = Pattern.compile("(?i)(?:\\b(?:USD|EUR|GBP|dollars|euros|pounds)\\b|[$€£])");

  public Map<String, Object> parse(String description) {
    if (description == null || description.isBlank() || description.length() > 1000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campaign description must be 1–1000 characters");
    if (SENSITIVE.matcher(description).find())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sensitive audience or creator targeting is unsupported");
    if (UNSUPPORTED_CURRENCY.matcher(description).find())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only INR budgets are supported for this draft");
    String input = description.toLowerCase(Locale.ROOT);
    var inferred = new LinkedHashMap<String, Object>();
    for (String category : Campaign.categoryTaxonomy())
      if (Pattern.compile("\\b" + category.substring(0, category.length() - 1) + "s?\\b").matcher(input).find()) {
        inferred.put("category", category);
        break;
      }
    var skills = Campaign.skillTaxonomy().stream().filter(s -> input.matches("(?s).*\\b" + s + "\\b.*")).sorted().toList();
    if (!skills.isEmpty()) inferred.put("skills", skills);
    var deliverables = Campaign.deliverableTaxonomy().stream().filter(s -> input.matches("(?s).*\\b" + s + "s?\\b.*")).sorted().toList();
    if (!deliverables.isEmpty()) inferred.put("deliverables", deliverables);
    Matcher budget = BUDGET.matcher(description);
    if (budget.find()) {
      int amount = Integer.parseInt(budget.group(1));
      if (amount > 10_000_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Budget exceeds supported range");
      inferred.put("max_budget", amount);
      inferred.put("budget_unit_confirmation_required", true);
      inferred.put("currency_confirmation_required", !description.substring(0,budget.end()).toLowerCase(Locale.ROOT).matches("(?s).*\\b(?:inr|rs)\\b.*|.*₹.*"));
    }
    Matcher date = DATE.matcher(description);
    if (date.find()) {
      try {
        var parsed = LocalDate.parse(date.group(1));
        inferred.put("from", parsed);
        inferred.put("through", parsed);
      } catch (Exception ignored) { /* An invalid date is left for explicit user correction. */ }
    }
    Matcher location = LOCATION.matcher(description);
    if (location.find()) {
      String city = location.group(1).trim().split("[,.;]|\\s+(?:for|on|with|under)\\b")[0].trim();
      if (!city.isBlank() && city.length() <= 100) inferred.put("city", city);
    }
    for (String format : List.of("portrait", "landscape", "square"))
      if (input.matches("(?s).*\\b" + format + "\\b.*")) { inferred.put("format", format); break; }
    inferred.put("needs_confirmation", true);
    inferred.put("method", "local_structured_parser");
    return inferred;
  }
}
