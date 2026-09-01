package com.dacon.core.policy;

import com.dacon.core.policy.PolicyDtos.ArtifactCalculationRule;
import com.dacon.core.policy.PolicyDtos.ArtifactChunk;
import com.dacon.core.policy.PolicyDtos.ArtifactPolicy;
import com.dacon.core.policy.PolicyDtos.ArtifactQueryProfile;
import com.dacon.core.policy.PolicyDtos.ArtifactSource;
import com.dacon.core.policy.PolicyDtos.ArtifactVersion;
import com.dacon.core.policy.PolicyDtos.PolicyArtifact;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 승인된 오프라인 artifact를 한 transaction에서 멱등 적재하고 ACTIVE snapshot을 전환한다. */
@Service
public class PolicyArtifactImportService {
  private final EntityManager entityManager;
  private final ObjectMapper mapper;

  public PolicyArtifactImportService(EntityManager entityManager, ObjectMapper mapper) {
    this.entityManager = entityManager;
    this.mapper = mapper;
  }

  @Transactional
  public long importArtifact(PolicyArtifact artifact) {
    validate(artifact);
    List<?> existing =
        entityManager
            .createNativeQuery(
                "select id,manifest_sha256,embedding_model,embedding_dimension,status from policy_index_snapshots where artifact_version=:version for update")
            .setParameter("version", artifact.artifactVersion())
            .getResultList();
    if (!existing.isEmpty()) {
      Object[] row = (Object[]) existing.getFirst();
      if (!artifact.manifestSha256().equals(row[1])
          || !artifact.embeddingModel().equals(row[2])
          || artifact.embeddingDimension() != ((Number) row[3]).intValue()) {
        throw new IllegalStateException("artifact version conflicts with its persisted identity");
      }
      if ("ACTIVE".equals(row[4])) {
        return ((Number) row[0]).longValue();
      }
    }

    long snapshotId =
        ((Number)
                entityManager
                    .createNativeQuery("select ensure_policy_index_snapshot(:v,:h,:m,:d)")
                    .setParameter("v", artifact.artifactVersion())
                    .setParameter("h", artifact.manifestSha256())
                    .setParameter("m", artifact.embeddingModel())
                    .setParameter("d", artifact.embeddingDimension())
                    .getSingleResult())
            .longValue();

    for (ArtifactSource source : artifact.sources()) {
      entityManager
          .createNativeQuery(
              "insert into policy_sources(source_key,organization,official_url,content_sha256,retrieved_at) values (:k,:o,:u,:h,:r) on conflict(source_key) do update set organization=excluded.organization,official_url=excluded.official_url,content_sha256=excluded.content_sha256,retrieved_at=excluded.retrieved_at")
          .setParameter("k", source.sourceKey())
          .setParameter("o", source.organization())
          .setParameter("u", source.officialUrl())
          .setParameter("h", source.contentSha256())
          .setParameter("r", source.retrievedAt())
          .executeUpdate();
    }
    for (ArtifactPolicy policy : artifact.policies()) {
      long policyId = upsertPolicy(policy);
      long versionId = upsertVersion(policyId, policy.version());
      upsertProvenance(versionId, policy.version());
      for (ArtifactChunk chunk : policy.version().chunks()) {
        upsertChunk(versionId, chunk);
      }
      if (policy.version().calculationRule() != null) {
        upsertRule(versionId, policy.version());
      }
      entityManager
          .createNativeQuery(
              "insert into policy_snapshot_versions(snapshot_id,policy_version_id) values (:s,:v) on conflict do nothing")
          .setParameter("s", snapshotId)
          .setParameter("v", versionId)
          .executeUpdate();
    }
    for (ArtifactQueryProfile profile : artifact.queryProfiles()) {
      entityManager
          .createNativeQuery(
              "insert into policy_query_profiles(support_goal,query_text,embedding,question_flow) values (:g,:q,cast(:e as jsonb),cast(:f as jsonb)) on conflict(support_goal) do update set query_text=excluded.query_text,embedding=excluded.embedding,question_flow=excluded.question_flow,updated_at=now()")
          .setParameter("g", profile.supportGoal())
          .setParameter("q", profile.queryText())
          .setParameter("e", json(profile.embedding()))
          .setParameter("f", json(profile.questionFlow()))
          .executeUpdate();
    }
    entityManager
        .createNativeQuery(
            "update policy_index_snapshots set status='RETIRED',activated_at=null where status='ACTIVE' and id<>:id")
        .setParameter("id", snapshotId)
        .executeUpdate();
    entityManager
        .createNativeQuery(
            "update policy_index_snapshots set status='ACTIVE',activated_at=now() where id=:id")
        .setParameter("id", snapshotId)
        .executeUpdate();
    return snapshotId;
  }

  private long upsertPolicy(ArtifactPolicy policy) {
    return ((Number)
            entityManager
                .createNativeQuery(
                    "insert into policies(policy_key,title,support_goal,summary,plan_connection) values (:k,:t,:g,:s,:c) on conflict(policy_key) do update set title=excluded.title,support_goal=excluded.support_goal,summary=excluded.summary,plan_connection=excluded.plan_connection returning id")
                .setParameter("k", policy.policyKey())
                .setParameter("t", policy.title())
                .setParameter("g", policy.supportGoal())
                .setParameter("s", policy.summary())
                .setParameter("c", policy.planConnection())
                .getSingleResult())
        .longValue();
  }

