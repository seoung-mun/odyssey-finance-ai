package com.dacon.core.policy;

import com.dacon.core.error.ApiException;
import com.dacon.core.goal.FinancialGoalRepository;
import com.dacon.core.policy.PolicyBenefitDtos.ConfirmPolicyBenefitRequest;
import com.dacon.core.policy.PolicyBenefitDtos.PolicyBenefitResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyBenefitService {
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private final PolicyBenefitRepository repository;
  private final FinancialGoalRepository goals;
  private final Clock clock;

  @Autowired
  public PolicyBenefitService(PolicyBenefitRepository repository, FinancialGoalRepository goals) {
    this(repository, goals, Clock.system(SEOUL));
  }

  PolicyBenefitService(
      PolicyBenefitRepository repository, FinancialGoalRepository goals, Clock clock) {
    this.repository = repository;
    this.goals = goals;
    this.clock = clock;
  }

  @Transactional
  public PolicyBenefitResponse confirm(
      int userId, long versionId, ConfirmPolicyBenefitRequest input) {
    if (!input.institutionConfirmed() || input.amountWon() <= 0 || input.startYearMonth() == null) {
      throw invalid();
    }
    goals.findActiveForUpdate(userId, input.goalId()).orElseThrow(this::notFound);
    PolicyBenefitResponse current = repository.active(userId, input.goalId(), versionId);
    if (current != null) {
      if (same(current, input)) return current;
      throw new ApiException(
          HttpStatus.CONFLICT, "POLICY_BENEFIT_CONFLICT", "이미 다른 내용으로 확인한 정책 혜택입니다.");
    }
    PolicyBenefitRepository.ConfirmationPolicy policy = repository.policy(userId, versionId);
    LocalDate today = LocalDate.now(clock);
    if (!validPolicy(policy, today)) throw invalid();
    validateRule(policy, input);
    return repository.insert(
        userId,
        input.goalId(),
        versionId,
        policy.adjustmentType(),
        input.amountWon(),
        input.startYearMonth(),
        input.endYearMonth(),
        clock.instant());
  }

  @Transactional(readOnly = true)
  public java.util.List<PolicyBenefitResponse> list(int userId, int goalId) {
    if (goals.findByIdAndUserId(goalId, userId).filter(g -> "ACTIVE".equals(g.status())).isEmpty())
      throw notFound();
    return repository.list(userId, goalId);
  }

  @Transactional
  public PolicyBenefitResponse cancel(int userId, long id) {
    PolicyBenefitResponse current = repository.find(userId, id);
    if (current == null) throw notFound();
    return "CANCELLED".equals(current.status()) ? current : repository.cancel(userId, id);
  }

  private boolean validPolicy(PolicyBenefitRepository.ConfirmationPolicy p, LocalDate today) {
    return p != null
        && p.activeSnapshot()
        && "APPROVED".equals(p.reviewStatus())
        && p.adjustmentType() != null
        && p.adjustmentType().equals(p.calculationMode())
        && (p.effectiveFrom() == null || !today.isBefore(p.effectiveFrom()))
        && (p.effectiveTo() == null || !today.isAfter(p.effectiveTo()))
        && PolicyEligibilityEvaluator.isEligible(
            p.decision(),
            p.regionScope(),
            p.regionCodes(),
            p.ageMin(),
            p.ageMax(),
            p.userRegionCode(),
            p.birthDate(),
            today);
  }

  private void validateRule(
      PolicyBenefitRepository.ConfirmationPolicy p, ConfirmPolicyBenefitRequest i) {
    if (p.amountUpperBound() == null
        || p.amountUpperBound().compareTo(java.math.BigDecimal.valueOf(i.amountWon())) < 0)
      throw invalid();
    if ("ONE_TIME_FUNDING".equals(p.adjustmentType())) {
      if (i.endYearMonth() != null) throw invalid();
      return;
    }
    if (!"MONTHLY_EXPENSE_REDUCTION".equals(p.adjustmentType())
        || i.endYearMonth() == null
        || i.endYearMonth().isBefore(i.startYearMonth())
        || p.maxMonths() == null
        || ChronoUnit.MONTHS.between(i.startYearMonth(), i.endYearMonth()) + 1 > p.maxMonths())
      throw invalid();
  }

  private boolean same(PolicyBenefitResponse b, ConfirmPolicyBenefitRequest i) {
    return b.amountWon() == i.amountWon()
        && b.startYearMonth().equals(i.startYearMonth())
        && java.util.Objects.equals(b.endYearMonth(), i.endYearMonth());
  }

  private ApiException invalid() {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_POLICY_BENEFIT", "확인할 수 없는 정책 혜택입니다.");
  }

  private ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "POLICY_BENEFIT_NOT_FOUND", "정책 혜택을 찾을 수 없습니다.");
  }
}
