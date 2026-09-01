package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
@EnabledIfEnvironmentVariable(named = "REAL_POSTGRES_URL", matches = ".+")
class PolicyArtifactImportPostgresTest {
  @Autowired private PolicyArtifactImportService importer;
  @Autowired private PolicySearchService search;
  @Autowired private EntityManager entityManager;

  @Test
  void repeatedArtifactIdentityReturnsSameSnapshotAndSearchesTopThreeWithProvenance() {
    byte[] canonical = PolicyTestArtifacts.fourPolicies(new ObjectMapper());
    byte[] artifact = Arrays.copyOf(canonical, canonical.length + 1);
    artifact[artifact.length - 1] = '\n';
    long retrievalsBefore = count("policy_retrieval_runs");

    long first = importer.importArtifact(artifact);
    long second = importer.importArtifact(artifact);

    assertThat(second).isEqualTo(first);
    PolicyDtos.PolicyResultsResponse result =
        (PolicyDtos.PolicyResultsResponse)
            search.search(new PolicyDtos.PolicySearchRequest("PURCHASE", List.of()));
    assertThat(result.results()).hasSize(3);
    assertThat(result.results().getFirst().source().locators()).containsExactly("section-0");
    assertThat(result.results())
        .allMatch(item -> item.eligibilityStatus().equals("NEEDS_CONFIRMATION"));
    assertThat(result.results().getFirst().calculationMode()).isEqualTo("ONE_TIME_FUNDING");
    assertThat(result.results().subList(1, 3))
        .allMatch(item -> item.calculationMode().equals("INFORMATIONAL"));
    assertThat(result.results().get(1).applicationPeriod()).isEqualTo("공식 페이지 확인");
    assertThat(count("policy_retrieval_runs")).isEqualTo(retrievalsBefore + 1);
    assertThat(
            entityManager
                .createNativeQuery(
                    "select top_version_ids::text from policy_retrieval_runs order by id desc limit 1")
                .getSingleResult()
                .toString())
        .doesNotContain("answer", "amount", "user");
  }

