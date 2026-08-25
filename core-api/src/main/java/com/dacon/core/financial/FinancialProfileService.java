package com.dacon.core.financial;

import com.dacon.core.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinancialProfileService {
  private final FinancialProfileRepository repository;
  private final JdbcTemplate jdbc;

  FinancialProfileService(FinancialProfileRepository repository, JdbcTemplate jdbc) {
    this.repository = repository;
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public FinancialProfileResponse get(int userId) {
    return repository
        .findById(userId)
        .orElseThrow(
            () ->
                new ApiException(
                    HttpStatus.NOT_FOUND, "FINANCIAL_PROFILE_NOT_FOUND", "금융 프로필이 없습니다."))
        .response();
  }

  @Transactional
  public FinancialProfileResponse upsert(int userId, FinancialProfileInput input) {
    var profile = repository.findById(userId).orElseGet(() -> new FinancialProfile(userId));
    if (!profile.matches(input) && hasActiveGoal(userId)) {
      throw new ApiException(
          HttpStatus.NOT_IMPLEMENTED,
          "ACTIVE_GOAL_REPLAN_NOT_IMPLEMENTED",
          "활성 목표의 재계획 저장 기능이 준비되기 전에는 금융 정보를 변경할 수 없습니다.");
    }
    profile.update(input);
    return repository.save(profile).response();
  }

  private boolean hasActiveGoal(int userId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM financial_goals WHERE user_id=? AND status='ACTIVE')",
            Boolean.class,
            userId));
  }
}
