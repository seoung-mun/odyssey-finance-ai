package com.dacon.core.policy;

import com.dacon.core.policy.PolicyDtos.PolicyQuestion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

/** ACTIVE 정책 snapshot과 query profile을 실제 PostgreSQL에서 읽는다. */
@Repository
public class PolicyRepository {
  private final EntityManager entityManager;
  private final ObjectMapper mapper;

  public PolicyRepository(EntityManager entityManager, ObjectMapper mapper) {
    this.entityManager = entityManager;
    this.mapper = mapper;
  }

  SearchCatalog activeCatalog(int userId, String supportGoal) {
    if (entityManager == null) {
      throw new IllegalStateException("entity manager is required");
    }
    List<?> header =
        entityManager
            .createNativeQuery(
                "select s.id, q.embedding::text, q.question_flow::text, btrim(up.region_code), up.birth_date from policy_index_snapshots s cross join policy_query_profiles q left join user_profiles up on up.user_id=:user where s.status='ACTIVE' and q.support_goal=:goal")
            .setParameter("user", userId)
            .setParameter("goal", supportGoal)
            .getResultList();
    if (header.size() != 1) {
      return null;
    }
    Object[] row = (Object[]) header.getFirst();
    long snapshotId = ((Number) row[0]).longValue();
    List<Candidate> candidates =
        entityManager
            .createNativeQuery(
                "select pv.id,p.id,p.title,p.summary,p.plan_connection,pv.source_version,pv.last_verified_at,pv.calculation_mode,c.embedding::text,c.metadata::text,ps.organization,ps.official_url,pvs.source_locator,exists(select 1 from policy_calculation_rules r where r.policy_version_id=pv.id),app_status.decision,eligibility.region_scope,coalesce((select jsonb_agg(btrim(region.region_code) order by region.region_code) from policy_version_regions region where region.policy_version_id=pv.id),'[]'::jsonb)::text,eligibility.age_min,eligibility.age_max from policy_index_snapshots s join policy_snapshot_versions sv on sv.snapshot_id=s.id join policy_versions pv on pv.id=sv.policy_version_id join policies p on p.id=pv.policy_id join policy_chunks c on c.policy_version_id=pv.id join policy_version_sources pvs on pvs.policy_version_id=pv.id and pvs.is_primary join policy_sources ps on ps.id=pvs.policy_source_id left join policy_version_application_status app_status on app_status.policy_version_id=pv.id left join policy_version_eligibility eligibility on eligibility.policy_version_id=pv.id where s.id=:snapshot and p.support_goal=:goal and pv.review_status='APPROVED' and (pv.effective_from is null or pv.effective_from<=current_date) and (pv.effective_to is null or pv.effective_to>=current_date)")
            .setParameter("snapshot", snapshotId)
            .setParameter("goal", supportGoal)
            .getResultList()
            .stream()
            .map(value -> candidate((Object[]) value))
            .toList();
    return new SearchCatalog(
        snapshotId,
        vector((String) row[1]),
        read((String) row[2], new TypeReference<List<PolicyQuestion>>() {}),
        new UserEligibilityProfile(nullableText(row[3]), date(row[4])),
        candidates);
  }

  void record(long snapshotId, String supportGoal, List<Long> versionIds, long latencyMs) {
    entityManager
        .createNativeQuery(
            "insert into policy_retrieval_runs(snapshot_id,support_goal,top_version_ids,latency_ms) values (:snapshot,:goal,cast(:ids as jsonb),:latency)")
        .setParameter("snapshot", snapshotId)
        .setParameter("goal", supportGoal)
        .setParameter("ids", write(versionIds))
        .setParameter("latency", Math.min(Integer.MAX_VALUE, latencyMs))
        .executeUpdate();
  }

