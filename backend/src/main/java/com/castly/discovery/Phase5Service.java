package com.castly.discovery;

import static com.castly.api.ActorDatabase.*;

import com.castly.api.ActorDatabase;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Phase5Service {
  private final ActorDatabase data;
  private final Phase5RateLimit rate;
  private final SuggestionProvider provider;
  private final BoundedProvider boundedProvider;
  private final boolean advanced, recommendations, matching, assistance;

  public Phase5Service(ActorDatabase data, Phase5RateLimit rate, SuggestionProvider provider,
      BoundedProvider boundedProvider,
      @Value("${castly.phase5.advanced-search-enabled:false}") boolean advanced,
      @Value("${castly.phase5.ai-recommendations-enabled:false}") boolean recommendations,
      @Value("${castly.phase5.smart-matching-enabled:false}") boolean matching,
      @Value("${castly.phase5.ai-assistance-enabled:false}") boolean assistance) {
    this.data = data;
    this.rate = rate;
    this.provider = provider;
    this.boundedProvider = boundedProvider;
    this.advanced = advanced;
    this.recommendations = recommendations;
    this.matching = matching;
    this.assistance = assistance;
  }

  private void enabled(boolean flag) {
    if (!flag) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "This discovery feature is not enabled");
  }

  public Map<String, Object> parse(UUID actor, String description, CampaignParser parser) {
    enabled(matching);
    rate.check(actor, "campaign_parse", 20);
    return parser.parse(description);
  }

  public Map<String, Boolean> configuration() {
    return Map.of("advanced_search", advanced, "ai_recommendations", recommendations,
        "smart_matching", matching, "ai_assistance", assistance, "external_provider", provider.available());
  }

  public void forget(UUID actor) {
    boundedProvider.forget(actor);
  }

  public record Search(String category, String city, String region, List<String> skills,
      Integer minRate, Integer maxRate, LocalDate from, LocalDate through,
      String availability, String sort, int limit, int offset) {
    public Search {
      category = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
      city = city == null ? "" : city.trim();
      region = region == null ? "" : region.trim();
      skills = skills == null ? List.of() : List.copyOf(skills);
      availability = availability == null ? "any" : availability;
      sort = sort == null ? "name" : sort;
      minRate = minRate == null ? 0 : minRate;
      maxRate = maxRate == null ? 10_000_000 : maxRate;
      if (!category.isEmpty() && !Campaign.categoryTaxonomy().contains(category)
          || city.length() > 100 || region.length() > 100 || skills.size() > 10
          || skills.stream().anyMatch(s -> s.isBlank() || s.length() > 80)
          || minRate < 0 || maxRate > 10_000_000 || minRate > maxRate
          || !Set.of("any", "confirmed").contains(availability)
          || !Set.of("name", "rate_asc", "rate_desc", "recent", "relevance").contains(sort)
          || limit < 1 || limit > 50 || offset < 0 || offset > 10000)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search filters");
      if ((from == null) != (through == null) || from != null && (from.isBefore(LocalDate.now())
          || through.isBefore(from) || ChronoUnit.DAYS.between(from, through) > 30))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid availability window");
      if (availability.equals("confirmed") && from == null)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confirmed availability needs a date window");
    }
  }

  public List<Map<String, Object>> search(UUID actor, Search filters) {
    enabled(advanced);
    rate.check(actor, "advanced_search", 60);
    return data.as(actor, db -> candidates(db, filters, List.of(), 0, List.of()));
  }

  private List<Map<String, Object>> candidates(JdbcClient db, Search f, List<String> preferred,
      int rankingBudget, List<String> deliverables) {
    String order = switch (f.sort()) {
      case "rate_asc" -> "day_rate asc,id";
      case "rate_desc" -> "day_rate desc,id";
      case "recent" -> "created_at desc,id";
      case "relevance" -> "(least(10,skill_score*5)+case when portfolio_match then 5 else 0 end"
          + "+case when availability_status='confirmed'"
          + " then 15 else 0 end+case when ranking_budget>0 then"
          + " greatest(0,15-day_rate*15/nullif(ranking_budget,0)) else 0 end) desc,id";
      default -> "lower(display_name),id";
    };
    String skillCondition = f.skills().isEmpty() ? "" :
        " and public.phase5_normalized_skills(t.skills) @> string_to_array(lower(:skill_text),chr(31))";
    String sql = "with found as (select t.id,t.display_name,t.category_id,t.city,t.day_rate,"
        + " t.skills,t.available,t.created_at,p.region,:ranking_budget as ranking_budget,"
        + " exists(select 1 from public.portfolio_assets a where a.talent_id=t.id"
        + " and exists(select 1 from unnest(string_to_array(:deliverable_text,chr(31))) wanted"
        + " where position(lower(wanted) in lower(a.label))>0)) as portfolio_match,"
        + " (select count(*) from unnest(t.skills) actual where lower(actual)=any"
        + " (string_to_array(:preferred_text,chr(31)))) as skill_score,"
        + " case when cast(:from_day as date) is null"
        + " then 'unknown' when not t.available then 'unavailable'"
        + " when exists(select 1 from public.talent_availability_days a where a.talent_id=t.id"
        + " and not a.available and a.day between cast(:from_day as date) and cast(:through_day as date))"
        + " then 'unavailable' when exists(select 1 from public.talent_booking_busy_days b join"
        + " public.talent_availability_days a on a.talent_id=b.talent_id and a.day=b.day"
        + " and a.available where b.talent_id=t.id and b.day between"
        + " cast(:from_day as date) and cast(:through_day as date))"
        + " then 'unavailable' when (select count(*) from"
        + " public.talent_availability_days a where a.talent_id=t.id and a.available and a.day"
        + " between cast(:from_day as date) and cast(:through_day as date))=:days"
        + " then 'confirmed' else 'unknown' end availability_status"
        + " from public.talent_profiles t left join public.talent_discovery_preferences p"
        + " on p.talent_id=t.id and p.share_region where t.published"
        + " and (:category='' or t.category_id=:category) and (:city='' or lower(t.city)=lower(:city))"
        + " and (:region='' or lower(p.region)=lower(:region))"
        + " and t.day_rate between :min_rate and :max_rate"
        + skillCondition + ")"
        + " select id,display_name,category_id,city,day_rate,skills,available,region,skill_score,portfolio_match,"
        + " availability_status from found where (:availability='any' or"
        + " availability_status='confirmed') order by " + order + " limit :limit offset :offset";
    return rows(db.sql(sql)
        .param("from_day", f.from()).param("through_day", f.through())
        .param("days", f.from() == null ? 0 : (int) ChronoUnit.DAYS.between(f.from(), f.through()) + 1)
        .param("category", f.category()).param("city", f.city()).param("region", f.region())
        .param("min_rate", f.minRate()).param("max_rate", f.maxRate())
        .param("skill_text", String.join("\u001f", f.skills()))
        .param("preferred_text", String.join("\u001f", preferred))
        .param("deliverable_text", String.join("\u001f", deliverables))
        .param("ranking_budget", rankingBudget)
        .param("availability", f.availability()).param("limit", f.limit()).param("offset", f.offset()));
  }

  public Map<String, Object> preferences(UUID actor, String region, boolean share) {
    enabled(advanced);
    rate.check(actor, "region_preference", 10);
    if (region != null && !region.matches("[\\p{L}][\\p{L} .'-]{1,99}") || share && region == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a city/region label, not coordinates");
    return data.as(actor, db -> one(db.sql(
        "insert into public.talent_discovery_preferences(talent_id,region,share_region)"
            + " values(:u,:r,:s) on conflict(talent_id) do update set region=excluded.region,"
            + " share_region=excluded.share_region,updated_at=statement_timestamp() returning *")
        .param("u", actor).param("r", region, java.sql.Types.VARCHAR).param("s", share)));
  }

  public Map<String, Object> availability(UUID actor, LocalDate day, boolean available) {
    enabled(advanced);
    rate.check(actor, "availability_write", 30);
    if (day == null || day.isBefore(LocalDate.now()) || day.isAfter(LocalDate.now().plusYears(2)))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a future day within two years");
    return data.as(actor, db -> one(db.sql(
        "insert into public.talent_availability_days(talent_id,day,available) values(:u,:d,:a)"
            + " on conflict(talent_id,day) do update set available=excluded.available,"
            + " updated_at=statement_timestamp() returning *")
        .param("u", actor).param("d", day).param("a", available)));
  }

  public void withdrawAvailability(UUID actor, LocalDate day) {
    enabled(advanced);
    rate.check(actor, "availability_write", 30);
    data.as(actor, db -> {
      db.sql("delete from public.talent_availability_days where talent_id=:u and day=:d")
          .param("u", actor).param("d", day).update();
      return null;
    });
  }

  public Map<String, Object> shortlist(UUID actor, Campaign campaign, boolean recommendation) {
    enabled(recommendation ? recommendations : matching);
    rate.check(actor, recommendation ? "recommendations" : "shortlist", 20);
    return computeShortlist(actor, campaign, true);
  }

  private Map<String, Object> computeShortlist(UUID actor, Campaign campaign, boolean allowProvider) {
    Search filters = new Search(campaign.category(), campaign.remote() ? "" : campaign.city(),
        campaign.remote() ? "" : campaign.region(), campaign.skills(), 0,
        "per_day".equals(campaign.budgetUnit()) ? campaign.maxBudget() : null,
        campaign.from(), campaign.through(),
        campaign.from() != null && !campaign.allowUnknownAvailability() ? "confirmed" : "any",
        "relevance", 50, 0);
    var source = data.as(actor, db -> candidates(db, filters,
        campaign.personalize() ? campaign.preferredSkills() : List.of(),
        "per_day".equals(campaign.budgetUnit()) && campaign.maxBudget()!=null ? campaign.maxBudget() : 0,
        campaign.deliverables()));
    var ranked = new ArrayList<Map<String, Object>>();
    for (var row : source) {
      if ("unavailable".equals(row.get("availability_status"))) continue;
      Set<String> declared = new HashSet<>();
      for (Object skill : (List<?>) row.get("skills")) declared.add(skill.toString().toLowerCase(Locale.ROOT));
      int overlap = 0;
      for (String skill : campaign.skills()) if (declared.contains(skill)) overlap++;
      int preference = 0;
      if (campaign.personalize())
        for (String skill : campaign.preferredSkills()) if (declared.contains(skill)) preference++;
      int score = 20 + Math.min(40, overlap * 10) + Math.min(10, preference * 5);
      if (campaign.category() != null) score += 10;
      if (campaign.maxBudget() != null && "per_day".equals(campaign.budgetUnit()))
        score += Math.max(0, 15 - ((Number) row.get("day_rate")).intValue() * 15 / campaign.maxBudget());
      if ("confirmed".equals(row.get("availability_status"))) score += 15;
      if (Boolean.TRUE.equals(row.get("portfolio_match"))) score += 5;
      var reasons = new ArrayList<String>();
      if (campaign.category() != null) reasons.add("Declared creator category matches");
      if (overlap > 0) reasons.add(overlap + " declared skill" + (overlap == 1 ? "" : "s") + " match");
      if (campaign.maxBudget() != null && "per_day".equals(campaign.budgetUnit()))
        reasons.add("Indicative day rate is within the confirmed per-day cap; final quote is unknown");
      if ("confirmed".equals(row.get("availability_status"))) reasons.add("Creator-declared days have no accepted-booking conflict");
      if (Boolean.TRUE.equals(row.get("portfolio_match")))
        reasons.add("Public portfolio label mentions a requested deliverable; example is creator supplied");
      var missing = new ArrayList<String>();
      if (campaign.from() != null && !"confirmed".equals(row.get("availability_status"))) missing.add("date availability needs confirmation");
      if (campaign.maxBudget() != null && "campaign_total".equals(campaign.budgetUnit())) missing.add("total campaign quote is unknown");
      if (campaign.skills().size() > overlap) missing.add("some requested skills are not declared");
      if (!campaign.deliverables().isEmpty() && !Boolean.TRUE.equals(row.get("portfolio_match")))
        missing.add("no public portfolio label mentions a requested deliverable");
      if (campaign.format()!=null) missing.add("requested format needs direct confirmation");
      if (campaign.audience()!=null) missing.add("audience suitability is not assessed");
      var item = new LinkedHashMap<String, Object>(row);
      item.put("score", Math.min(100, score)); item.put("reasons", reasons); item.put("missing_data", missing);
      ranked.add(item);
    }
    ranked.sort(Comparator.<Map<String, Object>>comparingInt(r -> ((Number) r.get("score")).intValue()).reversed()
        .thenComparing(r -> r.get("id").toString()));
    if (ranked.size() > 20) ranked.subList(20, ranked.size()).clear();
    String method = "deterministic";
    if (allowProvider && campaign.allowExternalAi() && provider.available() && !ranked.isEmpty()) {
      int[] skills = ranked.stream().mapToInt(r -> ((Number) r.get("skill_score")).intValue()).toArray();
      int[] budgets = ranked.stream().mapToInt(r -> ((Number) r.get("day_rate")).intValue() / 1000).toArray();
      boolean[] days = new boolean[ranked.size()];
      for (int i=0;i<ranked.size();i++) days[i] = "confirmed".equals(ranked.get(i).get("availability_status"));
      var proposed = boundedProvider.reorder(actor,
          ranked.stream().map(r -> r.get("id").toString()).toList(),
          new SuggestionProvider.Signals(skills, budgets, days), campaign.toString());
      if (proposed.isPresent()) {
        var copy = List.copyOf(ranked);
        ranked.clear(); for (int index : proposed.get()) ranked.add(copy.get(index));
        method = "provider_reranked";
      }
      var fresh = data.as(actor, db -> candidates(db, filters,
          campaign.personalize() ? campaign.preferredSkills() : List.of(),
          "per_day".equals(campaign.budgetUnit()) && campaign.maxBudget()!=null ? campaign.maxBudget() : 0,
          campaign.deliverables()));
      if (!fresh.equals(source)) return computeShortlist(actor, campaign, false);
    }
    return Map.of("method", method, "provider_available", provider.available(), "suggestions_only", true,
        "candidates", ranked, "total_examined", source.size());
  }

  public Map<String, Object> brief(UUID actor, Campaign campaign, UUID talentId) {
    enabled(assistance); rate.check(actor, "brief", 20);
    var talent = data.as(actor, db -> one(db.sql(
        "select id,display_name,category_id,city,day_rate from public.talent_profiles where id=:id and published")
        .param("id", talentId)));
    String draft = "Campaign request for " + talent.get("display_name") + ". Deliverables: "
        + (campaign.deliverables().isEmpty() ? "to confirm" : String.join(", ", campaign.deliverables()))
        + ". Proposed dates: " + (campaign.from() == null ? "to confirm" : campaign.from() + " to " + campaign.through())
        + ". Location: " + (campaign.remote() ? "remote, confirm feasibility" : Objects.toString(campaign.city(), "to confirm"))
        + ". Budget: " + (campaign.maxBudget() == null ? "to discuss" : "INR " + campaign.maxBudget()
            + " " + campaign.budgetUnit().replace('_',' '))
        + ". Confirm scope, rights, schedule and final quote directly before booking.";
    return Map.of("label", "Editable local suggestion", "draft", draft, "persisted", false);
  }

  public Map<String, Object> profile(UUID actor) {
    enabled(assistance); rate.check(actor, "profile_help", 10);
    var row = data.as(actor, db -> one(db.sql(
        "select bio,skills,portrait_path from public.talent_profiles where id=:u")
        .param("u", actor)));
    var suggestions = new ArrayList<String>();
    if (row.get("bio") == null || row.get("bio").toString().length() < 80)
      suggestions.add("Describe your services and work examples in your own words.");
    if (((List<?>) row.get("skills")).isEmpty()) suggestions.add("Add only skills you can demonstrate.");
    if (row.get("portrait_path") == null) suggestions.add("Consider a clear portrait you have permission to publish.");
    if (suggestions.isEmpty()) suggestions.add("Review your profile for current, accurate details.");
    return Map.of("label", "Editable local suggestions", "suggestions", suggestions, "persisted", false);
  }

  public Map<String, Object> compare(UUID actor, List<UUID> ids) {
    enabled(assistance); rate.check(actor, "compare", 20);
    if (ids == null || ids.isEmpty() || ids.size() > 3 || new HashSet<>(ids).size() != ids.size())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Compare one to three distinct creators");
    var items = data.as(actor, db -> {
      var result = new ArrayList<Map<String,Object>>();
      for (UUID id : ids) result.add(one(db.sql(
          "select id,display_name,category_id,city,day_rate,skills from public.talent_profiles"
              + " where id=:id and published").param("id", id)));
      return result;
    });
    return Map.of("label", "Factual comparison, not a recommendation or final quote",
        "items", items, "availability", "unknown until a dated search and direct confirmation", "persisted", false);
  }
}
