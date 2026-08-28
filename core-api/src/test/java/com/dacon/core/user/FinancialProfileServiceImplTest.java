package com.dacon.core.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dacon.core.auth.UserAccount;
import com.dacon.core.auth.UserAccountRepository;
import com.dacon.core.goal.FinancialGoalRepository;
import com.dacon.core.user.dto.FinancialProfileInput;
import com.dacon.core.user.dto.FinancialProfileResponse;
import com.dacon.core.user.repository.FinancialProfileRepository;
import com.dacon.core.user.repository.UserProfileRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FinancialProfileServiceImplTest {
  @Test
  void onboardingUpsertAlwaysUsesAuthenticatedUserId() {
    FinancialProfileRepository profiles = mock(FinancialProfileRepository.class);
    FinancialGoalRepository goals = mock(FinancialGoalRepository.class);
    UserAccountRepository users = mock(UserAccountRepository.class);
    UserAccount user = mock(UserAccount.class);
    when(users.findById(9)).thenReturn(Optional.of(user));
    when(profiles.findById(9)).thenReturn(Optional.empty());
    when(profiles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    UserService service =
        new UserServiceImpl(
            users,
            mock(com.dacon.core.auth.SocialAccountRepository.class),
            mock(UserProfileRepository.class),
            profiles,
            goals);
    FinancialProfileInput input = new FinancialProfileInput(3_000_000L, 1_000_000L);

    FinancialProfileResponse result = service.upsertFinancialProfile(9, input);

    assertThat(result.monthlyIncome()).isEqualTo(3_000_000L);
    verify(users).findById(9);
    verify(profiles).findById(9);
    verify(profiles).save(any(com.dacon.core.user.entity.FinancialProfile.class));
  }
}
