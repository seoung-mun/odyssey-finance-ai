package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyPresetReferenceSmokeTest {
  private static final LocalDate EVALUATION_DATE = LocalDate.of(2026, 9, 3);

  @Test
  void representativePoliciesAcceptTheirPresetProfiles() throws Exception {
    JsonNode policies =
        new ObjectMapper()
            .findAndRegisterModules()
            .readTree(
                Path.of("../data/policy/odyssey_policy_51_runtime_metadata_handoff_v1.json")
                    .toFile())
            .path("policies");

    assertEligible(policies, "서구 청년 천원 복비(부동산 중개보수) 지원사업", "12240", LocalDate.of(1997, 1, 1));
    assertEligible(policies, "익산형 청년월세 지원사업", "52140", LocalDate.of(1997, 1, 1));
    assertEligible(policies, "제주 청년 희망충전 월세 지원", "50110", LocalDate.of(1991, 1, 1));
  }

  private void assertEligible(
      JsonNode policies, String title, String regionCode, LocalDate birthDate) {
    JsonNode policy = null;
    for (JsonNode candidate : policies) {
      if (title.equals(candidate.path("title").asText())) {
        policy = candidate;
        break;
      }
    }
    assertThat(policy).as(title).isNotNull();
    JsonNode version = policy.path("version");
    JsonNode eligibility = version.path("eligibility");
    List<String> regionCodes =
        new ObjectMapper()
            .convertValue(
                eligibility.path("regionCodes"),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
    PolicyRepository.Candidate candidate =
        new PolicyRepository.Candidate(
            1,
            1,
            title,
            "요약",
            "계획 연결",
            "reference",
            Instant.parse("2026-09-01T00:00:00Z"),
            "INFORMATIONAL",
            new double[1024],
            Map.of(),
            "기관",
            "https://example.invalid",
            title,
            false,
            version.path("applicationStatus").path("decision").asText(null),
            eligibility.path("regionScope").asText(null),
            regionCodes,
            eligibility.path("ageMin").isNull() ? null : eligibility.path("ageMin").asInt(),
            eligibility.path("ageMax").isNull() ? null : eligibility.path("ageMax").asInt());

    assertThat(
            PolicySearchService.isEligible(
                candidate,
                new PolicyRepository.UserEligibilityProfile(regionCode, birthDate),
                EVALUATION_DATE))
        .as(title)
        .isTrue();
  }
}
