package com.dacon.core.policy;

import com.dacon.core.plan.FutureCashflowAdjustment;
import com.dacon.core.policy.PolicyBenefitDtos.PolicyBenefitResponse;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** Stage 2의 confirmed 조회 결과를 Planning이 소비하는 미래 현금흐름 조정으로 변환한다. */
@Component
public class PolicyBenefitAdjustmentReader {
  private static final Comparator<FutureCashflowAdjustment> ORDER =
      Comparator.comparing(FutureCashflowAdjustment::startYearMonth)
          .thenComparingLong(FutureCashflowAdjustment::policyBenefitId);

  private final PolicyBenefitService benefits;

  public PolicyBenefitAdjustmentReader(PolicyBenefitService benefits) {
    this.benefits = benefits;
  }

  /** 현재 user/goal의 CONFIRMED benefit만 읽어 결정적 순서의 불변 목록으로 반환한다. */
  public List<FutureCashflowAdjustment> read(int userId, int goalId) {
    return benefits.list(userId, goalId).stream().map(this::map).sorted(ORDER).toList();
  }

  private FutureCashflowAdjustment map(PolicyBenefitResponse benefit) {
    if (!"CONFIRMED".equals(benefit.status())) {
      throw new IllegalStateException("confirmed 정책 혜택 조회가 유효하지 않은 상태를 반환했습니다.");
    }
    return new FutureCashflowAdjustment(
        FutureCashflowAdjustment.POLICY_BENEFIT_SOURCE,
        benefit.id(),
        benefit.policyVersionId(),
        benefit.adjustmentType(),
        benefit.amountWon(),
        benefit.startYearMonth(),
        benefit.endYearMonth(),
        benefit.confirmedAt());
  }
}
