package com.dacon.core.plan;

import com.dacon.core.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * FastAPI 계산 graph의 타입, DB 수치 범위, 옵션·월 조합의 완전성을 저장 전에 검증한다.
 *
 * <p>수치를 다시 계산하지 않고 내부 API가 반환한 확정값이 저장 계약을 만족하는지만 판정한다.
 */
final class CalculationResponseValidator {
  /** 인스턴스 생성을 막는다. */
  private CalculationResponseValidator() {}

  /**
   * PRESET 3개와 각 계산 월의 분위수 밴드를 포함한 전체 계산 응답을 검증한다.
   *
   * @param body 내부 계산 API 응답 JSON
   * @param horizonMonths 입력으로 확정된 계산 개월 수
   * @throws ApiException 구조, 타입, 범위, PRESET 수준 또는 밴드 조합이 계약과 다르면 503 오류
   */
  static void validate(JsonNode body, int horizonMonths) {
    JsonNode simulation = body.path("simulation");
    JsonNode options = body.path("options");
    JsonNode bands = body.path("percentileBands");
    boolean valid =
        simulation.isObject()
            && "IID_BOOTSTRAP".equals(simulation.path("method").asText())
            && simulation.path("nPaths").asInt() == 10_000
            && simulation.path("randomSeed").canConvertToLong()
            && simulation.path("inputHash").asText().matches("^[0-9a-f]{64}$")
            && simulation.path("engineVersion").isTextual()
            && nonEmptyObject(simulation.path("inputSnapshot"))
            && nonEmptyObject(simulation.path("resultSummary"))
            && options.isArray()
            && !options.isEmpty()
            && bands.isArray()
            && bands.size() == options.size() * horizonMonths;
    Set<String> positions = new HashSet<>();
    Set<BigDecimal> nominalLevels = new HashSet<>();
    for (JsonNode option : options) {
      valid &=
          option.isObject()
              && "PRESET".equals(option.path("optionType").asText())
              && option.path("nominalLevel").isNumber()
              && fitsNumeric(option.path("nominalLevel"), 4, 3)
              && between(option.path("nominalLevel"), BigDecimal.ZERO, BigDecimal.ONE, false)
              && nonNegativeLong(option.path("recommendedMonthlySpending"))
              && option.path("requiredReductionRate").isNumber()
              && fitsNumeric(option.path("requiredReductionRate"), 23, 4)
              && option.path("requiredReductionRate").decimalValue().compareTo(BigDecimal.ONE) <= 0
              && between(option.path("simulationCoverage"), BigDecimal.ZERO, BigDecimal.ONE, true)
              && fitsNumeric(option.path("simulationCoverage"), 5, 4)
              && between(
                  option.path("historicalFeasibilityRatio"), BigDecimal.ZERO, BigDecimal.ONE, true)
              && fitsNumeric(option.path("historicalFeasibilityRatio"), 5, 4)
              && option.path("aggressiveWarning").isBoolean()
              && option.path("targetCoverageMet").isBoolean();
      if (option.path("nominalLevel").isNumber()) {
        nominalLevels.add(option.path("nominalLevel").decimalValue().stripTrailingZeros());
      }
    }
    valid &=
        options.size() == 3
            && nominalLevels.equals(
                Set.of(
                    new BigDecimal("0.70").stripTrailingZeros(),
                    new BigDecimal("0.80").stripTrailingZeros(),
                    new BigDecimal("0.90").stripTrailingZeros()));
    for (JsonNode band : bands) {
      int optionIndex = band.path("optionIndex").asInt(-1);
      int monthIndex = band.path("monthIndex").asInt(-1);
      valid &=
          optionIndex >= 0
              && optionIndex < options.size()
              && monthIndex >= 1
              && monthIndex <= horizonMonths
              && "CUMULATIVE_SAVINGS".equals(band.path("metricType").asText())
              && monotonic(band)
              && positions.add(optionIndex + ":" + monthIndex);
    }
    if (!valid) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CALCULATION_SERVICE_UNAVAILABLE",
          "계산 서비스 응답을 확인할 수 없습니다.");
    }
  }

  /**
   * 단일 CUSTOM 옵션과 각 계산 월의 분위수 밴드를 포함한 응답을 검증한다.
   *
   * @param body 내부 CUSTOM 계산 API 응답 JSON
   * @param horizonMonths 원래 계획 입력에 저장된 계산 개월 수
   * @throws ApiException 구조, 타입, 범위 또는 밴드 조합이 계약과 다르면 503 오류
   */
  static void validateCustom(JsonNode body, int horizonMonths) {
    JsonNode option = body.path("option");
    JsonNode bands = body.path("percentileBands");
    boolean valid =
        option.isObject()
            && "CUSTOM".equals(option.path("optionType").asText())
            && option.path("nominalLevel").isNull()
            && validOption(option)
            && bands.isArray()
            && bands.size() == horizonMonths;
    Set<String> positions = new HashSet<>();
    for (JsonNode band : bands) {
      int optionIndex = band.path("optionIndex").asInt(-1);
      int monthIndex = band.path("monthIndex").asInt(-1);
      valid &=
          optionIndex == 0
              && monthIndex >= 1
              && monthIndex <= horizonMonths
              && "CUMULATIVE_SAVINGS".equals(band.path("metricType").asText())
              && monotonic(band)
              && positions.add("0:" + monthIndex);
    }
    if (!valid) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CALCULATION_SERVICE_UNAVAILABLE",
          "계산 서비스 응답을 확인할 수 없습니다.");
    }
  }

  /** CUSTOM과 PRESET에 공통인 금액·비율·불리언 필드를 DB 저장 범위로 검사한다. */
  private static boolean validOption(JsonNode option) {
    return nonNegativeLong(option.path("recommendedMonthlySpending"))
        && option.path("requiredReductionRate").isNumber()
        && fitsNumeric(option.path("requiredReductionRate"), 23, 4)
        && option.path("requiredReductionRate").decimalValue().compareTo(BigDecimal.ONE) <= 0
        && between(option.path("simulationCoverage"), BigDecimal.ZERO, BigDecimal.ONE, true)
        && fitsNumeric(option.path("simulationCoverage"), 5, 4)
        && between(option.path("historicalFeasibilityRatio"), BigDecimal.ZERO, BigDecimal.ONE, true)
        && fitsNumeric(option.path("historicalFeasibilityRatio"), 5, 4)
        && option.path("aggressiveWarning").isBoolean()
        && option.path("targetCoverageMet").isBoolean();
  }

  private static boolean nonEmptyObject(JsonNode node) {
    return node.isObject() && !node.isEmpty();
  }

  private static boolean nonNegativeLong(JsonNode node) {
    return node.isIntegralNumber() && node.canConvertToLong() && node.asLong() >= 0;
  }

  /** 숫자 노드가 지정 구간의 내부 또는 경계 안에 있는지 판정한다. */
  private static boolean between(
      JsonNode node, BigDecimal minimum, BigDecimal maximum, boolean inclusive) {
    if (!node.isNumber()) {
      return false;
    }
    int lower = node.decimalValue().compareTo(minimum);
    int upper = node.decimalValue().compareTo(maximum);
    return inclusive ? lower >= 0 && upper <= 0 : lower > 0 && upper < 0;
  }

  /** JSON 숫자가 PostgreSQL {@code NUMERIC(precision, scale)}에 손실 없이 들어가는지 판정한다. */
  private static boolean fitsNumeric(JsonNode node, int precision, int scale) {
    if (!node.isNumber()) {
      return false;
    }
    BigDecimal value = node.decimalValue().stripTrailingZeros();
    int integerDigits = Math.max(0, value.precision() - value.scale());
    return value.scale() <= scale && integerDigits <= precision - scale;
  }

  /** 분위수 값이 모두 long 범위 정수이며 p10부터 p90까지 비감소하는지 판정한다. */
  private static boolean monotonic(JsonNode band) {
    JsonNode p10 = band.path("p10");
    JsonNode p25 = band.path("p25");
    JsonNode p50 = band.path("p50");
    JsonNode p75 = band.path("p75");
    JsonNode p90 = band.path("p90");
    return p10.isIntegralNumber()
        && p10.canConvertToLong()
        && p25.isIntegralNumber()
        && p25.canConvertToLong()
        && p50.isIntegralNumber()
        && p50.canConvertToLong()
        && p75.isIntegralNumber()
        && p75.canConvertToLong()
        && p90.isIntegralNumber()
        && p90.canConvertToLong()
        && p10.asLong() <= p25.asLong()
        && p25.asLong() <= p50.asLong()
        && p50.asLong() <= p75.asLong()
        && p75.asLong() <= p90.asLong();
  }
}