  @Test
  void conflictingManifestDoesNotReplaceTheActiveSnapshot() {
    ObjectMapper mapper = new ObjectMapper();
    byte[] artifact = PolicyTestArtifacts.fourPolicies(mapper);
    long active = importer.importArtifact(artifact);
    ObjectNode conflict = read(mapper, artifact);
    conflict.put("embeddingModel", "different-model");
    byte[] conflictingArtifact = PolicyTestArtifacts.canonicalBytes(mapper, conflict);

    assertThatThrownBy(() -> importer.importArtifact(conflictingArtifact))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            ((Number)
                    entityManager
                        .createNativeQuery(
                            "select id from policy_index_snapshots where status='ACTIVE'")
                        .getSingleResult())
                .longValue())
        .isEqualTo(active);
  }

  @Test
  void databaseFailureRollsBackPartialRowsAndKeepsTheActiveSnapshot() {
    ObjectMapper mapper = new ObjectMapper();
    byte[] activeArtifact = PolicyTestArtifacts.fourPolicies(mapper);
    long active = importer.importArtifact(activeArtifact);
    ObjectNode failing = read(mapper, activeArtifact);
    failing.put("artifactVersion", "task3-failing-v1");
    ObjectNode version = (ObjectNode) failing.path("policies").get(0).path("version");
    version.put("calculationMode", "INFORMATIONAL");
    version.putNull("calculationRule");
    byte[] failingArtifact = PolicyTestArtifacts.canonicalBytes(mapper, failing);

    assertThatThrownBy(() -> importer.importArtifact(failingArtifact))
        .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    assertThat(countWhere("policy_index_snapshots", "artifact_version", "task3-failing-v1"))
        .isZero();
    assertThat(
            ((Number)
                    entityManager
                        .createNativeQuery(
                            "select id from policy_index_snapshots where status='ACTIVE'")
                        .getSingleResult())
                .longValue())
        .isEqualTo(active);
  }

  @Test
  void rejectsWrongDimensionAndNonFiniteEmbeddingBeforeAnySnapshotWrite() {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode wrongDimension = read(mapper, PolicyTestArtifacts.fourPolicies(mapper));
    wrongDimension.put("artifactVersion", "task3-wrong-dimension");
    wrongDimension.put("embeddingDimension", 1023);
    byte[] artifact = PolicyTestArtifacts.canonicalBytes(mapper, wrongDimension);

    assertThatThrownBy(() -> importer.importArtifact(artifact))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(countWhere("policy_index_snapshots", "artifact_version", "task3-wrong-dimension"))
        .isZero();
  }

  @Test
  void concurrentFirstImportReturnsOneSnapshotWithoutPartialMembership() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode concurrent = read(mapper, PolicyTestArtifacts.fourPolicies(mapper));
    concurrent.put("artifactVersion", "task3-concurrent-v1");
    byte[] artifact = PolicyTestArtifacts.canonicalBytes(mapper, concurrent);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Long> importCall =
          () -> {
            ready.countDown();
            start.await();
            return importer.importArtifact(artifact);
          };
      Future<Long> first = executor.submit(importCall);
      Future<Long> second = executor.submit(importCall);
      ready.await();
      start.countDown();

      assertThat(second.get()).isEqualTo(first.get());
    }
    assertThat(countWhere("policy_index_snapshots", "artifact_version", "task3-concurrent-v1"))
        .isEqualTo(1);
    assertThat(
            ((Number)
                    entityManager
                        .createNativeQuery(
                            "select count(*) from policy_snapshot_versions sv join policy_index_snapshots s on s.id=sv.snapshot_id where s.artifact_version=:version")
                        .setParameter("version", "task3-concurrent-v1")
                        .getSingleResult())
                .longValue())
        .isEqualTo(4);
    assertThat(countWhere("policy_index_snapshots", "status", "ACTIVE")).isEqualTo(1);
  }

  @Test
  void rawManifestReviewGateAndProvenanceTamperingWriteNothing() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    byte[] approved = PolicyTestArtifacts.fourPolicies(mapper);
    long before = count("policy_index_snapshots");

    ObjectNode stale = read(mapper, approved);
    stale.put("artifactVersion", "stale-without-rehash");
    rejectWithoutSnapshot(mapper.writeValueAsBytes(stale), before);

    ObjectNode pending = read(mapper, approved);
    ((ObjectNode) pending.path("reviewGate")).put("status", "PENDING").put("importable", false);
    rejectWithoutSnapshot(PolicyTestArtifacts.canonicalBytes(mapper, pending), before);

    ObjectNode locator = read(mapper, approved);
    ((ObjectNode) locator.path("policies").get(0).path("version"))
        .put("sourceLocator", "tampered-locator");
    rejectWithoutSnapshot(PolicyTestArtifacts.canonicalBytes(mapper, locator), before);

    ObjectNode source = read(mapper, approved);
    ((ObjectNode) source.path("sources").get(0)).put("contentSha256", "bad");
    rejectWithoutSnapshot(PolicyTestArtifacts.canonicalBytes(mapper, source), before);

    ObjectNode rule = read(mapper, approved);
    ((ObjectNode) rule.path("policies").get(0).path("version").path("calculationRule"))
        .put("approvedLocator", "other");
    rejectWithoutSnapshot(PolicyTestArtifacts.canonicalBytes(mapper, rule), before);

    ObjectNode security = read(mapper, approved);
    security.put("secret", "must-not-enter");
    rejectWithoutSnapshot(PolicyTestArtifacts.canonicalBytes(mapper, security), before);

    byte[] officialPending =
        Files.readAllBytes(Path.of("../data/policy/policy-artifact-candidate.json"));
    rejectWithoutSnapshot(officialPending, before);
  }

  @Test
  void rejectsSecurityKeySeparatorAndCompatibilityVariantsButKeepsNormalMetadata() {
    ObjectMapper mapper = new ObjectMapper();
    long before = count("policy_index_snapshots");
    List<String> variants = List.of("api-key", "API.KEY", "api key", "api/key", "ａｐｉ－ｋｅｙ");
    for (int index = 0; index < variants.size(); index++) {
      ObjectNode artifact = read(mapper, PolicyTestArtifacts.fourPolicies(mapper));
      artifact.put("artifactVersion", "task3-security-variant-" + index);
      ((ObjectNode)
              artifact
                  .path("policies")
                  .get(0)
                  .path("version")
                  .path("chunks")
                  .get(0)
                  .path("metadata"))
          .put(variants.get(index), "must-not-persist");

      rejectWithoutSnapshot(PolicyTestArtifacts.canonicalBytes(mapper, artifact), before);
    }

    ObjectNode normal = read(mapper, PolicyTestArtifacts.fourPolicies(mapper));
    normal.put("artifactVersion", "task3-normal-metadata-v1");
    importer.importArtifact(PolicyTestArtifacts.canonicalBytes(mapper, normal));

    assertThat(
            entityManager
                .createNativeQuery(
                    "select metadata->>'supportDetails' from policy_chunks where metadata->>'supportDetails' is not null limit 1")
                .getSingleResult())
        .isEqualTo("지원 0");
  }

  private void rejectWithoutSnapshot(byte[] artifact, long expectedCount) {
    assertThatThrownBy(() -> importer.importArtifact(artifact))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(count("policy_index_snapshots")).isEqualTo(expectedCount);
  }

  private ObjectNode read(ObjectMapper mapper, byte[] artifact) {
    try {
      return (ObjectNode) mapper.readTree(artifact);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private long count(String table) {
    return ((Number)
            entityManager.createNativeQuery("select count(*) from " + table).getSingleResult())
        .longValue();
  }

  private long countWhere(String table, String column, String value) {
    return ((Number)
            entityManager
                .createNativeQuery("select count(*) from " + table + " where " + column + "=:value")
                .setParameter("value", value)
                .getSingleResult())
        .longValue();
  }
}
