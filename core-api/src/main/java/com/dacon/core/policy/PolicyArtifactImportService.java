package com.dacon.core.policy;

import com.dacon.core.policy.PolicyDtos.ArtifactCalculationRule;
import com.dacon.core.policy.PolicyDtos.ArtifactChunk;
import com.dacon.core.policy.PolicyDtos.ArtifactEligibility;
import com.dacon.core.policy.PolicyDtos.ArtifactPolicy;
import com.dacon.core.policy.PolicyDtos.ArtifactQueryProfile;
import com.dacon.core.policy.PolicyDtos.ArtifactSource;
import com.dacon.core.policy.PolicyDtos.ArtifactVersion;
import com.dacon.core.policy.PolicyDtos.PolicyArtifact;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
  public long importArtifact(byte[] rawJson) {
    PolicyArtifact artifact = parseAndValidate(rawJson);
    validate(artifact);
    // ponytail: artifact import는 희소 운영 command라 전역 DB lock으로 최초 동시 적재만 직렬화한다.
    entityManager.createNativeQuery("select pg_advisory_xact_lock(78150301)").getSingleResult();
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

    // 활성 snapshot이 참조하는 동일 version을 calculable로 승격할 수 있도록 먼저 retire한다.
    // 이후 import가 실패하면 @Transactional rollback으로 기존 ACTIVE 상태도 함께 복원된다.
    entityManager
        .createNativeQuery(
            "update policy_index_snapshots set status='RETIRED',activated_at=null where status='ACTIVE' and id<>:id")
        .setParameter("id", snapshotId)
        .executeUpdate();

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
      syncRuntimeMetadata(versionId, policy.version());
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
            "update policy_index_snapshots set status='ACTIVE',activated_at=now() where id=:id")
        .setParameter("id", snapshotId)
        .executeUpdate();
    return snapshotId;
  }

  PolicyArtifact parseAndValidate(byte[] rawJson) {
    if (rawJson == null || rawJson.length == 0) {
      throw new IllegalArgumentException("policy artifact JSON is required");
    }
    try {
      ObjectMapper strictMapper =
          mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
      JsonNode root =
          new ObjectMapper()
              .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .readTree(rawJson);
      rejectSecurityFields(root);
      JsonNode manifest = root.path("manifestSha256");
      if (!root.isObject() || !manifest.isTextual() || !manifest.asText().matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("invalid policy artifact manifest");
      }
      byte[] canonicalRaw = new ObjectMapper().writeValueAsBytes(canonical(root));
      byte[] canonicalFile = Arrays.copyOf(canonicalRaw, canonicalRaw.length + 1);
      canonicalFile[canonicalFile.length - 1] = '\n';
      if (!MessageDigest.isEqual(rawJson, canonicalRaw)
          && !MessageDigest.isEqual(rawJson, canonicalFile)) {
        throw new IllegalArgumentException("policy artifact JSON must use canonical encoding");
      }
      ObjectNode unsigned = ((ObjectNode) root).deepCopy();
      unsigned.put("manifestSha256", "");
      byte[] expected = sha256(new ObjectMapper().writeValueAsBytes(canonical(unsigned)));
      byte[] actual = java.util.HexFormat.of().parseHex(manifest.asText());
      if (!MessageDigest.isEqual(expected, actual)) {
        throw new IllegalArgumentException("policy artifact manifest hash does not match content");
      }
      return strictMapper.treeToValue(root, PolicyArtifact.class);
    } catch (IOException | IllegalArgumentException exception) {
      throw new IllegalArgumentException("invalid policy artifact JSON", exception);
    }
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

  private void syncRuntimeMetadata(long versionId, ArtifactVersion version) {
    if (version.applicationStatus() == null) {
      entityManager
          .createNativeQuery(
              "delete from policy_version_application_status where policy_version_id=:v")
          .setParameter("v", versionId)
          .executeUpdate();
    } else {
      var status = version.applicationStatus();
      entityManager
          .createNativeQuery(
              "insert into policy_version_application_status(policy_version_id,decision,as_of_date,current_status,verified_via,evidence_url,verified_at) values (:v,:d,:a,:c,:via,:u,:at) on conflict(policy_version_id) do update set decision=excluded.decision,as_of_date=excluded.as_of_date,current_status=excluded.current_status,verified_via=excluded.verified_via,evidence_url=excluded.evidence_url,verified_at=excluded.verified_at")
          .setParameter("v", versionId)
          .setParameter("d", status.decision())
          .setParameter("a", status.asOfDate())
          .setParameter("c", status.currentStatus())
          .setParameter("via", status.verifiedVia())
          .setParameter("u", status.evidenceUrl())
          .setParameter("at", status.verifiedAt())
          .executeUpdate();
    }

    entityManager
        .createNativeQuery("delete from policy_version_regions where policy_version_id=:v")
        .setParameter("v", versionId)
        .executeUpdate();
    if (version.eligibility() == null) {
      entityManager
          .createNativeQuery("delete from policy_version_eligibility where policy_version_id=:v")
          .setParameter("v", versionId)
          .executeUpdate();
      return;
    }

    ArtifactEligibility eligibility = version.eligibility();
    entityManager
        .createNativeQuery(
            "insert into policy_version_eligibility(policy_version_id,region_scope,age_min,age_max) values (:v,:s,:min,:max) on conflict(policy_version_id) do update set region_scope=excluded.region_scope,age_min=excluded.age_min,age_max=excluded.age_max")
        .setParameter("v", versionId)
        .setParameter("s", eligibility.regionScope())
        .setParameter("min", eligibility.ageMin())
        .setParameter("max", eligibility.ageMax())
        .executeUpdate();
    for (String regionCode : eligibility.regionCodes()) {
      entityManager
          .createNativeQuery(
              "insert into policy_version_regions(policy_version_id,region_code) values (:v,:c)")
          .setParameter("v", versionId)
          .setParameter("c", regionCode)
          .executeUpdate();
    }
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

  void validate(PolicyArtifact artifact) {
    if (artifact == null
        || artifact.embeddingDimension() != 1024
        || blank(artifact.artifactVersion())
        || blank(artifact.embeddingModel())
        || artifact.sources() == null
        || artifact.policies() == null
        || artifact.queryProfiles() == null
        || artifact.reviewGate() == null
        || !"APPROVED".equals(artifact.reviewGate().status())
        || !artifact.reviewGate().importable()
        || !validToolchain(artifact.toolchain())) {
      throw new IllegalArgumentException("invalid policy artifact manifest");
    }
    Set<String> sourceKeys = new HashSet<>();
    for (ArtifactSource source : artifact.sources()) {
      if (blank(source.sourceKey())
          || !sourceKeys.add(source.sourceKey())
          || blank(source.organization())
          || blank(source.officialUrl())
          || !hash(source.contentSha256())
          || !hash(source.textSha256())
          || source.retrievedAt() == null
          || source.bodyMarkers() == null
          || source.bodyMarkers().isEmpty()
          || blank(source.contentType())
          || blank(source.finalUrl())
          || source.httpStatus() != 200) {
        throw new IllegalArgumentException("invalid or duplicate policy source");
      }
    }
    Set<String> policyKeys = new HashSet<>();
    Set<String> referencedSourceKeys = new HashSet<>();
    for (ArtifactPolicy policy : artifact.policies()) {
      ArtifactVersion version = policy.version();
      if (blank(policy.policyKey())
          || !policyKeys.add(policy.policyKey())
          || blank(policy.title())
          || blank(policy.supportGoal())
          || version == null
          || !"APPROVED".equals(version.reviewStatus())
          || !sourceKeys.contains(version.sourceKey())
          || blank(version.sourceVersion())
          || blank(version.sourceLocator())
          || !MessageDigest.isEqual(
              sha256(version.sourceLocator().getBytes(StandardCharsets.UTF_8)),
              hex(version.locatorSha256()))
          || version.chunks() == null
          || version.chunks().isEmpty()) {
        throw new IllegalArgumentException("snapshot contains an unapproved or incomplete policy");
      }
      referencedSourceKeys.add(version.sourceKey());
      validateRuntimeMetadata(version);
      Set<Integer> chunkIndexes = new HashSet<>();
      for (ArtifactChunk chunk : version.chunks()) {
        if (chunk.chunkIndex() < 0
            || !chunkIndexes.add(chunk.chunkIndex())
            || blank(chunk.content())
            || chunk.metadata() == null
            || !chunk.metadata().isObject()) {
          throw new IllegalArgumentException("invalid or duplicate policy chunk");
        }
        validateEmbedding(chunk.embedding());
      }
      boolean calculable =
          List.of("ONE_TIME_FUNDING", "MONTHLY_EXPENSE_REDUCTION")
              .contains(version.calculationMode());
      if (calculable != (version.calculationRule() != null)) {
        throw new IllegalArgumentException("calculation mode requires a matching approved rule");
      }
      if (calculable) {
        validateRule(version);
      }
    }
    Set<String> profileGoals = new HashSet<>();
    for (ArtifactQueryProfile profile : artifact.queryProfiles()) {
      if (blank(profile.supportGoal())
          || !profileGoals.add(profile.supportGoal())
          || blank(profile.queryText())) {
        throw new IllegalArgumentException("invalid or duplicate policy query profile");
      }
      validateEmbedding(profile.embedding());
      if (profile.questionFlow() == null || profile.questionFlow().size() > 3) {
        throw new IllegalArgumentException(
            "policy question flow must contain at most three questions");
      }
      Set<String> questionIds = new HashSet<>();
      profile
          .questionFlow()
          .forEach(
              question -> {
                if (blank(question.questionId())
                    || !questionIds.add(question.questionId())
                    || blank(question.label())
                    || question.options() == null
                    || question.options().isEmpty()) {
                  throw new IllegalArgumentException("invalid policy question flow");
                }
                Set<String> optionValues = new HashSet<>();
                question
                    .options()
                    .forEach(
                        option -> {
                          if (blank(option.value())
                              || blank(option.label())
                              || !optionValues.add(option.value())) {
                            throw new IllegalArgumentException("invalid policy question option");
                          }
                        });
              });
    }
    if (!sourceKeys.equals(referencedSourceKeys)
        || artifact.policies().stream()
            .anyMatch(policy -> !profileGoals.contains(policy.supportGoal()))) {
      throw new IllegalArgumentException("policy artifact relationships are incomplete");
    }
  }

  private void validateRuntimeMetadata(ArtifactVersion version) {
    if ((version.applicationStatus() == null) != (version.eligibility() == null)) {
      throw new IllegalArgumentException("policy runtime metadata must be complete or absent");
    }
    if (version.applicationStatus() == null) {
      return;
    }

    var status = version.applicationStatus();
    if (!validApplicationDecision(status.decision())
        || status.asOfDate() == null
        || blank(status.currentStatus())
        || blank(status.verifiedVia())
        || blank(status.evidenceUrl())
        || status.verifiedAt() == null
        || status.verifiedAt().isAfter(status.asOfDate())) {
      throw new IllegalArgumentException("invalid policy application status metadata");
    }

    ArtifactEligibility eligibility = version.eligibility();
    if (!validRegionScope(eligibility.regionScope())
        || eligibility.regionCodes() == null
        || ("LOCAL".equals(eligibility.regionScope()) && eligibility.regionCodes().isEmpty())
        || eligibility.regionCodes().stream()
            .anyMatch(code -> code == null || !code.matches("\\d{5}"))
        || new HashSet<>(eligibility.regionCodes()).size() != eligibility.regionCodes().size()
        || (eligibility.ageMin() == null) != (eligibility.ageMax() == null)
        || !validAge(eligibility.ageMin())
        || !validAge(eligibility.ageMax())
        || (eligibility.ageMin() != null && eligibility.ageMin() > eligibility.ageMax())) {
      throw new IllegalArgumentException("invalid policy region or age metadata");
    }
  }

  private boolean validAge(Integer age) {
    return age == null || (age >= 0 && age <= 120);
  }

  private boolean validApplicationDecision(String decision) {
    return "ALLOW".equals(decision) || "EXCLUDE".equals(decision) || "RECHECK".equals(decision);
  }

  private boolean validRegionScope(String regionScope) {
    return "NATIONAL".equals(regionScope) || "LOCAL".equals(regionScope);
  }

  private void validateRule(ArtifactVersion version) {
    ArtifactCalculationRule rule = version.calculationRule();
    if (!version.calculationMode().equals(rule.adjustmentType())
        || !version.sourceVersion().equals(rule.sourceVersion())
        || !version.sourceLocator().equals(rule.approvedLocator())
        || !version.locatorSha256().equals(rule.approvedSha256())
        || rule.amountUpperBound() < 1
        || rule.amountUpperBound() > 1_000_000_000_000_000L
        || rule.goldenCase() == null
        || !rule.goldenCase().isObject()
        || rule.goldenCase().isEmpty()
        || rule.humanApprovedAt() == null
        || blank(rule.reviewer())) {
      throw new IllegalArgumentException("calculation rule does not match approved provenance");
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

  private JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      ObjectNode sorted = mapper.createObjectNode();
      Map<String, JsonNode> fields = new TreeMap<>();
      node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
      fields.forEach((key, value) -> sorted.set(key, canonical(value)));
      return sorted;
    }
    if (node.isArray()) {
      ArrayNode array = mapper.createArrayNode();
      node.forEach(value -> array.add(canonical(value)));
      return array;
    }
    return node;
  }

  private void rejectSecurityFields(JsonNode node) {
    if (node.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        String key = normalizeSecurityKey(field.getKey());
        if (Set.of("password", "secret", "apikey", "authorization", "cookie", "userid", "answers")
            .contains(key)) {
          throw new IllegalArgumentException("security-sensitive artifact field is forbidden");
        }
        rejectSecurityFields(field.getValue());
      }
    } else if (node.isArray()) {
      node.forEach(this::rejectSecurityFields);
    }
  }

  private String normalizeSecurityKey(String key) {
    String normalized = Normalizer.normalize(key, Normalizer.Form.NFKC);
    StringBuilder result = new StringBuilder(normalized.length());
    for (int index = 0; index < normalized.length(); index++) {
      char value = normalized.charAt(index);
      if (value >= 'A' && value <= 'Z') {
        result.append((char) (value + ('a' - 'A')));
      } else if ((value >= 'a' && value <= 'z') || (value >= '0' && value <= '9')) {
        result.append(value);
      }
    }
    return result.toString();
  }

  private byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private byte[] hex(String value) {
    try {
      return java.util.HexFormat.of().parseHex(value);
    } catch (IllegalArgumentException exception) {
      return new byte[0];
    }
  }

  private boolean hash(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private boolean validToolchain(PolicyDtos.ArtifactToolchain toolchain) {
    return toolchain != null
        && java.util.stream.Stream.of(
                toolchain.backend(),
                toolchain.blas(),
                toolchain.byteorder(),
                toolchain.machine(),
                toolchain.numpy(),
                toolchain.python(),
                toolchain.safetensors(),
                toolchain.scipy(),
                toolchain.sentenceTransformers(),
                toolchain.system(),
                toolchain.tokenizers(),
                toolchain.torch(),
                toolchain.transformers())
            .noneMatch(this::blank)
        && hash(toolchain.torchBuildSha256());
  }
}
