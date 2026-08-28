package com.dacon.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dacon.core.analysis.AnalysisServicePort;
import com.dacon.core.error.ApiException;
import com.dacon.core.explanation.ExplanationQueuePort;
import com.dacon.core.plan.dto.PlanningDtos.PlanDetailResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanOptionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PlanningServiceImplTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void percentileValuesMapToSqlColumnNames() throws Exception {
    assertThat(
            List.of(
                PlanBand.class.getDeclaredField("p10Value"),
                PlanBand.class.getDeclaredField("p25Value"),
                PlanBand.class.getDeclaredField("p50Value"),
                PlanBand.class.getDeclaredField("p75Value"),
                PlanBand.class.getDeclaredField("p90Value")))
        .allSatisfy(
            field -> {
              Column column = field.getAnnotation(Column.class);
              assertThat(column).as(field.getName()).isNotNull();
              assertThat(column.name()).isEqualTo(field.getName().replace("Value", "_value"));
            });
  }

  @Test
  void optionDecimalColumnsUseSqlPrecisionAndScale() throws Exception {
    assertDecimalColumn("nominalLevel", 4, 3, true);
    assertDecimalColumn("requiredReductionRate", 23, 4, false);
    assertDecimalColumn("simulationCoverage", 5, 4, false);
    assertDecimalColumn("historicalFeasibilityRatio", 5, 4, false);
  }

  @Test
  void planningRequiresCompletedProfileAndThreeCompleteMonths() {
    PlanningQueryService queries = mock(PlanningQueryService.class);
    PlanningService service =
        service(queries, mock(PlanningCommandService.class), mock(AnalysisServicePort.class));
    when(queries.readPlanInput(3, 9)).thenReturn(input(false, List.of(1L, 2L, 3L)));

    assertThatThrownBy(() -> service.createPlan(3, 9, "INITIAL", "req-1"))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("ONBOARDING_INCOMPLETE");

    when(queries.readPlanInput(3, 9)).thenReturn(input(true, List.of(1L, 2L)));
    assertThatThrownBy(() -> service.createPlan(3, 9, "INITIAL", "req-1"))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("INSUFFICIENT_HISTORY");
  }

  @Test
  void calculationRunsBeforeTransactionalSave() throws Exception {
    PlanningQueryService queries = mock(PlanningQueryService.class);
    PlanningCommandService commands = mock(PlanningCommandService.class);
    AnalysisServicePort analysis = mock(AnalysisServicePort.class);
    PlanInput input = input(true, List.of(1L, 2L, 3L));
    JsonNode calculation = validCalculation();
    PlanDetailResponse detail = mock(PlanDetailResponse.class);
    when(queries.readPlanInput(3, 9)).thenReturn(input);
    when(analysis.simulate(anyString(), anyString())).thenReturn(calculation);
    when(commands.save(3, 9, input, calculation, "INITIAL", null))
        .thenReturn(new SavedPlan(11, "a".repeat(64)));
    when(queries.plan(3, 11)).thenReturn(detail);

    service(queries, commands, analysis).createPlan(3, 9, "INITIAL", "req-1");

    InOrder order = inOrder(analysis, commands);
    order.verify(analysis).simulate(anyString(), anyString());
    order.verify(commands).save(3, 9, input, calculation, "INITIAL", null);
  }

  @Test
  void calculationRequiresExactlyThreePresetLevels() throws Exception {
    JsonNode wrong = mapper.readTree(validCalculation().toString().replace("0.9", "0.85"));

    assertThatThrownBy(() -> PlanningService.validateCalculation(wrong, 1))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("CALCULATION_SERVICE_UNAVAILABLE");
  }

  @Test
  void integerBandsRejectDecimalNumbers() throws Exception {
    JsonNode decimal =
        mapper.readTree(validCalculation().toString().replace("\"p10\":1", "\"p10\":1.0"));

    assertThatThrownBy(() -> PlanningService.validateCalculation(decimal, 1))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("CALCULATION_SERVICE_UNAVAILABLE");
  }

  @Test
  void optionDecimalsThatWouldBeRoundedByPostgresAreRejected() throws Exception {
    JsonNode excessiveScale =
        mapper.readTree(
            validCalculation()
                .toString()
                .replace("\"simulationCoverage\":0.7", "\"simulationCoverage\":0.70001"));

    assertThatThrownBy(() -> PlanningService.validateCalculation(excessiveScale, 1))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("CALCULATION_SERVICE_UNAVAILABLE");
  }

  @Test
  void customCalculationRunsBetweenSnapshotReadAndTransactionalSave() throws Exception {
    PlanningQueryService queries = mock(PlanningQueryService.class);
    PlanningCommandService commands = mock(PlanningCommandService.class);
    AnalysisServicePort analysis = mock(AnalysisServicePort.class);
    JsonNode inputSnapshot = mapper.readTree("{\"horizonMonths\":1,\"randomSeed\":7}");
    CustomOptionSnapshot snapshot =
        new CustomOptionSnapshot(11, "PROPOSED", "a".repeat(64), inputSnapshot, 1);
    JsonNode calculation = validCustomCalculation();
    PlanOptionResponse response = mock(PlanOptionResponse.class);
    when(queries.readCustomOptionSnapshot(3, 11)).thenReturn(snapshot);
    when(analysis.customOption(anyString(), anyString())).thenReturn(calculation);
    when(commands.saveCustomOption(3, snapshot, calculation)).thenReturn(17);
    when(queries.option(3, 17)).thenReturn(response);

    org.assertj.core.api.Assertions.assertThat(
            service(queries, commands, analysis).customOption(3, 11, 800_000L, "req-1"))
        .isSameAs(response);

    InOrder order = inOrder(queries, analysis, commands);
    order.verify(queries).readCustomOptionSnapshot(3, 11);
    order
        .verify(analysis)
        .customOption(
            org.mockito.ArgumentMatchers.contains("\"baselineMonthlySpending\":800000"),
            org.mockito.ArgumentMatchers.eq("req-1"));
    order.verify(commands).saveCustomOption(3, snapshot, calculation);
  }

  private PlanningService service(
      PlanningQueryService queries, PlanningCommandService commands, AnalysisServicePort analysis) {
    return new PlanningServiceImpl(
        queries, commands, analysis, mock(ExplanationQueuePort.class), mapper);
  }

  private void assertDecimalColumn(String fieldName, int precision, int scale, boolean nullable)
      throws Exception {
    Column column = PlanOption.class.getDeclaredField(fieldName).getAnnotation(Column.class);
    assertThat(column).as(fieldName).isNotNull();
    assertThat(column.precision()).as(fieldName).isEqualTo(precision);
    assertThat(column.scale()).as(fieldName).isEqualTo(scale);
    assertThat(column.nullable()).as(fieldName).isEqualTo(nullable);
  }

  private PlanInput input(boolean profileComplete, List<Long> history) {
    return new PlanInput(
        3,
        9,
        "목표",
        1_000_000L,
        10L,
        LocalDate.now().plusMonths(1),
        3_000_000L,
        1_000_000L,
        history,
        List.of(),
        1,
        List.of(1.0),
        999_990L,
        0,
        1_000_000L,
        mapper.createObjectNode().put("aggressiveWarningPct", 0.2),
        profileComplete,
        "none");
  }

  private JsonNode validCalculation() throws Exception {
    return mapper.readTree(
        """
        {"simulation":{"method":"IID_BOOTSTRAP","nPaths":10000,"randomSeed":7,"inputHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","engineVersion":"1","inputSnapshot":{"x":1},"resultSummary":{"x":1}},"options":[{"optionType":"PRESET","nominalLevel":0.7,"recommendedMonthlySpending":10,"requiredReductionRate":0.1,"simulationCoverage":0.7,"historicalFeasibilityRatio":0.5,"aggressiveWarning":false,"targetCoverageMet":true},{"optionType":"PRESET","nominalLevel":0.8,"recommendedMonthlySpending":10,"requiredReductionRate":0.1,"simulationCoverage":0.8,"historicalFeasibilityRatio":0.5,"aggressiveWarning":false,"targetCoverageMet":true},{"optionType":"PRESET","nominalLevel":0.9,"recommendedMonthlySpending":10,"requiredReductionRate":0.1,"simulationCoverage":0.9,"historicalFeasibilityRatio":0.5,"aggressiveWarning":false,"targetCoverageMet":true}],"percentileBands":[{"optionIndex":0,"monthIndex":1,"metricType":"CUMULATIVE_SAVINGS","p10":1,"p25":2,"p50":3,"p75":4,"p90":5},{"optionIndex":1,"monthIndex":1,"metricType":"CUMULATIVE_SAVINGS","p10":1,"p25":2,"p50":3,"p75":4,"p90":5},{"optionIndex":2,"monthIndex":1,"metricType":"CUMULATIVE_SAVINGS","p10":1,"p25":2,"p50":3,"p75":4,"p90":5}]}
        """);
  }

  private JsonNode validCustomCalculation() throws Exception {
    return mapper.readTree(
        """
        {"option":{"optionType":"CUSTOM","nominalLevel":null,"recommendedMonthlySpending":800000,"requiredReductionRate":0.2,"simulationCoverage":0.8,"historicalFeasibilityRatio":0.5,"aggressiveWarning":false,"targetCoverageMet":true},"percentileBands":[{"optionIndex":0,"monthIndex":1,"metricType":"CUMULATIVE_SAVINGS","p10":1,"p25":2,"p50":3,"p75":4,"p90":5}]}
        """);
  }
}
