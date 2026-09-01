package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dacon.core.policy.PolicyDtos.PolicyArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
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
    PolicyArtifact artifact = PolicyTestArtifacts.fourPolicies(new ObjectMapper());
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
    PolicyArtifact artifact = PolicyTestArtifacts.fourPolicies(new ObjectMapper());
    long active = importer.importArtifact(artifact);
    PolicyArtifact conflict =
        new PolicyArtifact(
            artifact.artifactVersion(),
            "e".repeat(64),
            artifact.embeddingModel(),
            artifact.embeddingDimension(),
            artifact.sources(),
            artifact.policies(),
            artifact.queryProfiles());

    assertThatThrownBy(() -> importer.importArtifact(conflict))
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
    PolicyArtifact activeArtifact = PolicyTestArtifacts.fourPolicies(mapper);
    long active = importer.importArtifact(activeArtifact);
    List<PolicyDtos.ArtifactSource> sources = new ArrayList<>(activeArtifact.sources());
    sources.add(
        new PolicyDtos.ArtifactSource(
            "task3-invalid-source",
            "잘못된 기관",
            "https://example.invalid/bad",
            "bad-hash",
            java.time.Instant.parse("2026-08-31T00:00:00Z")));
    PolicyArtifact failing =
        new PolicyArtifact(
            "task3-failing-v1",
            "d".repeat(64),
            activeArtifact.embeddingModel(),
            activeArtifact.embeddingDimension(),
            sources,
            activeArtifact.policies(),
            activeArtifact.queryProfiles());

    assertThatThrownBy(() -> importer.importArtifact(failing))
        .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    assertThat(countWhere("policy_index_snapshots", "artifact_version", "task3-failing-v1"))
        .isZero();
    assertThat(countWhere("policy_sources", "source_key", "task3-invalid-source")).isZero();
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
    PolicyArtifact artifact = PolicyTestArtifacts.fourPolicies(new ObjectMapper());
    PolicyArtifact wrongDimension =
        new PolicyArtifact(
            "task3-wrong-dimension",
            "c".repeat(64),
            artifact.embeddingModel(),
            1023,
            artifact.sources(),
            artifact.policies(),
            artifact.queryProfiles());

    assertThatThrownBy(() -> importer.importArtifact(wrongDimension))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(countWhere("policy_index_snapshots", "artifact_version", "task3-wrong-dimension"))
        .isZero();
  }

  @Test
  void concurrentFirstImportReturnsOneSnapshotWithoutPartialMembership() throws Exception {
    PolicyArtifact base = PolicyTestArtifacts.fourPolicies(new ObjectMapper());
    PolicyArtifact artifact =
        new PolicyArtifact(
            "task3-concurrent-v1",
            "b".repeat(64),
            base.embeddingModel(),
            base.embeddingDimension(),
            base.sources(),
            base.policies(),
            base.queryProfiles());
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
    assertThat(countWhere("policy_index_snapshots", "artifact_version", artifact.artifactVersion()))
        .isEqualTo(1);
    assertThat(
            ((Number)
                    entityManager
                        .createNativeQuery(
                            "select count(*) from policy_snapshot_versions sv join policy_index_snapshots s on s.id=sv.snapshot_id where s.artifact_version=:version")
                        .setParameter("version", artifact.artifactVersion())
                        .getSingleResult())
                .longValue())
        .isEqualTo(4);
    assertThat(countWhere("policy_index_snapshots", "status", "ACTIVE")).isEqualTo(1);
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
