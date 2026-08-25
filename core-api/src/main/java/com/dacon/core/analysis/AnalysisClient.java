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

/** 내부 FastAPI 계산 endpoint를 동기로 호출하고 기술 장애를 503으로 변환한다. */
@Component
public class AnalysisClient implements AnalysisServicePort {
  private static final Duration EXPLANATION_TIMEOUT = Duration.ofSeconds(15);
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String baseUrl;
  private final String internalToken;
  private final Duration timeout;

  /** 내부 endpoint 설정을 검증하고 HTTP client를 구성한다. */
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

  /** 계산 JSON과 request ID를 전달하며 올바른 계산 응답 JSON을 반환한다. */
  @Override
  public JsonNode simulate(String json, String requestId) {
    if (json == null || requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("json and requestId are required");
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(baseUrl + "/internal/simulate"))
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .header("X-Internal-Token", internalToken)
              .header("X-Request-ID", requestId)
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 422) {
        throw inputError(response.body());
      }
      if (response.statusCode() != 200) {
        throw unavailable();
      }
      JsonNode body = objectMapper.readTree(response.body());
      if (!body.isObject()
          || !body.path("simulation").isObject()
          || !body.path("resolvedSpendingFloor").isObject()
          || !body.path("options").isArray()
          || !body.path("percentileBands").isArray()) {
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

  /** 설명 JSON과 request ID를 전달하며 검증된 최종 설명을 반환한다. */
  @Override
  public JsonNode generateExplanation(String json, String requestId) {
    JsonNode body = post("/internal/explanations", json, requestId, EXPLANATION_TIMEOUT, false);
    String status = body.path("status").asText();
    boolean valid =
        body.isObject()
            && ("READY".equals(status) || "FALLBACK".equals(status))
            && body.path("text").isTextual()
            && !body.path("text").asText().isBlank()
            && optionalModel(body)
            && optionalRetryCount(body)
            && optionalStringArray(body, "failedNumbers")
            && optionalText(body, "generatedAt")
            && (!"FALLBACK".equals(status)
                || body.path("model").isMissingNode()
                || body.path("model").isNull())
            && (!"READY".equals(status)
                || body.path("failedNumbers").isMissingNode()
                || body.path("failedNumbers").isEmpty());
    if (!valid) {
      throw unavailable();
    }
    return body;
  }

  /** CUSTOM 계산 JSON과 request ID를 전달하며 저장 가능한 응답 graph를 반환한다. */
  @Override
  public JsonNode customOption(String json, String requestId) {
    JsonNode body = post("/internal/custom-option", json, requestId, timeout, true);
    if (!body.path("resolvedSpendingFloor").isObject()
        || !body.path("option").isObject()
        || !body.path("percentileBands").isArray()) {
      throw unavailable();
    }
    return body;
  }

  private boolean optionalModel(JsonNode body) {
    JsonNode value = body.path("model");
    return value.isMissingNode() || value.isTextual() || value.isNull();
  }

  private boolean optionalRetryCount(JsonNode body) {
    JsonNode value = body.path("retryCount");
    return value.isMissingNode()
        || (value.isIntegralNumber()
            && value.canConvertToInt()
            && value.asInt() >= 0
            && value.asInt() <= 2);
  }

  private boolean optionalStringArray(JsonNode body, String name) {
    JsonNode value = body.path(name);
    if (value.isMissingNode()) {
      return true;
    }
    if (!value.isArray()) {
      return false;
    }
    for (JsonNode item : value) {
      if (!item.isTextual()) {
        return false;
      }
    }
    return true;
  }

  private boolean optionalText(JsonNode body, String name) {
    JsonNode value = body.path(name);
    return value.isMissingNode() || value.isTextual();
  }

  /** 공통 내부 POST 호출을 수행하고 JSON 객체를 반환한다. */
  private JsonNode post(
      String path,
      String json,
      String requestId,
      Duration requestTimeout,
      boolean preserveInputError) {
    if (json == null || requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("json and requestId are required");
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(baseUrl + path))
              .timeout(requestTimeout)
              .header("Content-Type", "application/json")
              .header("X-Internal-Token", internalToken)
              .header("X-Request-ID", requestId)
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (preserveInputError && response.statusCode() == 422) {
        throw customInputError(response.body());
      }
      if (response.statusCode() != 200) {
        throw unavailable();
      }
      JsonNode body = objectMapper.readTree(response.body());
      if (!body.isObject()) {
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

  /** 외부 계산 장애를 나타내는 공개 API 예외를 만든다. */
  private ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CALCULATION_SERVICE_UNAVAILABLE",
        "계산 서비스에 일시적으로 연결할 수 없습니다.");
  }

  private ApiException inputError(String json) {
    try {
      String code = objectMapper.readTree(json).path("code").asText();
      if (java.util.Set.of("INSUFFICIENT_HISTORY", "INVALID_HORIZON", "INVALID_INPUT")
          .contains(code)) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, "계산 입력을 확인해 주세요.");
      }
      return unavailable();
    } catch (IOException exception) {
      return unavailable();
    }
  }

  private ApiException customInputError(String json) {
    try {
      String code = objectMapper.readTree(json).path("code").asText();
      if ("INVALID_INPUT".equals(code)) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, "CUSTOM 금액을 확인해 주세요.");
      }
      if (java.util.Set.of("INSUFFICIENT_HISTORY", "INVALID_HORIZON").contains(code)) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, "계산 입력을 확인해 주세요.");
      }
      return unavailable();
    } catch (IOException exception) {
      return unavailable();
    }
  }
}
