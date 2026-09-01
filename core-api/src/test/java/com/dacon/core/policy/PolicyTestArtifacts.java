package com.dacon.core.policy;

import com.dacon.core.policy.PolicyDtos.ArtifactCalculationRule;
import com.dacon.core.policy.PolicyDtos.ArtifactChunk;
import com.dacon.core.policy.PolicyDtos.ArtifactPolicy;
import com.dacon.core.policy.PolicyDtos.ArtifactQueryProfile;
import com.dacon.core.policy.PolicyDtos.ArtifactReviewGate;
import com.dacon.core.policy.PolicyDtos.ArtifactSource;
import com.dacon.core.policy.PolicyDtos.ArtifactToolchain;
import com.dacon.core.policy.PolicyDtos.ArtifactVersion;
import com.dacon.core.policy.PolicyDtos.PolicyArtifact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class PolicyTestArtifacts {
  private PolicyTestArtifacts() {}

  static byte[] fourPolicies(ObjectMapper mapper) {
    return canonicalBytes(mapper, approvedArtifact(mapper));
  }

  static PolicyArtifact approvedArtifact(ObjectMapper mapper) {
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
              now,
              List.of("정책 " + index),
              "text/html",
              "https://example.invalid/policy/" + index,
              200,
              "d".repeat(64)));
      ArtifactChunk chunk =
          new ArtifactChunk(
              0,
              "정책 근거 " + index,
              vector(index),
              mapper.createObjectNode().put("supportDetails", "지원 " + index));
      String locatorHash = sha256("section-" + index);
      ArtifactCalculationRule rule =
          index == 0
              ? new ArtifactCalculationRule(
                  "ONE_TIME_FUNDING",
                  1_000_000,
                  null,
                  "2026-0",
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
        List.of(new ArtifactQueryProfile("PURCHASE", "내 집 마련", query, List.of())),
        new ArtifactReviewGate("APPROVED", true, "task3 QA approved fixture"),
        new ArtifactToolchain(
            "cpu",
            "test",
            "little",
            "test",
            "test",
            "21",
            "test",
            "test",
            "test",
            "test",
            "test",
            "test",
            "a".repeat(64),
            "test"));
  }

  static byte[] canonicalBytes(ObjectMapper mapper, PolicyArtifact artifact) {
    ObjectMapper artifactMapper = new ObjectMapper().findAndRegisterModules();
    return canonicalBytes(artifactMapper, artifactMapper.valueToTree(artifact));
  }

  static byte[] canonicalBytes(ObjectMapper mapper, JsonNode artifact) {
    try {
      ObjectNode root = ((ObjectNode) artifact).deepCopy();
      ObjectMapper canonicalMapper = new ObjectMapper();
      root =
          (ObjectNode)
              canonicalMapper.readTree(canonicalMapper.writeValueAsBytes(canonical(mapper, root)));
      root.put("manifestSha256", "");
      String manifest =
          java.util.HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(canonicalMapper.writeValueAsBytes(canonical(mapper, root))));
      root.put("manifestSha256", manifest);
      return canonicalMapper.writeValueAsBytes(canonical(mapper, root));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static JsonNode canonical(ObjectMapper mapper, JsonNode node) {
    if (node.isObject()) {
      ObjectNode sorted = mapper.createObjectNode();
      Map<String, JsonNode> fields = new TreeMap<>();
      node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
      fields.forEach((key, value) -> sorted.set(key, canonical(mapper, value)));
      return sorted;
    }
    if (node.isArray()) {
      ArrayNode array = mapper.createArrayNode();
      node.forEach(value -> array.add(canonical(mapper, value)));
      return array;
    }
    return node;
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
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
