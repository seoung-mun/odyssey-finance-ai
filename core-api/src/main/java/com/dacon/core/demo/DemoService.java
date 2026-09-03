package com.dacon.core.demo;

import com.dacon.core.error.ApiException;
import com.dacon.core.user.entity.UserProfile;
import com.dacon.core.user.repository.UserProfileRepository;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 데모 목록 조회와 사용자 seed 오류를 공개 API 상태로 변환한다. */
@Service
public class DemoService {
  private final DemoRepository repository;
  private final UserProfileRepository profiles;

  public DemoService(DemoRepository repository, UserProfileRepository profiles) {
    this.repository = repository;
    this.profiles = profiles;
  }

  @Transactional(readOnly = true)
  public List<DemoDtos.DemoTester> testers() {
    return repository.findLatestTesters().stream().map(DemoScenario::response).toList();
  }

  @Transactional
  public DemoDtos.DemoSeedResponse seed(int userId, String testerId) {
    try {
      DemoRepository.DemoSeedRow seeded = repository.seed(userId, testerId);
      return new DemoDtos.DemoSeedResponse(
          seeded.getTesterId(), seeded.getScenarioVersion(), seeded.getSeededAt());
    } catch (DataAccessException exception) {
      String message = exception.getMostSpecificCause().getMessage();
      if (message != null && message.contains("DEMO_TESTER_NOT_FOUND")) {
        throw new ApiException(HttpStatus.NOT_FOUND, "DEMO_TESTER_NOT_FOUND", "데모 테스터가 없습니다.");
      }
      if (message != null && message.contains("DEMO_SEED_CONFLICT")) {
        throw new ApiException(
            HttpStatus.CONFLICT, "DEMO_SEED_CONFLICT", "기존 데이터가 있는 사용자는 데모 seed를 시작할 수 없습니다.");
      }
      throw exception;
    }
  }

  @Transactional
  public DemoDtos.DemoTransactionsResponse seedTransactions(int userId) {
    UserProfile profile = profiles.findById(userId).orElse(null);
    LocalDate birthDate = profile == null ? null : profile.birthDate();
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    if (birthDate == null || birthDate.isAfter(today)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "PROFILE_BIRTH_DATE_REQUIRED", "데모 거래 생성을 위해 생년월일이 필요합니다.");
    }

    String testerId = testerId(birthDate, today);
    try {
      DemoRepository.DemoTransactionsRow seeded = repository.seedTransactions(userId, testerId);
      return new DemoDtos.DemoTransactionsResponse(
          seeded.getTesterId(),
          seeded.getScenarioVersion(),
          seeded.getInserted(),
          seeded.getCompleteMonths());
    } catch (DataAccessException exception) {
      String message = exception.getMostSpecificCause().getMessage();
      if (message != null && message.contains("DEMO_TESTER_NOT_FOUND")) {
        throw new ApiException(HttpStatus.NOT_FOUND, "DEMO_TESTER_NOT_FOUND", "데모 테스터가 없습니다.");
      }
      if (message != null && message.contains("DEMO_USER_NOT_FOUND")) {
        throw new ApiException(HttpStatus.NOT_FOUND, "DEMO_USER_NOT_FOUND", "사용자가 없습니다.");
      }
      if (message != null && message.contains("DEMO_TRANSACTION_REPLACE_CONFLICT")) {
        throw new ApiException(
            HttpStatus.CONFLICT, "DEMO_TRANSACTION_REPLACE_CONFLICT", "데모 거래를 교체할 수 없습니다.");
      }
      if (message != null && message.contains("DEMO_TEMPLATE_MISSING")) {
        throw demoTemplateError("DEMO_TEMPLATE_MISSING");
      }
      if (message != null && message.contains("DEMO_TEMPLATE_INCOMPLETE_MONTHS")) {
        throw demoTemplateError("DEMO_TEMPLATE_INCOMPLETE_MONTHS");
      }
      if (message != null && message.contains("DEMO_TEMPLATE_MONTHLY_TOTAL_TOO_LARGE")) {
        throw demoTemplateError("DEMO_TEMPLATE_MONTHLY_TOTAL_TOO_LARGE");
      }
      if (message != null && message.contains("DEMO_TEMPLATE_NOT_FOUND")) {
        throw demoTemplateError("DEMO_TEMPLATE_NOT_FOUND");
      }
      throw exception;
    }
  }

  private ApiException demoTemplateError(String code) {
    return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, code, "데모 거래 템플릿을 사용할 수 없습니다.");
  }

  static String testerId(LocalDate birthDate, LocalDate today) {
    if (birthDate == null || birthDate.isAfter(today)) {
      throw new IllegalArgumentException("birthDate must not be after today");
    }
    int age = Period.between(birthDate, today).getYears();
    if (age <= 34) {
      return "youth";
    }
    if (age <= 54) {
      return "middle";
    }
    return "senior";
  }
}
