package com.dacon.core.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dacon.core.error.ApiException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

class DemoServiceTest {
  @Test
  void moneyFieldsUsePostgresNumericMapping() throws Exception {
    assertThat(DemoScenario.class.getDeclaredField("monthlyIncome").getType())
        .isEqualTo(BigDecimal.class);
    assertThat(DemoScenario.class.getDeclaredField("monthlyFixedCost").getType())
        .isEqualTo(BigDecimal.class);
    assertThat(DemoScenario.class.getDeclaredField("goalTargetAmount").getType())
        .isEqualTo(BigDecimal.class);
  }

  @Test
  void publicMoneyConversionRejectsFractionAndOverflow() {
    assertThatThrownBy(
            () ->
                scenarioWithMoney(new BigDecimal("1.5"), BigDecimal.ONE, BigDecimal.TEN).response())
        .isInstanceOf(ArithmeticException.class);
    assertThatThrownBy(
            () ->
                scenarioWithMoney(
                        new BigDecimal("9223372036854775808"), BigDecimal.ONE, BigDecimal.TEN)
                    .response())
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void goalMonthsUsesPostgresSmallintMapping() throws Exception {
    assertThat(DemoScenario.class.getDeclaredField("goalMonths").getType()).isEqualTo(short.class);
  }

  @Test
  void compositeIdProvidesJpaNoArgConstructor() throws Exception {
    assertThat(DemoScenario.DemoScenarioId.class.getConstructor()).isNotNull();
  }

  @Test
  void listsLatestScenarioVersionOnly() {
    DemoRepository repository = mock(DemoRepository.class);
    when(repository.findLatestTesters())
        .thenReturn(
            List.of(
                new DemoScenario(
                    "youth",
                    2,
                    "청년",
                    "설명",
                    "YOUTH",
                    BigDecimal.valueOf(3_000_000),
                    BigDecimal.valueOf(1_000_000),
                    "목표",
                    BigDecimal.valueOf(10_000_000),
                    (short) 12)));

    assertThat(new DemoService(repository).testers()).hasSize(1);
  }

  @Test
  void mapsSeedFunctionResult() {
    DemoRepository repository = mock(DemoRepository.class);
    Instant seededAt = Instant.parse("2026-08-28T00:00:00Z");
    DemoRepository.DemoSeedRow row = mock(DemoRepository.DemoSeedRow.class);
    when(row.getTesterId()).thenReturn("youth");
    when(row.getScenarioVersion()).thenReturn(2);
    when(row.getSeededAt()).thenReturn(seededAt);
    when(repository.seed(7, "youth")).thenReturn(row);

    assertThat(new DemoService(repository).seed(7, "youth").scenarioVersion()).isEqualTo(2);
  }

  @Test
  void mapsSeedConflictAndMissingTester() {
    DemoRepository repository = mock(DemoRepository.class);
    when(repository.seed(7, "occupied"))
        .thenThrow(
            new DataIntegrityViolationException(
                "seed", new SQLException("ERROR: DEMO_SEED_CONFLICT")));
    when(repository.seed(7, "missing"))
        .thenThrow(
            new DataIntegrityViolationException(
                "seed", new SQLException("ERROR: DEMO_TESTER_NOT_FOUND")));
    DemoService service = new DemoService(repository);

    assertThatThrownBy(() -> service.seed(7, "occupied"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT));
    assertThatThrownBy(() -> service.seed(7, "missing"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  private DemoScenario scenarioWithMoney(
      BigDecimal income, BigDecimal fixedCost, BigDecimal targetAmount) {
    return new DemoScenario(
        "tester", 1, "표시", "설명", "YOUTH", income, fixedCost, "목표", targetAmount, (short) 12);
  }
}
