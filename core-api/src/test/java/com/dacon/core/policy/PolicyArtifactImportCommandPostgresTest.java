package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = PolicyArtifactImportCommand.CommandConfiguration.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.flyway.enabled=false")
class PolicyArtifactImportCommandPostgresTest
    extends com.dacon.core.PostgresIntegrationTestSupport {
  private static final Path ARTIFACT = approvedCommandArtifact();

  @Autowired private EntityManager entityManager;
  @Autowired private PolicyArtifactImportCommand command;

  @DynamicPropertySource
  static void artifactPath(DynamicPropertyRegistry registry) {
    registry.add("app.policy-artifact-path", ARTIFACT::toString);
  }

  @Test
  void nonWebSpringCommandImportsCanonicalFileAndIsIdempotent() throws Exception {
    Object[] snapshot =
        (Object[])
            entityManager
                .createNativeQuery(
                    "select id,status from policy_index_snapshots where artifact_version='task3-command-v1'")
                .getSingleResult();
    assertThat(snapshot[1]).isEqualTo("ACTIVE");
    assertThat(
            ((Number)
                    entityManager
                        .createNativeQuery(
                            "select count(*) from policy_snapshot_versions where snapshot_id=:id")
                        .setParameter("id", snapshot[0])
                        .getSingleResult())
                .longValue())
        .isEqualTo(4);
    command.run(new org.springframework.boot.DefaultApplicationArguments());
    assertThat(
            ((Number)
                    entityManager
                        .createNativeQuery(
                            "select count(*) from policy_index_snapshots where artifact_version='task3-command-v1'")
                        .getSingleResult())
                .longValue())
        .isEqualTo(1);
  }

  private static Path approvedCommandArtifact() {
    try {
      ObjectMapper mapper = new ObjectMapper();
      ObjectNode root = (ObjectNode) mapper.readTree(PolicyTestArtifacts.fourPolicies(mapper));
      root.put("artifactVersion", "task3-command-v1");
      Path path = Files.createTempFile("task3-approved-policy-", ".json");
      Files.write(path, PolicyTestArtifacts.canonicalBytes(mapper, root));
      path.toFile().deleteOnExit();
      return path;
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
