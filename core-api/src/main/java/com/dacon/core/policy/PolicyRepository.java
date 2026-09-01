package com.dacon.core.policy;

import com.dacon.core.policy.PolicyDtos.PolicyQuestion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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

  SearchCatalog activeCatalog(String supportGoal) {
    if (entityManager == null) {
      throw new IllegalStateException("entity manager is required");
    }
    List<?> header =
        entityManager
            .createNativeQuery(
                "select s.id, q.embedding::text, q.question_flow::text from policy_index_snapshots s cross join policy_query_profiles q where s.status='ACTIVE' and q.support_goal=:goal")
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
                "select pv.id,p.id,p.title,p.summary,p.plan_connection,pv.source_version,pv.last_verified_at,pv.calculation_mode,c.embedding::text,c.metadata::text,ps.organization,ps.official_url,pvs.source_locator,exists(select 1 from policy_calculation_rules r where r.policy_version_id=pv.id) from policy_index_snapshots s join policy_snapshot_versions sv on sv.snapshot_id=s.id join policy_versions pv on pv.id=sv.policy_version_id join policies p on p.id=pv.policy_id join policy_chunks c on c.policy_version_id=pv.id join policy_version_sources pvs on pvs.policy_version_id=pv.id and pvs.is_primary join policy_sources ps on ps.id=pvs.policy_source_id where s.id=:snapshot and p.support_goal=:goal and pv.review_status='APPROVED' and (pv.effective_from is null or pv.effective_from<=current_date) and (pv.effective_to is null or pv.effective_to>=current_date)")
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
        (Boolean) row[13]);
  }

  private Instant instant(Object value) {
    return value instanceof Instant instant ? instant : ((OffsetDateTime) value).toInstant();
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
      List<Candidate> candidates) {}

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
      boolean approvedRule) {}
}
