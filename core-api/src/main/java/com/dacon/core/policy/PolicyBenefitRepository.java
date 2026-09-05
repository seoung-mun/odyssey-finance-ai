package com.dacon.core.policy;

import com.dacon.core.policy.PolicyBenefitDtos.PolicyBenefitResponse;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
class PolicyBenefitRepository {
  private final EntityManager entityManager;

  PolicyBenefitRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  ConfirmationPolicy policy(int userId, long versionId) {
    List<?> rows =
        entityManager
            .createNativeQuery(
                "select v.review_status,v.calculation_mode,v.effective_from,v.effective_to,r.adjustment_type,r.amount_upper_bound,r.max_months,a.decision,e.region_scope,coalesce((select jsonb_agg(btrim(pr.region_code)) from policy_version_regions pr where pr.policy_version_id=v.id),'[]'::jsonb)::text,e.age_min,e.age_max,btrim(up.region_code),up.birth_date,exists(select 1 from policy_index_snapshots s join policy_snapshot_versions m on m.snapshot_id=s.id where s.status='ACTIVE' and m.policy_version_id=v.id) from policy_versions v left join policy_calculation_rules r on r.policy_version_id=v.id left join policy_version_application_status a on a.policy_version_id=v.id left join policy_version_eligibility e on e.policy_version_id=v.id left join user_profiles up on up.user_id=:user where v.id=:version")
            .setParameter("user", userId)
            .setParameter("version", versionId)
            .getResultList();
    if (rows.isEmpty()) return null;
    Object[] r = (Object[]) rows.getFirst();
    return new ConfirmationPolicy(
        (String) r[0],
        (String) r[1],
        date(r[2]),
        date(r[3]),
        (String) r[4],
        (BigDecimal) r[5],
        r[6] == null ? null : ((Number) r[6]).intValue(),
        (String) r[7],
        (String) r[8],
        regionCodes((String) r[9]),
        integer(r[10]),
        integer(r[11]),
        r[12] == null ? null : r[12].toString(),
        date(r[13]),
        Boolean.TRUE.equals(r[14]));
  }

  PolicyBenefitResponse active(int userId, int goalId, long versionId) {
    List<?> rows =
        entityManager
            .createNativeQuery(
                "select id,goal_id,policy_version_id,adjustment_type,amount_won,start_year_month,end_year_month,status,confirmed_at from policy_benefits where user_id=:user and goal_id=:goal and policy_version_id=:version and status='CONFIRMED'")
            .setParameter("user", userId)
            .setParameter("goal", goalId)
            .setParameter("version", versionId)
            .getResultList();
    return rows.isEmpty() ? null : response((Object[]) rows.getFirst());
  }

  PolicyBenefitResponse insert(
      int userId,
      int goalId,
      long versionId,
      String type,
      long amount,
      YearMonth start,
      YearMonth end,
      Instant now) {
    Object[] row =
        (Object[])
            entityManager
                .createNativeQuery(
                    "insert into policy_benefits(user_id,goal_id,policy_version_id,adjustment_type,amount_won,start_year_month,end_year_month,status,confirmed_at) values (:user,:goal,:version,:type,:amount,:start,:end,'CONFIRMED',:now) returning id,goal_id,policy_version_id,adjustment_type,amount_won,start_year_month,end_year_month,status,confirmed_at")
                .setParameter("user", userId)
                .setParameter("goal", goalId)
                .setParameter("version", versionId)
                .setParameter("type", type)
                .setParameter("amount", BigDecimal.valueOf(amount))
                .setParameter("start", start.atDay(1))
                .setParameter("end", end == null ? null : end.atDay(1))
                .setParameter("now", now)
                .getSingleResult();
    return response(row);
  }

  List<PolicyBenefitResponse> list(int userId, int goalId) {
    return entityManager
        .createNativeQuery(
            "select id,goal_id,policy_version_id,adjustment_type,amount_won,start_year_month,end_year_month,status,confirmed_at from policy_benefits where user_id=:user and goal_id=:goal and status='CONFIRMED' order by confirmed_at desc,id desc")
        .setParameter("user", userId)
        .setParameter("goal", goalId)
        .getResultList()
        .stream()
        .map(v -> response((Object[]) v))
        .toList();
  }

  PolicyBenefitResponse find(int userId, long id) {
    List<?> rows =
        entityManager
            .createNativeQuery(
                "select id,goal_id,policy_version_id,adjustment_type,amount_won,start_year_month,end_year_month,status,confirmed_at from policy_benefits where user_id=:user and id=:id for update")
            .setParameter("user", userId)
            .setParameter("id", id)
            .getResultList();
    return rows.isEmpty() ? null : response((Object[]) rows.getFirst());
  }

  PolicyBenefitResponse cancel(int userId, long id) {
    Object[] row =
        (Object[])
            entityManager
                .createNativeQuery(
                    "update policy_benefits set status='CANCELLED',updated_at=now() where user_id=:user and id=:id returning id,goal_id,policy_version_id,adjustment_type,amount_won,start_year_month,end_year_month,status,confirmed_at")
                .setParameter("user", userId)
                .setParameter("id", id)
                .getSingleResult();
    return response(row);
  }

  private PolicyBenefitResponse response(Object[] r) {
    return new PolicyBenefitResponse(
        ((Number) r[0]).longValue(),
        ((Number) r[1]).intValue(),
        ((Number) r[2]).longValue(),
        (String) r[3],
        ((BigDecimal) r[4]).longValueExact(),
        YearMonth.from(date(r[5])),
        r[6] == null ? null : YearMonth.from(date(r[6])),
        (String) r[7],
        instant(r[8]));
  }

  private LocalDate date(Object v) {
    return v == null ? null : v instanceof LocalDate d ? d : ((Date) v).toLocalDate();
  }

  private Instant instant(Object v) {
    if (v instanceof Instant i) return i;
    if (v instanceof OffsetDateTime o) return o.toInstant();
    return ((java.sql.Timestamp) v).toInstant();
  }

  private Integer integer(Object v) {
    return v == null ? null : ((Number) v).intValue();
  }

  private List<String> regionCodes(String json) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  record ConfirmationPolicy(
      String reviewStatus,
      String calculationMode,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      String adjustmentType,
      BigDecimal amountUpperBound,
      Integer maxMonths,
      String decision,
      String regionScope,
      List<String> regionCodes,
      Integer ageMin,
      Integer ageMax,
      String userRegionCode,
      LocalDate birthDate,
      boolean activeSnapshot) {}
}
