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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 현재 사용자 조회와 온보딩 변경을 사용자 ID 범위의 JPA 트랜잭션으로 수행한다. */
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
  private final FinancialReplanService financialReplans;

  /**
   * 계정과 온보딩 데이터에 필요한 저장소를 모두 주입해 유스케이스를 구성한다.
   *
   * @param users 내부 사용자 저장소
   * @param socialAccounts 소셜 표시 정보 저장소
   * @param profiles 인적 프로필 저장소
   * @param financialProfiles 금융 프로필 저장소
   * @param goals 금융 목표 저장소
   * @param scheduledExpenses 예정지출 저장소
   * @param transactions 거래 저장소
   */
  @Autowired
  public UserServiceImpl(
      UserAccountRepository users,
      SocialAccountRepository socialAccounts,
      UserProfileRepository profiles,
      FinancialProfileRepository financialProfiles,
      FinancialGoalRepository goals,
      ScheduledExpenseRepository scheduledExpenses,
      TransactionRepository transactions,
      FinancialReplanService financialReplans) {
    this.users = users;
    this.socialAccounts = socialAccounts;
    this.profiles = profiles;
    this.financialProfiles = financialProfiles;
    this.goals = goals;
    this.scheduledExpenses = scheduledExpenses;
    this.transactions = transactions;
    this.financialReplans = financialReplans;
  }

  UserServiceImpl(
      UserAccountRepository users,
      SocialAccountRepository socialAccounts,
      UserProfileRepository profiles,
      FinancialProfileRepository financialProfiles,
      FinancialGoalRepository goals,
      ScheduledExpenseRepository scheduledExpenses,
      TransactionRepository transactions) {
    this(
        users,
        socialAccounts,
        profiles,
        financialProfiles,
        goals,
        scheduledExpenses,
        transactions,
        null);
  }

  /** {@inheritDoc} */
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

  /** {@inheritDoc} */
  @Override
  @Transactional(readOnly = true)
  public ProfileResponse profile(int userId) {
    UserProfile profile = profiles.findById(userId).orElseThrow(() -> notFound("요청한 자원이 없습니다."));
    return profileResponse(profile);
  }

  /** {@inheritDoc} */
  @Override
  @Transactional
  public ProfileResponse upsertProfile(int userId, ProfileInput input) {
    UserAccount user = requireUser(userId);
    UserProfile profile = profiles.findById(userId).orElseGet(() -> new UserProfile(user, input));
    profile.update(input);
    return profileResponse(profiles.save(profile));
  }

  /**
   * {@inheritDoc}
   *
   * <p>사용자 행을 비관적 쓰기 잠금한 뒤 빈 계정 조건을 재확인한다. 완료 표식과 모든 샘플 행은 같은 트랜잭션에서 commit된다.
   */
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

  /** {@inheritDoc} */
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

  /**
   * {@inheritDoc}
   *
   * <p>현재 재계획 저장 기능이 없으므로 ACTIVE 목표가 있는 계정의 실질적 값 변경은 저장 전에 거부한다. 동일 값 저장은 허용한다.
   */
  @Override
  @Transactional
  public FinancialProfileResponse upsertFinancialProfile(int userId, FinancialProfileInput input) {
    UserAccount user = requireUser(userId);
    FinancialProfile profile =
        financialProfiles.findById(userId).orElseGet(() -> new FinancialProfile(user));
    if (profile.matches(input)) {
      return financialResponse(profile);
    }
    FinancialGoal active = goals.findFirstByUserIdAndStatus(userId, "ACTIVE").orElse(null);
    if (active != null) {
      return financialReplans.change(userId, active, input, java.util.UUID.randomUUID().toString());
    }
    profile.update(input);
    return financialResponse(financialProfiles.save(profile));
  }

  /**
   * 샘플 버전·사용자·월·월 내 순번으로 재실행에도 동일한 거래 멱등 ID를 만든다.
   *
   * @return 사용자 범위 부분 unique index에 사용할 외부 거래 ID
   */
  static String sampleExternalId(int userId, String month, int index) {
    return "sample-" + SAMPLE_VERSION + "-u" + userId + "-" + month + "-" + index;
  }

  /**
   * 지정 사용자의 직전 12개월에 월 3건씩 결정적인 PAYMENT 샘플을 삽입한다.
   *
   * @param userId 샘플 거래 소유 사용자 ID
   */
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

  /**
   * 사용자 존재를 확인하고 없으면 소유 자원과 같은 404로 숨긴다.
   *
   * @param userId 확인할 내부 사용자 ID
   * @return 존재하는 사용자
   * @throws ApiException 사용자가 없는 경우
   */
  private UserAccount requireUser(int userId) {
    return users.findById(userId).orElseThrow(() -> notFound("요청한 자원이 없습니다."));
  }

  /**
   * 인적 프로필 엔티티를 외부 응답으로 복사한다.
   *
   * @param profile 변환할 사용자 소유 인적 프로필
   * @return 저장된 인적 프로필 응답
   */
  private ProfileResponse profileResponse(UserProfile profile) {
    return new ProfileResponse(profile.birthDate(), profile.regionCode(), profile.updatedAt());
  }

  /**
   * 금융 프로필을 재계획 미발생 응답으로 변환한다.
   *
   * @param profile 변환할 사용자 소유 금융 프로필
   * @return 저장된 금융 프로필과 {@code NOT_REQUIRED} 결과
   */
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

  /**
   * 존재 여부와 다른 사용자 소유 여부를 구분하지 않는 404 예외를 만든다.
   *
   * @param message 사용자에게 노출할 상세 메시지
   * @return {@code RESOURCE_NOT_FOUND} API 예외
   */
  private ApiException notFound(String message) {
    return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
  }
}
