package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("e2e")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.flyway.enabled=false", "app.e2e-token=task3-e2e-token"})
@EnabledIfEnvironmentVariable(named = "REAL_POSTGRES_URL", matches = ".+")
class PolicySearchHttpPostgresTest {
  @LocalServerPort private int port;
  @Autowired private PolicyArtifactImportService importer;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();
  private String accessToken;

  @BeforeEach
  void prepareCatalogAndLogin() throws Exception {
    importer.importArtifact(PolicyTestArtifacts.fourPolicies(mapper));
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/e2e?username=task3-policy-http"))
                .header("X-E2E-Token", "task3-e2e-token")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    accessToken = mapper.readTree(response.body()).path("accessToken").asText();
  }

  @Test
  void authenticatedPublicSearchReturnsTopThreeAndInvalidGoalReturns400() throws Exception {
    HttpResponse<String> success = post("{\"supportGoal\":\"PURCHASE\",\"answers\":[]}", true);
    JsonNode body = mapper.readTree(success.body());
    assertThat(success.statusCode()).isEqualTo(200);
    assertThat(body.path("type").asText()).isEqualTo("RESULTS");
    assertThat(body.path("results").size()).isEqualTo(3);
    assertThat(body.path("results").get(0).path("source").path("locators").get(0).asText())
        .isEqualTo("section-0");

    HttpResponse<String> invalid = post("{\"supportGoal\":\"UNKNOWN\",\"answers\":[]}", true);
    assertThat(invalid.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(invalid.body()).path("code").asText())
        .isEqualTo("VALIDATION_FAILED");

    assertThat(post("{\"supportGoal\":\"PURCHASE\",\"answers\":[]}", false).statusCode())
        .isEqualTo(401);
  }

  @Test
  void largeFiniteParallelEmbeddingRanksFirstOverUnitVectors() throws Exception {
    String large = mapper.writeValueAsString(java.util.Collections.nCopies(1024, 1e150));
    String unit =
        mapper.writeValueAsString(
            java.util.stream.IntStream.range(0, 1024)
                .mapToObj(index -> index == 0 ? 1.0 : 0.0)
                .toList());
    jdbc.update(
        "update policy_query_profiles set embedding=cast(? as jsonb) where support_goal='PURCHASE'",
        large);
    jdbc.update(
        "update policy_chunks c set embedding=cast(? as jsonb) from policy_versions v join policies p on p.id=v.policy_id where c.policy_version_id=v.id and p.policy_key<>'task3-policy-3'",
        unit);
    jdbc.update(
        "update policy_chunks c set embedding=cast(? as jsonb) from policy_versions v join policies p on p.id=v.policy_id where c.policy_version_id=v.id and p.policy_key='task3-policy-3'",
        large);

    JsonNode body =
        mapper.readTree(post("{\"supportGoal\":\"PURCHASE\",\"answers\":[]}", true).body());

    assertThat(body.path("results").get(0).path("title").asText()).isEqualTo("정책 3");
  }

  private HttpResponse<String> post(String body, boolean authenticated) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri("/api/v1/policies/search"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (authenticated) {
      request.header("Authorization", "Bearer " + accessToken);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }
}
