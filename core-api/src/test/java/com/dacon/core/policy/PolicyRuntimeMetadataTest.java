package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dacon.core.policy.PolicyDtos.ArtifactPolicy;
import com.dacon.core.policy.PolicyDtos.PolicyArtifact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyRuntimeMetadataTest {
  private static final Path DATA = Path.of("../data/policy");
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final PolicyArtifactImportService importer =
      new PolicyArtifactImportService(null, mapper);

  @Test
  void runtimeMetadataHandoffMatchesAllStablePolicyKeysAndReferences() throws Exception {
    PolicyArtifact artifact =
        importer.parseAndValidate(
            Files.readAllBytes(DATA.resolve("odyssey_policy_51_runtime_metadata_handoff_v1.json")));
    JsonNode statusReference =
        mapper.readTree(
            Files.readAllBytes(
                DATA.resolve("odyssey_policy_51_application_status_asof_2026-09-01_v3.json")));
    JsonNode eligibilityReference =
        mapper.readTree(
            Files.readAllBytes(DATA.resolve("odyssey_policy_51_region_age_companion.json")));

    Map<String, JsonNode> statuses = byPolicyKey(statusReference);
    Map<String, JsonNode> eligibilities = byPolicyKey(eligibilityReference);
    Map<String, Long> decisions = new HashMap<>();
    Set<String> mapped = new HashSet<>();
    for (ArtifactPolicy policy : artifact.policies()) {
      JsonNode status = statuses.get(policy.policyKey());
      JsonNode eligibility = eligibilities.get(policy.policyKey());
      assertThat(status).as("status %s", policy.policyKey()).isNotNull();
      assertThat(eligibility).as("eligibility %s", policy.policyKey()).isNotNull();
      assertThat(policy.title()).isEqualTo(status.path("title").asText());
      assertThat(policy.supportGoal()).isEqualTo(status.path("supportGoal").asText());
      assertThat(policy.version().applicationStatus().decision())
          .isEqualTo(status.path("top3Decision").asText());
      assertThat(policy.version().applicationStatus().asOfDate().toString())
          .isEqualTo(statusReference.path("asOfDate").asText());
      assertThat(policy.version().applicationStatus().currentStatus())
          .isEqualTo(status.path("currentStatus").asText());
      assertThat(policy.version().applicationStatus().verifiedVia())
          .isEqualTo(status.path("verifiedVia").asText());
      assertThat(policy.version().applicationStatus().evidenceUrl())
          .isEqualTo(status.path("evidenceUrl").asText());
      assertThat(policy.version().applicationStatus().verifiedAt().toString())
          .isEqualTo(status.path("verifiedAt").asText());

      JsonNode region = eligibility.path("region");
      JsonNode age = eligibility.path("age");
      assertThat(policy.version().eligibility().regionScope())
          .isEqualTo(region.path("scopeHint").asText());
      assertThat(policy.version().eligibility().regionCodes())
          .containsExactlyElementsOf(
              mapper.convertValue(
                  region.path("regionCodes"),
                  mapper
                      .getTypeFactory()
                      .constructCollectionType(java.util.List.class, String.class)));
      assertThat(policy.version().eligibility().ageMin())
          .isEqualTo(nullableInteger(age.path("minCandidate")));
      assertThat(policy.version().eligibility().ageMax())
          .isEqualTo(nullableInteger(age.path("maxCandidate")));
      decisions.merge(policy.version().applicationStatus().decision(), 1L, Long::sum);
      mapped.add(policy.policyKey());
    }

    assertThat(artifact.policies()).hasSize(51);
    assertThat(mapped).hasSize(51).containsExactlyInAnyOrderElementsOf(statuses.keySet());
    assertThat(mapped).containsExactlyInAnyOrderElementsOf(eligibilities.keySet());
    assertThat(decisions)
        .containsEntry("ALLOW", 23L)
        .containsEntry("EXCLUDE", 11L)
        .containsEntry("RECHECK", 17L);
    assertThat(artifact.reviewGate().status()).isEqualTo("PENDING");
    assertThat(artifact.reviewGate().importable()).isFalse();
    assertThat(artifact.policies())
        .allMatch(policy -> "PENDING".equals(policy.version().reviewStatus()))
        .allMatch(policy -> policy.version().calculationRule() == null)
        .allMatch(
            policy ->
                Set.of("ELIGIBILITY_ONLY", "INFORMATIONAL")
                    .contains(policy.version().calculationMode()));
  }

  @Test
  void legacyArtifactWithoutRuntimeMetadataRemainsValid() throws Exception {
    ObjectNode legacy = (ObjectNode) mapper.readTree(PolicyTestArtifacts.fourPolicies(mapper));
    legacy
        .path("policies")
        .forEach(
            policy -> {
              ObjectNode version = (ObjectNode) policy.path("version");
              version.remove("applicationStatus");
              version.remove("eligibility");
            });

    PolicyArtifact artifact =
        importer.parseAndValidate(PolicyTestArtifacts.canonicalBytes(mapper, legacy));

    importer.validate(artifact);
    assertThat(artifact.policies())
        .allMatch(
            policy ->
                policy.version().applicationStatus() == null
                    && policy.version().eligibility() == null);
  }

  @Test
  void incompleteOrUnknownRuntimeMetadataIsRejectedBeforePersistence() throws Exception {
    ObjectNode incomplete = (ObjectNode) mapper.readTree(PolicyTestArtifacts.fourPolicies(mapper));
    ((ObjectNode) incomplete.path("policies").get(0).path("version")).remove("eligibility");
    PolicyArtifact incompleteArtifact =
        importer.parseAndValidate(PolicyTestArtifacts.canonicalBytes(mapper, incomplete));
    assertThatThrownBy(() -> importer.validate(incompleteArtifact))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("policy runtime metadata must be complete or absent");

    ObjectNode unknownDecision =
        (ObjectNode) mapper.readTree(PolicyTestArtifacts.fourPolicies(mapper));
    ((ObjectNode) unknownDecision.path("policies").get(0).path("version").path("applicationStatus"))
        .put("decision", "UNKNOWN");
    PolicyArtifact unknownArtifact =
        importer.parseAndValidate(PolicyTestArtifacts.canonicalBytes(mapper, unknownDecision));
    assertThatThrownBy(() -> importer.validate(unknownArtifact))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invalid policy application status metadata");
  }

  private Map<String, JsonNode> byPolicyKey(JsonNode document) {
    Map<String, JsonNode> result = new HashMap<>();
    document
        .path("policies")
        .forEach(
            policy -> {
              String key = policy.path("policyKey").asText();
              assertThat(result.put(key, policy)).as("duplicate policyKey %s", key).isNull();
            });
    assertThat(result).hasSize(51);
    return result;
  }

  private Integer nullableInteger(JsonNode value) {
    return value.isNull() ? null : value.asInt();
  }
}
