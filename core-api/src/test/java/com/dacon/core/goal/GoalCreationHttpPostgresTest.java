package com.dacon.core.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("e2e")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.flyway.enabled=false", "app.e2e-token=task2-e2e-token"})
class GoalCreationHttpPostgresTest extends com.dacon.core.PostgresIntegrationTestSupport {
  @LocalServerPort private int port;
  @Autowired private ObjectMapper mapper;

  private final HttpClient http = HttpClient.newHttpClient();
  private String accessToken;

  @BeforeEach
  void login() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(uri("/api/v1/auth/e2e?username=task2-http"))
            .header("X-E2E-Token", "task2-e2e-token")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    accessToken = mapper.readTree(response.body()).path("accessToken").asText();
  }

  @Test
  void goalCreationRejectsMissingAndNullTargetDateOverHttp() throws Exception {
    assertValidationFailure("/api/v1/goals", "{\"name\":\"goal\",\"targetAmount\":1000}");
    assertValidationFailure(
        "/api/v1/goals", "{\"name\":\"goal\",\"targetAmount\":1000,\"targetDate\":null}");
  }

  @Test
  void scheduledExpenseCreationRejectsMissingAndNullDateOverHttp() throws Exception {
    assertValidationFailure("/api/v1/scheduled-expenses", "{\"name\":\"rent\",\"amount\":1000}");
    assertValidationFailure(
        "/api/v1/scheduled-expenses", "{\"name\":\"rent\",\"amount\":1000,\"scheduledDate\":null}");
  }

  private void assertValidationFailure(String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode problem = mapper.readTree(response.body());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(problem.path("code").asText()).isEqualTo("VALIDATION_FAILED");
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }
}
