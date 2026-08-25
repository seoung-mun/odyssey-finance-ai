package com.dacon.core.user;

import com.dacon.core.auth.SocialAccount;
import com.dacon.core.auth.SocialAccountRepository;
import com.dacon.core.auth.UserAccount;
import com.dacon.core.auth.UserAccountRepository;
import com.dacon.core.error.ApiException;
import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.FinancialGoalRepository;
import com.dacon.core.goal.ScheduledExpense;
import com.dacon.core.goal.ScheduledExpenseRepository;
import com.dacon.core.transaction.TransactionRepository;
import com.dacon.core.user.dto.FinancialProfileInput;
import com.dacon.core.user.dto.FinancialProfileResponse;
import com.dacon.core.user.dto.UserDtos.MeResponse;
import com.dacon.core.user.dto.UserDtos.ProfileInput;
import com.dacon.core.user.dto.UserDtos.ProfileResponse;
import com.dacon.core.user.dto.UserDtos.SampleResponse;
import com.dacon.core.user.entity.FinancialProfile;
import com.dacon.core.user.entity.ReplanOutcome;
import com.dacon.core.user.entity.SpendingFloorMode;
import com.dacon.core.user.entity.UserProfile;
import com.dacon.core.user.repository.FinancialProfileRepository;
import com.dacon.core.user.repository.UserProfileRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 현재 사용자 조회와 온보딩 command를 JPA repository로 수행한다. */
@Service
public class UserServiceImpl implements UserService {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final String SAMPLE_VERSION = "v1";

  private final UserAccountRepository users;
  private final SocialAccountRepository socialAccounts;
  private final UserProfileRepository profiles;
  private final FinancialProfileRepository financialProfiles;
  private final FinancialGoalRepository goals;
  private final ScheduledExpenseRepository scheduledExpenses;
  private final TransactionRepository transactions;

  public UserServiceImpl(
      UserAccountRepository users,
      SocialAccountRepository socialAccounts,
      UserProfileRepository profiles,
      FinancialProfileRepository financialProfiles,
      FinancialGoalRepository goals,
      ScheduledExpenseRepository scheduledExpenses,
      TransactionRepository transactions) {
    this.users = users;
    this.socialAccounts = socialAccounts;
    this.profiles = profiles;
    this.financialProfiles = financialProfiles;
    this.goals = goals;
    this.scheduledExpenses = scheduledExpenses;
    this.transactions = transactions;
  }

  @Override
  @Transactional(readOnly = true)
  public MeResponse me(int userId) {
    UserAccount user = requireUser(userId);
    SocialAccount social =
        socialAccounts
            .findFirstByUserIdOrderById(userId)
            .orElseThrow(() -> notFound("요청한 자원이 없습니다."));
    List<String> missing = new ArrayList<>();
    if (!profiles.existsById(userId)) {
      missing.add("PROFILE");
    }
    if (!financialProfiles.existsById(userId)) {
      missing.add("FINANCIAL_PROFILE");
    }
    Integer activeGoalId =
        goals.findFirstByUserIdAndStatus(userId, "ACTIVE").map(FinancialGoal::id).orElse(null);
    if (activeGoalId == null) {
      missing.add("GOAL");
    }
    return new MeResponse(
        user.id(),
        social.email(),
        social.displayName(),
        social.profileImageUrl(),
        missing.isEmpty(),
        List.copyOf(missing),
        activeGoalId,
        user.sampleDataLoadedAt());
  }

  @Override
  @Transactional(readOnly = true)
  public ProfileResponse profile(int userId) {
    UserProfile profile = profiles.findById(userId).orElseThrow(() -> notFound("요청한 자원이 없습니다."));
    return profileResponse(profile);
  }

  @Override
  @Transactional
  public ProfileResponse upsertProfile(int userId, ProfileInput input) {
    UserAccount user = requireUser(userId);
    UserProfile profile = profiles.findById(userId).orElseGet(() -> new UserProfile(user, input));
    profile.update(input);
    return profileResponse(profiles.save(profile));
  }