  ScenarioSnapshot scenarioSnapshot(int userId, int planVersionId, long policyVersionId) {
    List<?> rows =
        entityManager
            .createNativeQuery(
                "select plan.status, run.input_snapshot::text, plan.as_of_date, "
                    + "option.option_type, option.nominal_level, option.recommended_monthly_spending, "
                    + "option.required_reduction_rate, option.simulation_coverage, "
                    + "version.review_status, version.calculation_mode, version.effective_from, version.effective_to, "
                    + "rule.adjustment_type, rule.amount_upper_bound, rule.max_months, rule.source_version, "
                    + "source.organization, source.official_url, version.source_version, version.last_verified_at, "
                    + "version_source.source_locator, policy.support_goal, "
                    + "exists(select 1 from policy_index_snapshots snapshot join policy_snapshot_versions member on member.snapshot_id=snapshot.id where snapshot.status='ACTIVE' and member.policy_version_id=version.id) "
                    + "from plan_versions plan "
                    + "join financial_goals goal on goal.id=plan.goal_id "
                    + "left join simulation_runs run on run.plan_version_id=plan.id "
                    + "left join plan_options option on option.plan_version_id=plan.id and option.selected_at is not null "
                    + "join policy_versions version on version.id=:policyVersionId "
                    + "join policies policy on policy.id=version.policy_id "
                    + "left join policy_calculation_rules rule on rule.policy_version_id=version.id "
                    + "left join policy_version_sources version_source on version_source.policy_version_id=version.id and version_source.is_primary "
                    + "left join policy_sources source on source.id=version_source.policy_source_id "
                    + "where plan.id=:planVersionId and goal.user_id=:userId")
            .setParameter("userId", userId)
            .setParameter("planVersionId", planVersionId)
            .setParameter("policyVersionId", policyVersionId)
            .getResultList();
    if (rows.isEmpty()) {
      return null;
    }
    Object[] row = (Object[]) rows.getFirst();
    return new ScenarioSnapshot(
        (String) row[0],
        (String) row[1],
        date(row[2]),
        (String) row[3],
        (java.math.BigDecimal) row[4],
        number(row[5]),
        (java.math.BigDecimal) row[6],
        (java.math.BigDecimal) row[7],
        (String) row[8],
        (String) row[9],
        date(row[10]),
        date(row[11]),
        (String) row[12],
        decimal(row[13]),
        row[14] == null ? null : ((Number) row[14]).intValue(),
        (String) row[15],
        (String) row[16],
        (String) row[17],
        (String) row[18],
        nullableInstant(row[19]),
        (String) row[20],
        (String) row[21],
        Boolean.TRUE.equals(row[22]));
  }

  private long number(Object value) {
    return value == null ? 0 : ((Number) value).longValue();
  }

  private java.math.BigDecimal decimal(Object value) {
    return value == null ? null : (java.math.BigDecimal) value;
  }

  private LocalDate date(Object value) {
    if (value == null) {
      return null;
    }
    return value instanceof LocalDate date ? date : ((java.sql.Date) value).toLocalDate();
  }

  private Candidate candidate(Object[] row) {
    return new Candidate(
        ((Number) row[0]).longValue(),
        ((Number) row[1]).longValue(),
        (String) row[2],
        (String) row[3],
        (String) row[4],
        (String) row[5],
        instant(row[6]),
        (String) row[7],
        vector((String) row[8]),
        read((String) row[9], new TypeReference<java.util.Map<String, Object>>() {}),
        (String) row[10],
        (String) row[11],
        (String) row[12],
        (Boolean) row[13],
        nullableText(row[14]),
        nullableText(row[15]),
        read((String) row[16], new TypeReference<List<String>>() {}),
        nullableInteger(row[17]),
        nullableInteger(row[18]));
  }

  private String nullableText(Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString().trim();
    return text.isEmpty() ? null : text;
  }

  private Integer nullableInteger(Object value) {
    return value == null ? null : ((Number) value).intValue();
  }

  private Instant instant(Object value) {
    return value instanceof Instant instant ? instant : ((OffsetDateTime) value).toInstant();
  }

  private Instant nullableInstant(Object value) {
    return value == null ? null : instant(value);
  }

  private double[] vector(String json) {
    List<Double> values = read(json, new TypeReference<List<Double>>() {});
    return values.stream().mapToDouble(Double::doubleValue).toArray();
  }

  private <T> T read(String json, TypeReference<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("invalid approved policy catalog JSON", exception);
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(exception);
    }
  }

  record SearchCatalog(
      long snapshotId,
      double[] queryEmbedding,
      List<PolicyQuestion> questions,
      UserEligibilityProfile profile,
      List<Candidate> candidates) {}

  record UserEligibilityProfile(String regionCode, LocalDate birthDate) {}

  record Candidate(
      long versionId,
      long policyId,
      String title,
      String summary,
      String planConnection,
      String sourceVersion,
      Instant lastVerifiedAt,
      String calculationMode,
      double[] embedding,
      java.util.Map<String, Object> metadata,
      String organization,
      String officialUrl,
      String locator,
      boolean approvedRule,
      String applicationDecision,
      String regionScope,
      List<String> regionCodes,
      Integer ageMin,
      Integer ageMax) {}

  record ScenarioSnapshot(
      String planStatus,
      String inputSnapshot,
      LocalDate asOfDate,
      String optionType,
      java.math.BigDecimal nominalLevel,
      long recommendedMonthlySpending,
      java.math.BigDecimal requiredReductionRate,
      java.math.BigDecimal simulationCoverage,
      String reviewStatus,
      String calculationMode,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      String adjustmentType,
      java.math.BigDecimal amountUpperBound,
      Integer maxMonths,
      String ruleSourceVersion,
      String organization,
      String officialUrl,
      String sourceVersion,
      Instant lastVerifiedAt,
      String locator,
      String supportGoal,
      boolean activeSnapshot) {}
}
