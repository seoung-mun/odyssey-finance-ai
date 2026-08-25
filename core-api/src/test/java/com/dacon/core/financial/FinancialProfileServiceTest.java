package com.dacon.core.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FinancialProfileServiceTest {
  @Test
  void onboardingUpsertAlwaysUsesAuthenticatedUserId() {
    var repository = mock(FinancialProfileRepository.class);
    var jdbc = mock(JdbcTemplate.class);
    var profile = new FinancialProfile(9);
    when(repository.findById(9)).thenReturn(Optional.of(profile));
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(jdbc.queryForObject(
            any(String.class), org.mockito.ArgumentMatchers.eq(Boolean.class), any()))
        .thenReturn(false);
    var service = new FinancialProfileService(repository, jdbc);
    var input = new FinancialProfileInput(3_000_000, 1_000_000, SpendingFloorMode.OFF, null);

    var result = service.upsert(9, input);

    assertThat(result.monthlyIncome()).isEqualTo(3_000_000);
    verify(repository).findById(9);
    verify(repository).save(profile);
  }
}
