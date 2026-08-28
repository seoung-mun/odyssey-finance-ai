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

/**
 * 내부 FastAPI 계산·설명 endpoint를 동기로 호출하는 HTTP 어댑터다.
 *
 * <p>내부 토큰과 요청 식별자를 전달하고, HTTP·파싱·응답 구조 오류는 공개 API의 503 오류로 변환한다. 이 타입은 DB 트랜잭션이나 영속 상태를 소유하지 않는다.
 */
@Component
public class AnalysisClient implements AnalysisServicePort {
  private static final Duration EXPLANATION_TIMEOUT = Duration.ofSeconds(15);
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String baseUrl;
  private final String internalToken;
  private final Duration timeout;

  /**
   * 주입된 HTTP 클라이언트와 내부 서비스 접속 설정을 보관한다.
   *
   * @param httpClient 동기 내부 호출에 사용할 클라이언트
   * @param baseUrl 내부 FastAPI 기준 URL
   * @param internalToken {@code X-Internal-Token} 헤더 값
   * @param timeout 계산 및 CUSTOM 호출 제한 시간
   * @throws IllegalArgumentException 내부 토큰이 공백인 경우
   */
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

  /**
   * 계산 요청을 {@code /internal/simulate}에 전송하고 최상위 응답 구조를 확인한다.
   *
   * @param json 내부 계산 API 계약을 따르는 JSON 문자열
   * @param requestId {@code X-Request-ID}로 전달할 비어 있지 않은 식별자
   * @return 시뮬레이션, 지출 하한, 선택지와 분위수 밴드를 포함한 JSON 객체
   * @throws IllegalArgumentException 입력 JSON 또는 요청 식별자가 유효하지 않은 경우
   * @throws ApiException 알려진 422 입력 오류 또는 그 밖의 호출·응답 오류가 발생한 경우
   */
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

  /**
   * 설명 요청을 15초 제한으로 전송하고 최종 상태와 선택 필드의 타입을 확인한다.
   *
   * @param json 허용 숫자와 확정 계획을 담은 JSON 문자열
   * @param requestId {@code X-Request-ID}로 전달할 비어 있지 않은 식별자
   * @return {@code READY} 또는 {@code FALLBACK} 상태의 설명 JSON 객체
   * @throws IllegalArgumentException 입력 JSON 또는 요청 식별자가 유효하지 않은 경우
   * @throws ApiException 호출 실패 또는 최종 설명 계약 위반이 발생한 경우
   */
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

  /**
   * CUSTOM 선택지 요청을 전송하고 저장에 필요한 최상위 응답 구조를 확인한다.
   *
   * @param json 사용자 지정 월 지출액을 적용한 계산 요청 JSON
   * @param requestId {@code X-Request-ID}로 전달할 비어 있지 않은 식별자
   * @return 지출 하한, 단일 CUSTOM 선택지와 분위수 밴드를 포함한 JSON 객체
   * @throws IllegalArgumentException 입력 JSON 또는 요청 식별자가 유효하지 않은 경우
   * @throws ApiException 422 입력 오류, 호출 실패 또는 응답 구조 위반이 발생한 경우
   */
  @Override
  public JsonNode customOption(String json, String requestId) {
    JsonNode body = post("/internal/custom-option", json, requestId, timeout, true);
    if (!body.path("option").isObject() || !body.path("percentileBands").isArray()) {
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

  /**
   * 내부 POST 요청에 인증·추적 헤더와 제한 시간을 적용하고 JSON 객체 응답만 허용한다.
   *
   * @param path 기준 URL 뒤에 붙일 내부 API 경로
   * @param json 요청 본문
   * @param requestId 서비스 간 요청 식별자
   * @param requestTimeout 이 호출에 적용할 제한 시간
   * @param preserveInputError CUSTOM 입력 오류를 422로 보존할지 여부
   * @return 파싱된 JSON 객체
   * @throws IllegalArgumentException 필수 요청 값이 유효하지 않은 경우
   * @throws ApiException 내부 서비스 오류나 계약 위반 응답이 발생한 경우
   */
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

  /**
   * 내부 장애를 외부에 노출하지 않는 503 예외로 정규화한다.
   *
   * @return 코드가 {@code CALCULATION_SERVICE_UNAVAILABLE}인 공개 API 예외
   */
  private ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CALCULATION_SERVICE_UNAVAILABLE",
        "계산 서비스에 일시적으로 연결할 수 없습니다.");
  }

  /**
   * 일반 계산 API의 422 본문에서 공개 가능한 안정 코드만 보존한다.
   *
   * @param json 내부 오류 응답 본문
   * @return 허용된 입력 오류 또는 본문을 신뢰할 수 없을 때의 503 예외
   */
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

  /**
   * CUSTOM 계산의 422 오류를 입력 종류에 맞는 공개 상태 코드로 변환한다.
   *
   * @param json 내부 오류 응답 본문
   * @return CUSTOM 금액 오류는 422, 이력·기간 오류는 400, 나머지는 503인 예외
   */
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
