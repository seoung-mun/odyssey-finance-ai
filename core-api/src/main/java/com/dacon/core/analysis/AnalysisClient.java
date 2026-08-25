package com.dacon.core.analysis;

import com.dacon.core.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AnalysisClient {
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String baseUrl;
  private final String internalToken;
  private final Duration timeout;

  public AnalysisClient(
      HttpClient httpClient,
      @Value("${app.analysis-base-url}") String baseUrl,
      @Value("${app.analysis-token}") String internalToken,
      @Value("${app.analysis-timeout}") Duration timeout) {
    this.httpClient = httpClient;
    this.baseUrl = baseUrl;
    if (internalToken.isBlank()) {
      throw new IllegalArgumentException("ANALYSIS_INTERNAL_TOKEN is required");
    }
    this.internalToken = internalToken;
    this.timeout = timeout;
  }

  public JsonNode simulate(String json) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create(baseUrl + "/internal/simulate"))
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .header("X-Internal-Token", internalToken)
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw unavailable();
      }
      var body = objectMapper.readTree(response.body());
      if (!body.hasNonNull("simulation") || !body.has("options") || !body.has("percentileBands")) {
        throw unavailable();
      }
      return body;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (IOException | IllegalArgumentException exception) {
      throw unavailable();
    }
  }

  private ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CALCULATION_SERVICE_UNAVAILABLE",
        "계산 서비스에 일시적으로 연결할 수 없습니다.");
  }
}
