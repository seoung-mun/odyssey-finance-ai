package com.dacon.core.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dacon.core.auth.UserAccount;
import com.dacon.core.auth.UserAccountRepository;
import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.FinancialGoalRepository;
import com.dacon.core.plan.PlanVersion;
import com.dacon.core.plan.PlanVersionRepository;
import com.dacon.core.user.dto.FinancialProfileInput;
import com.dacon.core.user.dto.FinancialProfileResponse;
import com.dacon.core.user.entity.FinancialProfile;
import com.dacon.core.user.entity.ReplanOutcome;
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

  @Test
  void activeGoalWithoutActivePlanSavesProfileWithoutReplan() {
    FinancialProfileRepository profiles = mock(FinancialProfileRepository.class);
    FinancialGoalRepository goals = mock(FinancialGoalRepository.class);
    PlanVersionRepository plans = mock(PlanVersionRepository.class);
    FinancialReplanService financialReplans = mock(FinancialReplanService.class);
    UserAccountRepository users = mock(UserAccountRepository.class);
    UserAccount user = mock(UserAccount.class);
    FinancialGoal goal = mock(FinancialGoal.class);
    FinancialProfile profile = new FinancialProfile(user);
    profile.update(new FinancialProfileInput(2_000_000L, 800_000L));
    when(users.findById(9)).thenReturn(Optional.of(user));
    when(profiles.findById(9)).thenReturn(Optional.of(profile));
    when(profiles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(goals.findFirstByUserIdAndStatus(9, "ACTIVE")).thenReturn(Optional.of(goal));
    when(goal.id()).thenReturn(17);
    when(plans.findFirstByGoalIdAndStatusOrderByVersionNoDesc(17, "ACTIVE"))
        .thenReturn(Optional.empty());
    UserService service =
        new UserServiceImpl(
            users,
            mock(com.dacon.core.auth.SocialAccountRepository.class),
            mock(UserProfileRepository.class),
            profiles,
            goals,
            plans,
            financialReplans);
    FinancialProfileInput input = new FinancialProfileInput(3_000_000L, 1_000_000L);

    FinancialProfileResponse result = service.upsertFinancialProfile(9, input);

    assertThat(result.monthlyIncome()).isEqualTo(3_000_000L);
    assertThat(result.monthlyFixedCost()).isEqualTo(1_000_000L);
    assertThat(result.replanOutcome()).isEqualTo(ReplanOutcome.NOT_REQUIRED);
    verify(profiles).save(any(com.dacon.core.user.entity.FinancialProfile.class));
    verify(financialReplans, never()).change(eq(9), same(goal), eq(input), anyString());
  }

  @Test
  void activeGoalWithActivePlanKeepsExistingReplanBehavior() {
    FinancialProfileRepository profiles = mock(FinancialProfileRepository.class);
    FinancialGoalRepository goals = mock(FinancialGoalRepository.class);
    PlanVersionRepository plans = mock(PlanVersionRepository.class);
    FinancialReplanService financialReplans = mock(FinancialReplanService.class);
    UserAccountRepository users = mock(UserAccountRepository.class);
    UserAccount user = mock(UserAccount.class);
    FinancialGoal goal = mock(FinancialGoal.class);
    FinancialProfile profile = new FinancialProfile(user);
    profile.update(new FinancialProfileInput(2_000_000L, 800_000L));
    FinancialProfileInput input = new FinancialProfileInput(3_000_000L, 1_000_000L);
    FinancialProfileResponse expected =
        new FinancialProfileResponse(
            3_000_000L, 1_000_000L, null, 23, ReplanOutcome.PROPOSED, 31, null);
    when(users.findById(9)).thenReturn(Optional.of(user));
    when(profiles.findById(9)).thenReturn(Optional.of(profile));
    when(goals.findFirstByUserIdAndStatus(9, "ACTIVE")).thenReturn(Optional.of(goal));
    when(goal.id()).thenReturn(17);
    when(plans.findFirstByGoalIdAndStatusOrderByVersionNoDesc(17, "ACTIVE"))
        .thenReturn(Optional.of(mock(PlanVersion.class)));
    when(financialReplans.change(eq(9), same(goal), eq(input), anyString())).thenReturn(expected);
    UserService service =
        new UserServiceImpl(
            users,
            mock(com.dacon.core.auth.SocialAccountRepository.class),
            mock(UserProfileRepository.class),
            profiles,
            goals,
            plans,
            financialReplans);

    FinancialProfileResponse result = service.upsertFinancialProfile(9, input);

    assertThat(result).isSameAs(expected);
    verify(financialReplans).change(eq(9), same(goal), eq(input), anyString());
    verify(profiles, never()).save(any(com.dacon.core.user.entity.FinancialProfile.class));
  }
}