  private long upsertVersion(long policyId, ArtifactVersion version) {
    return ((Number)
            entityManager
                .createNativeQuery(
                    "insert into policy_versions(policy_id,source_version,review_status,calculation_mode,effective_from,effective_to,last_verified_at) values (:p,:v,:r,:c,:f,:t,:l) on conflict(policy_id,source_version) do update set review_status=excluded.review_status,calculation_mode=excluded.calculation_mode,effective_from=excluded.effective_from,effective_to=excluded.effective_to,last_verified_at=excluded.last_verified_at returning id")
                .setParameter("p", policyId)
                .setParameter("v", version.sourceVersion())
                .setParameter("r", version.reviewStatus())
                .setParameter("c", version.calculationMode())
                .setParameter("f", version.effectiveFrom())
                .setParameter("t", version.effectiveTo())
                .setParameter("l", version.lastVerifiedAt())
                .getSingleResult())
        .longValue();
  }

  private void upsertProvenance(long versionId, ArtifactVersion version) {
    entityManager
        .createNativeQuery(
            "insert into policy_version_sources(policy_version_id,policy_source_id,source_locator,locator_sha256,is_primary) select :v,id,:l,:h,true from policy_sources where source_key=:k on conflict(policy_version_id,policy_source_id,source_locator) do update set locator_sha256=excluded.locator_sha256,is_primary=true")
        .setParameter("v", versionId)
        .setParameter("l", version.sourceLocator())
        .setParameter("h", version.locatorSha256())
        .setParameter("k", version.sourceKey())
        .executeUpdate();
  }

  private void upsertChunk(long versionId, ArtifactChunk chunk) {
    entityManager
        .createNativeQuery(
            "insert into policy_chunks(policy_version_id,chunk_index,content,embedding,metadata) values (:v,:i,:c,cast(:e as jsonb),cast(:m as jsonb)) on conflict(policy_version_id,chunk_index) do update set content=excluded.content,embedding=excluded.embedding,metadata=excluded.metadata")
        .setParameter("v", versionId)
        .setParameter("i", chunk.chunkIndex())
        .setParameter("c", chunk.content())
        .setParameter("e", json(chunk.embedding()))
        .setParameter("m", json(chunk.metadata()))
        .executeUpdate();
  }

  private void upsertRule(long versionId, ArtifactVersion version) {
    ArtifactCalculationRule rule = version.calculationRule();
    entityManager
        .createNativeQuery(
            "insert into policy_calculation_rules(policy_version_id,adjustment_type,amount_upper_bound,max_months,source_version,approved_locator,approved_sha256,golden_case,human_approved_at,reviewer) values (:v,:a,:u,:m,:s,:l,:h,cast(:g as jsonb),:at,:r) on conflict(policy_version_id) do update set adjustment_type=excluded.adjustment_type,amount_upper_bound=excluded.amount_upper_bound,max_months=excluded.max_months,source_version=excluded.source_version,approved_locator=excluded.approved_locator,approved_sha256=excluded.approved_sha256,golden_case=excluded.golden_case,human_approved_at=excluded.human_approved_at,reviewer=excluded.reviewer")
        .setParameter("v", versionId)
        .setParameter("a", rule.adjustmentType())
        .setParameter("u", rule.amountUpperBound())
        .setParameter("m", rule.maxMonths())
        .setParameter("s", version.sourceVersion())
        .setParameter("l", rule.approvedLocator())
        .setParameter("h", rule.approvedSha256())
        .setParameter("g", json(rule.goldenCase()))
        .setParameter("at", rule.humanApprovedAt())
        .setParameter("r", rule.reviewer())
        .executeUpdate();
  }

  private void validate(PolicyArtifact artifact) {
    if (artifact == null
        || artifact.embeddingDimension() != 1024
        || artifact.artifactVersion() == null
        || artifact.manifestSha256() == null
        || !artifact.manifestSha256().matches("[0-9a-f]{64}")
        || artifact.embeddingModel() == null
        || artifact.sources() == null
        || artifact.policies() == null
        || artifact.queryProfiles() == null) {
      throw new IllegalArgumentException("invalid policy artifact manifest");
    }
    Set<String> sourceKeys = new HashSet<>();
    artifact.sources().forEach(source -> sourceKeys.add(source.sourceKey()));
    for (ArtifactPolicy policy : artifact.policies()) {
      ArtifactVersion version = policy.version();
      if (version == null
          || !"APPROVED".equals(version.reviewStatus())
          || !sourceKeys.contains(version.sourceKey())
          || version.chunks() == null
          || version.chunks().isEmpty()) {
        throw new IllegalArgumentException("snapshot contains an unapproved or incomplete policy");
      }
      for (ArtifactChunk chunk : version.chunks()) {
        validateEmbedding(chunk.embedding());
      }
      boolean calculable =
          List.of("ONE_TIME_FUNDING", "MONTHLY_EXPENSE_REDUCTION")
              .contains(version.calculationMode());
      if (calculable != (version.calculationRule() != null)) {
        throw new IllegalArgumentException("calculation mode requires a matching approved rule");
      }
    }
    for (ArtifactQueryProfile profile : artifact.queryProfiles()) {
      validateEmbedding(profile.embedding());
      if (profile.questionFlow() == null || profile.questionFlow().size() > 3) {
        throw new IllegalArgumentException(
            "policy question flow must contain at most three questions");
      }
    }
  }

  private void validateEmbedding(List<Double> embedding) {
    if (embedding == null
        || embedding.size() != 1024
        || embedding.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
      throw new IllegalArgumentException("policy embedding must contain 1024 finite numbers");
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("artifact JSON cannot be serialized", exception);
    }
  }
}
