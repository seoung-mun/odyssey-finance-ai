package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dacon.core.policy.PolicyDtos.PolicyArtifact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class PolicyCalculableArtifactTest {
  private static final Path APPROVED_RULES =
      Path.of("../data/policy/policy-calculable-allow-11-approved.json");
  private static final Path ACTIVATION_ARTIFACT =
      Path.of("../data/policy/policy-artifact-calculable-approved-23.json");
  private static final Path APPROVAL =
      Path.of("../data/policy/policy-calculable-allow-11-approval.json");
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final PolicyArtifactImportService importer =
      new PolicyArtifactImportService(null, mapper);

  @Test
  void validatesApprovedCalculableSubsetAndMergedRuntimeArtifact() throws Exception {
    PolicyArtifact approvedRules = parseAndValidate(APPROVED_RULES);
    PolicyArtifact activation = parseAndValidate(ACTIVATION_ARTIFACT);

    assertThat(approvedRules.policies()).hasSize(11);
    assertThat(approvedRules.policies())
        .allSatisfy(policy -> assertThat(policy.version().calculationRule()).isNotNull());
    assertThat(activation.policies()).hasSize(23);
    assertThat(activation.sources()).hasSize(23);
    assertThat(activation.policies())
        .filteredOn(policy -> policy.version().calculationRule() != null)
        .hasSize(11);
    assertThat(activation.policies())
        .filteredOn(policy -> policy.version().calculationMode().equals("ONE_TIME_FUNDING"))
        .hasSize(9);
    assertThat(activation.policies())
        .filteredOn(
            policy -> policy.version().calculationMode().equals("MONTHLY_EXPENSE_REDUCTION"))
        .hasSize(2);
    assertThat(activation.policies())
        .filteredOn(policy -> policy.version().calculationMode().equals("ELIGIBILITY_ONLY"))
        .hasSize(12);

    byte[] approvalBytes = Files.readAllBytes(APPROVAL);
    byte[] canonicalApproval = Arrays.copyOf(approvalBytes, approvalBytes.length - 1);
    String approvalHash =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalApproval));
    JsonNode approval = mapper.readTree(approvalBytes);
    assertThat(approval.path("scope").asText()).isEqualTo("CALCULABLE_FINANCIAL_CALCULATION");
    assertThat(approval.path("reviewer").asText()).isEqualTo("PROJECT_OWNER");
    assertThat(approval.path("approvedPolicyCount").asInt()).isEqualTo(11);
    assertThat(activation.reviewGate().reason()).contains(approvalHash);
  }

  @Test
  void preservesApprovedGoldenCasesAndP0Simplifications() throws Exception {
    PolicyArtifact artifact = parseAndValidate(ACTIVATION_ARTIFACT);

    assertThat(artifact.policies())
        .filteredOn(policy -> policy.version().calculationRule() != null)
        .allSatisfy(
            policy -> {
              JsonNode golden = policy.version().calculationRule().goldenCase();
              assertThat(golden.path("input").path("institutionConfirmed").asBoolean()).isTrue();
              assertThat(golden.path("input").path("amountWon").asLong()).isPositive();
              assertThat(golden.path("input").path("startYearMonth").asText()).isNotBlank();
              assertThat(golden.path("expected").path("adjustmentType").asText())
                  .isEqualTo(policy.version().calculationMode());
            });
    assertThat(artifact.policies())
        .filteredOn(
            policy ->
                policy.version().calculationRule() != null
                    && !policy
                        .version()
                        .calculationRule()
                        .goldenCase()
                        .path("p0Simplifications")
                        .isEmpty())
        .hasSize(2)
        .allSatisfy(
            policy -> {
              assertThat(policy.version().calculationMode()).isEqualTo("ONE_TIME_FUNDING");
              assertThat(policy.version().calculationRule().amountUpperBound()).isEqualTo(300_000);
            });
  }

  @Test
  void rejectsCalculableRuleWithoutReviewer() throws Exception {
    ObjectNode root = (ObjectNode) mapper.readTree(Files.readAllBytes(ACTIVATION_ARTIFACT));
    ObjectNode rule = null;
    for (JsonNode policy : root.withArray("policies")) {
      JsonNode candidate = policy.path("version").path("calculationRule");
      if (candidate.isObject()) {
        rule = (ObjectNode) candidate;
        break;
      }
    }
    assertThat(rule).isNotNull();
    rule.put("reviewer", "");
    byte[] invalid = PolicyTestArtifacts.canonicalBytes(mapper, root);

    assertThatThrownBy(
            () -> {
              PolicyArtifact artifact = importer.parseAndValidate(invalid);
              importer.validate(artifact);
            })
        .isInstanceOf(IllegalArgumentException.class);
  }

  private PolicyArtifact parseAndValidate(Path path) throws Exception {
    PolicyArtifact artifact = importer.parseAndValidate(Files.readAllBytes(path));
    importer.validate(artifact);
    return artifact;
  }
}
