package com.dacon.core.plan;

import com.dacon.core.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** FastAPI 계산 graph의 타입·범위·완전성을 저장 전에 검증한다. */
final class CalculationResponseValidator {
  private CalculationResponseValidator() {}

  static void validate(JsonNode body, int horizonMonths) {
    JsonNode simulation = body.path("simulation");
    JsonNode floor = body.path("resolvedSpendingFloor");
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
            && floor.isObject()
            && Set.of("OFF", "AUTO", "CUSTOM").contains(floor.path("mode").asText())
            && nonNegativeLong(floor.path("requestedMonthlyAmount"))
            && nonNegativeLong(floor.path("effectiveMonthlyAmount"))
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
              && between(
                  option.path("effectiveMaxReductionRate"), BigDecimal.ZERO, BigDecimal.ONE, true)
              && fitsNumeric(option.path("effectiveMaxReductionRate"), 5, 4)
              && option.path("floorApplied").isBoolean()
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

  static void validateCustom(JsonNode body, int horizonMonths) {
    JsonNode floor = body.path("resolvedSpendingFloor");
    JsonNode option = body.path("option");
    JsonNode bands = body.path("percentileBands");
    boolean valid =
        floor.isObject()
            && Set.of("OFF", "AUTO", "CUSTOM").contains(floor.path("mode").asText())
            && nonNegativeLong(floor.path("requestedMonthlyAmount"))
            && nonNegativeLong(floor.path("effectiveMonthlyAmount"))
            && option.isObject()
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
        && between(option.path("effectiveMaxReductionRate"), BigDecimal.ZERO, BigDecimal.ONE, true)
        && fitsNumeric(option.path("effectiveMaxReductionRate"), 5, 4)
        && option.path("floorApplied").isBoolean()
        && option.path("targetCoverageMet").isBoolean();
  }

  private static boolean nonEmptyObject(JsonNode node) {
    return node.isObject() && !node.isEmpty();
  }

  private static boolean nonNegativeLong(JsonNode node) {
    return node.isIntegralNumber() && node.canConvertToLong() && node.asLong() >= 0;
  }

  private static boolean between(
      JsonNode node, BigDecimal minimum, BigDecimal maximum, boolean inclusive) {
    if (!node.isNumber()) {
      return false;
    }
    int lower = node.decimalValue().compareTo(minimum);
    int upper = node.decimalValue().compareTo(maximum);
    return inclusive ? lower >= 0 && upper <= 0 : lower > 0 && upper < 0;
  }

  private static boolean fitsNumeric(JsonNode node, int precision, int scale) {
    if (!node.isNumber()) {
      return false;
    }
    BigDecimal value = node.decimalValue().stripTrailingZeros();
    int integerDigits = Math.max(0, value.precision() - value.scale());
    return value.scale() <= scale && integerDigits <= precision - scale;
  }

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
