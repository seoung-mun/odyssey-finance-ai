package com.dacon.core.policy;

import com.dacon.core.policy.PolicyDtos.ArtifactCalculationRule;
import com.dacon.core.policy.PolicyDtos.ArtifactChunk;
import com.dacon.core.policy.PolicyDtos.ArtifactPolicy;
import com.dacon.core.policy.PolicyDtos.ArtifactQueryProfile;
import com.dacon.core.policy.PolicyDtos.ArtifactSource;
import com.dacon.core.policy.PolicyDtos.ArtifactVersion;
import com.dacon.core.policy.PolicyDtos.PolicyArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PolicyTestArtifacts {
  private PolicyTestArtifacts() {}

  static PolicyArtifact fourPolicies(ObjectMapper mapper) {
    Instant now = Instant.parse("2026-08-31T00:00:00Z");
    List<Double> query = vector(0);
    List<ArtifactSource> sources = new ArrayList<>();
    List<ArtifactPolicy> policies = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      String key = "task3-source-" + index;
      sources.add(
          new ArtifactSource(
              key,
              "기관 " + index,
              "https://example.invalid/policy/" + index,
              Character.toString((char) ('a' + index)).repeat(64),
              now));
      ArtifactChunk chunk =
          new ArtifactChunk(
              0,
              "정책 근거 " + index,
              vector(index),
              mapper.createObjectNode().put("supportDetails", "지원 " + index));
      String locatorHash = Character.toString((char) ('a' + index)).repeat(64);
      ArtifactCalculationRule rule =
          index == 0
              ? new ArtifactCalculationRule(
                  "ONE_TIME_FUNDING",
                  1_000_000,
                  null,
                  "section-0",
                  locatorHash,
                  mapper.createObjectNode().put("case", "approved"),
                  now,
                  "task3-reviewer")
              : null;
      policies.add(
          new ArtifactPolicy(
              "task3-policy-" + index,
              "정책 " + index,
              "PURCHASE",
              "요약 " + index,
              "계획 연결 " + index,
              new ArtifactVersion(
                  "2026-" + index,
                  "APPROVED",
                  index == 0 ? "ONE_TIME_FUNDING" : "INFORMATIONAL",
                  null,
                  null,
                  now,
                  key,
                  "section-" + index,
                  locatorHash,
                  List.of(chunk),
                  rule)));
    }
    return new PolicyArtifact(
        "task3-v1",
        "f".repeat(64),
        "nlpai-lab/KURE-v1",
        1024,
        sources,
        policies,
        List.of(new ArtifactQueryProfile("PURCHASE", "내 집 마련", query, List.of())));
  }

  private static List<Double> vector(int secondaryIndex) {
    List<Double> values = new ArrayList<>(Collections.nCopies(1024, 0.0));
    values.set(0, 1.0);
    if (secondaryIndex > 0) {
      values.set(secondaryIndex, secondaryIndex / 10.0);
    }
    return values;
  }
}
