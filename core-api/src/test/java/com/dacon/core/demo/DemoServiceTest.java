package com.dacon.core.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dacon.core.error.ApiException;
import com.dacon.core.user.entity.UserProfile;
import com.dacon.core.user.repository.UserProfileRepository;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
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

    assertThat(new DemoService(repository, mock(UserProfileRepository.class)).testers()).hasSize(1);
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

    assertThat(
            new DemoService(repository, mock(UserProfileRepository.class))
                .seed(7, "youth")
                .scenarioVersion())
        .isEqualTo(2);
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
    DemoService service = new DemoService(repository, mock(UserProfileRepository.class));

    assertThatThrownBy(() -> service.seed(7, "occupied"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT));
    assertThatThrownBy(() -> service.seed(7, "missing"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void classifiesBoundaryAgesBeforeAndOnBirthday() {
    LocalDate today = LocalDate.of(2026, 9, 2);

    assertThat(DemoService.testerId(LocalDate.of(1991, 9, 3), today)).isEqualTo("youth");
    assertThat(DemoService.testerId(LocalDate.of(1991, 9, 2), today)).isEqualTo("middle");
    assertThat(DemoService.testerId(LocalDate.of(1971, 9, 3), today)).isEqualTo("middle");
    assertThat(DemoService.testerId(LocalDate.of(1971, 9, 2), today)).isEqualTo("senior");
  }

  @Test
  void classifiesLeapDayBirthDateByCalendarAge() {
    assertThat(DemoService.testerId(LocalDate.of(1992, 2, 29), LocalDate.of(2027, 2, 28)))
        .isEqualTo("youth");
    assertThat(DemoService.testerId(LocalDate.of(1992, 2, 29), LocalDate.of(2027, 3, 1)))
        .isEqualTo("middle");
  }

  @Test
  void seedsTransactionsForAuthenticatedUsersProfileAndMapsResult() {
    DemoRepository repository = mock(DemoRepository.class);
    UserProfileRepository profiles = mock(UserProfileRepository.class);
    UserProfile profile = mock(UserProfile.class);
    DemoRepository.DemoTransactionsRow row = mock(DemoRepository.DemoTransactionsRow.class);
    when(profile.birthDate()).thenReturn(LocalDate.now(ZoneId.of("Asia/Seoul")).minusYears(30));
    when(profiles.findById(7)).thenReturn(Optional.of(profile));
    when(repository.seedTransactions(7, "youth")).thenReturn(row);
    when(row.getTesterId()).thenReturn("youth");
    when(row.getScenarioVersion()).thenReturn(3);
    when(row.getInserted()).thenReturn(24);
    when(row.getCompleteMonths()).thenReturn(24);

    DemoDtos.DemoTransactionsResponse result =
        new DemoService(repository, profiles).seedTransactions(7);

    assertThat(result).isEqualTo(new DemoDtos.DemoTransactionsResponse("youth", 3, 24, 24));
  }

  @Test
  void rejectsMissingOrFutureBirthDate() {
    DemoRepository repository = mock(DemoRepository.class);
    UserProfileRepository profiles = mock(UserProfileRepository.class);
    UserProfile futureProfile = mock(UserProfile.class);
    when(futureProfile.birthDate()).thenReturn(LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1));
    when(profiles.findById(7)).thenReturn(Optional.empty());
    when(profiles.findById(8)).thenReturn(Optional.of(futureProfile));
    DemoService service = new DemoService(repository, profiles);

    assertThatThrownBy(() -> service.seedTransactions(7))
        .isInstanceOf(ApiException.class)
        .satisfies(
            error -> {
              assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(((ApiException) error).code()).isEqualTo("PROFILE_BIRTH_DATE_REQUIRED");
            });
    assertThatThrownBy(() -> service.seedTransactions(8))
        .isInstanceOf(ApiException.class)
        .satisfies(
            error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void mapsTransactionSeedDatabaseErrors() {
    DemoRepository repository = mock(DemoRepository.class);
    UserProfileRepository profiles = mock(UserProfileRepository.class);
    UserProfile profile = mock(UserProfile.class);
    when(profile.birthDate()).thenReturn(LocalDate.now(ZoneId.of("Asia/Seoul")).minusYears(30));
    when(profiles.findById(7)).thenReturn(Optional.of(profile));
    when(repository.seedTransactions(7, "youth"))
        .thenThrow(
            new DataIntegrityViolationException(
                "seed", new SQLException("ERROR: DEMO_TESTER_NOT_FOUND")));
    when(repository.seedTransactions(8, "youth"))
        .thenThrow(
            new DataIntegrityViolationException(
                "seed", new SQLException("ERROR: DEMO_TEMPLATE_INCOMPLETE_MONTHS")));
    when(profiles.findById(8)).thenReturn(Optional.of(profile));
    DemoService service = new DemoService(repository, profiles);

    assertThatThrownBy(() -> service.seedTransactions(7))
        .isInstanceOf(ApiException.class)
        .satisfies(
            error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));
    assertThatThrownBy(() -> service.seedTransactions(8))
        .isInstanceOf(ApiException.class)
        .satisfies(
            error ->
                assertThat(((ApiException) error).status())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
  }

  private DemoScenario scenarioWithMoney(
      BigDecimal income, BigDecimal fixedCost, BigDecimal targetAmount) {
    return new DemoScenario(
        "tester", 1, "표시", "설명", "YOUTH", income, fixedCost, "목표", targetAmount, (short) 12);
  }
}
