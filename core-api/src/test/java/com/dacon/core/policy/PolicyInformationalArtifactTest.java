package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.dacon.core.policy.PolicyDtos.PolicyArtifact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class PolicyInformationalArtifactTest {
  private static final Path ARTIFACT =
      Path.of("../data/policy/policy-artifact-informational-approved-23.json");
  private static final Path APPROVAL =
      Path.of("../data/policy/policy-artifact-informational-approved-23-approval.json");
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final PolicyArtifactImportService importer =
      new PolicyArtifactImportService(null, mapper);

  @Test
  void validatesHumanApprovedAllowOnlyInformationalArtifact() throws Exception {
    byte[] artifactBytes = Files.readAllBytes(ARTIFACT);
    PolicyArtifact artifact = importer.parseAndValidate(artifactBytes);

    assertThat(artifact.reviewGate().status()).isEqualTo("APPROVED");
    assertThat(artifact.reviewGate().importable()).isTrue();
    assertThat(artifact.policies()).hasSize(23);
    assertThat(artifact.sources()).hasSize(23);
    assertThat(artifact.policies())
        .allSatisfy(
            policy -> {
              assertThat(policy.version().applicationStatus().decision()).isEqualTo("ALLOW");
              assertThat(policy.version().reviewStatus()).isEqualTo("APPROVED");
              assertThat(policy.version().calculationMode()).isEqualTo("ELIGIBILITY_ONLY");
              assertThat(policy.version().calculationRule()).isNull();
            });

    byte[] approvalBytes = Files.readAllBytes(APPROVAL);
    assertThat(approvalBytes).endsWith((byte) '\n');
    byte[] canonicalApproval = Arrays.copyOf(approvalBytes, approvalBytes.length - 1);
    String approvalHash =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalApproval));
    assertThat(artifact.reviewGate().reason()).contains(approvalHash);

    JsonNode approval = mapper.readTree(approvalBytes);
    assertThat(approval.path("scope").asText()).isEqualTo("INFORMATIONAL_EXPOSURE_ONLY");
    assertThat(approval.path("reviewer").asText()).isEqualTo("PROJECT_OWNER");
    assertThat(approval.path("humanApprovedAt").asText()).isEqualTo("2026-09-04T00:47:10Z");
    assertThat(approval.path("approvedPolicyCount").asInt()).isEqualTo(23);
    assertThat(approval.path("notApproved").path("EXCLUDE").asInt()).isEqualTo(11);
    assertThat(approval.path("notApproved").path("RECHECK").asInt()).isEqualTo(17);
    assertThat(approval.path("notApproved").path("CALCULABLE_ACTIVATION").asInt()).isZero();
  }
}