  @Override
  @Transactional
  public SampleResponse loadSample(int userId) {
    UserAccount user = users.findByIdForUpdate(userId).orElseThrow(() -> notFound("요청한 자원이 없습니다."));
    if (user.sampleDataLoadedAt() != null) {
      return new SampleResponse(false, user.sampleDataLoadedAt());
    }
    if (financialProfiles.existsById(userId)
        || !goals.findByUserIdOrderByCreatedAtDesc(userId).isEmpty()
        || transactions.existsByUserId(userId)
        || scheduledExpenses.existsByUserId(userId)) {
      throw new ApiException(
          HttpStatus.CONFLICT, "SAMPLE_DATA_CONFLICT", "이미 입력한 정보가 있어 샘플을 추가할 수 없습니다.");
    }

    profiles.save(new UserProfile(user, new ProfileInput(LocalDate.of(1995, 5, 15), "11680")));
    FinancialProfile financial = new FinancialProfile(user);
    financial.update(
        new FinancialProfileInput(4_200_000L, 1_650_000L, SpendingFloorMode.AUTO, null));
    financialProfiles.save(financial);
    goals.save(
        new FinancialGoal(
            user, "비상금 2천만원", 20_000_000L, 5_000_000L, LocalDate.now(KST).plusMonths(18)));
    scheduledExpenses.save(
        new ScheduledExpense(user, "보험 갱신", 480_000L, LocalDate.now(KST).plusMonths(2)));
    insertSampleTransactions(userId);
    Instant now = Instant.now();
    user.markSampleLoaded(now);
    users.save(user);
    return new SampleResponse(true, now);
  }

  @Override
  @Transactional(readOnly = true)
  public FinancialProfileResponse financialProfile(int userId) {
    FinancialProfile profile =
        financialProfiles
            .findById(userId)
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.NOT_FOUND, "FINANCIAL_PROFILE_NOT_FOUND", "금융 프로필이 없습니다."));
    return financialResponse(profile);
  }

  @Override
  @Transactional
  public FinancialProfileResponse upsertFinancialProfile(int userId, FinancialProfileInput input) {
    UserAccount user = requireUser(userId);
    FinancialProfile profile =
        financialProfiles.findById(userId).orElseGet(() -> new FinancialProfile(user));
    if (!profile.matches(input) && goals.existsByUserIdAndStatus(userId, "ACTIVE")) {
      throw new ApiException(
          HttpStatus.NOT_IMPLEMENTED,
          "ACTIVE_GOAL_REPLAN_NOT_IMPLEMENTED",
          "활성 목표의 재계획 저장 기능이 준비되기 전에는 금융 정보를 변경할 수 없습니다.");
    }
    profile.update(input);
    return financialResponse(financialProfiles.save(profile));
  }

  /** 사용자·월·index로 결정적인 샘플 거래 ID를 만든다. */
  static String sampleExternalId(int userId, String month, int index) {
    return "sample-" + SAMPLE_VERSION + "-u" + userId + "-" + month + "-" + index;
  }

  private void insertSampleTransactions(int userId) {
    YearMonth current = YearMonth.now(KST);
    for (int monthOffset = 12; monthOffset >= 1; monthOffset--) {
      YearMonth month = current.minusMonths(monthOffset);
      long[] amounts = {820_000L, 360_000L, 240_000L};
      String[] categories = {"식비", "교통", "생활"};
      for (int index = 0; index < amounts.length; index++) {
        OffsetDateTime transactionAt =
            month.atDay(5 + index * 8).atStartOfDay(KST).toOffsetDateTime();
        transactions.insertIgnoringDuplicate(
            userId,
            transactionAt,
            amounts[index] + (monthOffset % 3) * 20_000L,
            "PAYMENT",
            categories[index],
            "샘플 가맹점",
            null,
            null,
            sampleExternalId(userId, month.toString(), index));
      }
    }
  }

  private UserAccount requireUser(int userId) {
    return users.findById(userId).orElseThrow(() -> notFound("요청한 자원이 없습니다."));
  }

  private ProfileResponse profileResponse(UserProfile profile) {
    return new ProfileResponse(profile.birthDate(), profile.regionCode(), profile.updatedAt());
  }

  private FinancialProfileResponse financialResponse(FinancialProfile profile) {
    return new FinancialProfileResponse(
        profile.monthlyIncome(),
        profile.monthlyFixedCost(),
        profile.spendingFloorMode(),
        profile.customMonthlyVariableFloor(),
        profile.updatedAt(),
        null,
        ReplanOutcome.NOT_REQUIRED,
        null,
        null);
  }

  private ApiException notFound(String message) {
    return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
  }
}
