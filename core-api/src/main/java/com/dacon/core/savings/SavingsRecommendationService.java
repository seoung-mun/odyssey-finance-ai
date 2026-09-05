package com.dacon.core.savings;

import com.dacon.core.error.ApiException;
import com.dacon.core.plan.PlanOption;
import com.dacon.core.plan.PlanOptionRepository;
import com.dacon.core.plan.PlanVersion;
import com.dacon.core.plan.PlanVersionRepository;
import com.dacon.core.savings.SavingsDtos.ConditionResponse;
import com.dacon.core.savings.SavingsDtos.RecommendationListResponse;
import com.dacon.core.savings.SavingsDtos.RecommendationResponse;
import com.dacon.core.savings.SavingsDtos.WhatIfRequest;
import com.dacon.core.savings.SavingsDtos.WhatIfResponse;
import com.dacon.core.savings.SavingsRepository.ConditionRow;
import com.dacon.core.savings.SavingsRepository.OptionRow;
import com.dacon.core.savings.SavingsRepository.ProductRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavingsRecommendationService {
  static final String TERM_FALLBACK = "선택한 적금 기간이 현재 계획의 남은 기간보다 깁니다.";

  private final PlanVersionRepository plans;
  private final PlanOptionRepository planOptions;
  private final SavingsRepository savings;

  public SavingsRecommendationService(
      PlanVersionRepository plans, PlanOptionRepository planOptions, SavingsRepository savings) {
    this.plans = plans;
    this.planOptions = planOptions;
    this.savings = savings;
  }

  @Transactional(readOnly = true)
  public RecommendationListResponse recommendations(int userId) {
    PlanContext context = context(userId);
    List<Candidate> candidates = new ArrayList<>();
    for (ProductRow product : savings.eligibleProducts()) {
      if (product.maxLimit() != null && context.monthlySavings() > product.maxLimit()) {
        continue;
      }
      savings.simpleOptions(product.id()).stream()
          .filter(option -> option.termMonths() <= context.remainingMonths())
          .map(option -> candidate(context.monthlySavings(), product, option))
          .min(Candidate.OPTION_ORDER)
          .ifPresent(candidates::add);
    }
    List<RecommendationResponse> result =
        candidates.stream().sorted(Candidate.PRODUCT_ORDER).limit(3).map(this::response).toList();
    return new RecommendationListResponse(
        context.plan().asOfDate(), context.remainingMonths(), context.monthlySavings(), result);
  }

  @Transactional(readOnly = true)
  public WhatIfResponse whatIf(int userId, long productId, WhatIfRequest request) {
    PlanContext context = context(userId);
    ProductRow product =
        savings
            .product(productId)
            .filter(value -> "1".equals(value.joinDeny()))
            .orElseThrow(this::notFound);
    if (product.maxLimit() != null && context.monthlySavings() > product.maxLimit()) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY, "SAVINGS_LIMIT_EXCEEDED", "월저축액이 상품 한도를 넘습니다.");
    }
    OptionRow option =
        savings.simpleOption(productId, request.optionId()).orElseThrow(this::notFound);
    if (new HashSet<>(request.conditionIds()).size() != request.conditionIds().size()) {
      throw invalidCondition();
    }
    List<ConditionRow> selected = savings.selectedRuleConditions(productId, request.conditionIds());
    if (selected.size() != request.conditionIds().size()) {
      throw invalidCondition();
    }
    if (option.termMonths() > context.remainingMonths()) {
      return new WhatIfResponse(
          false, TERM_FALLBACK, productId, option.id(), null, null, null, null, null, List.of());
    }
    BigDecimal bonus =
        selected.stream().map(ConditionRow::bonusRate).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal appliedRate = option.baseRate().add(bonus).min(option.maximumRate());
    long interest =
        SavingsCalculator.pretaxInterest(
            context.monthlySavings(), option.termMonths(), appliedRate);
    return new WhatIfResponse(
        true,
        null,
        productId,
        option.id(),
        option.termMonths(),
        appliedRate,
        context.monthlySavings(),
        interest,
        interest / context.monthlySavings(),
        selected.stream().map(this::conditionResponse).toList());
  }

  private PlanContext context(int userId) {
    PlanVersion plan = plans.findActiveByUserId(userId).orElseThrow(this::missingPlan);
    PlanOption selected =
        planOptions
            .findByPlanVersionIdAndSelectedAtIsNotNull(plan.id())
            .orElseThrow(this::missingPlan);
    long monthlySavings =
        SavingsCalculator.monthlySavings(
            plan.monthlyIncomeSnapshot(),
            plan.monthlyFixedCostSnapshot(),
            selected.recommendedMonthlySpending());
    int remaining = SavingsCalculator.remainingMonths(plan.asOfDate(), plan.targetDateSnapshot());
    return new PlanContext(plan, monthlySavings, remaining);
  }

  private Candidate candidate(long monthlySavings, ProductRow product, OptionRow option) {
    long interest =
        SavingsCalculator.pretaxInterest(monthlySavings, option.termMonths(), option.baseRate());
    return new Candidate(product, option, interest, interest / monthlySavings, monthlySavings);
  }

  private RecommendationResponse response(Candidate candidate) {
    ProductRow product = candidate.product();
    OptionRow option = candidate.option();
    List<ConditionResponse> conditions =
        savings.ruleConditions(product.id()).stream().map(this::conditionResponse).toList();
    return new RecommendationResponse(
        product.id(),
        option.id(),
        product.finCoNo(),
        product.finPrdtCd(),
        product.bankName(),
        product.productName(),
        option.reserveTypeName(),
        option.termMonths(),
        option.baseRate(),
        option.maximumRate(),
        candidate.monthlySavings(),
        candidate.interest(),
        candidate.acceleratedMonths(),
        conditions);
  }

  private ConditionResponse conditionResponse(ConditionRow value) {
    return new ConditionResponse(value.id(), value.label(), value.bonusRate());
  }

  private ApiException missingPlan() {
    return new ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY, "ACTIVE_PLAN_REQUIRED", "선택된 ACTIVE 계획이 필요합니다.");
  }

  private ApiException invalidCondition() {
    return new ApiException(
        HttpStatus.BAD_REQUEST, "INVALID_SAVINGS_CONDITION", "적용할 우대조건을 확인해 주세요.");
  }

  private ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다.");
  }

  private record PlanContext(PlanVersion plan, long monthlySavings, int remainingMonths) {}

  private record Candidate(
      ProductRow product,
      OptionRow option,
      long interest,
      long acceleratedMonths,
      long monthlySavings) {
    private static final Comparator<Candidate> OPTION_ORDER =
        Comparator.comparingLong(Candidate::interest)
            .reversed()
            .thenComparing(candidate -> candidate.option().baseRate(), Comparator.reverseOrder())
            .thenComparingInt(candidate -> candidate.option().termMonths())
            .thenComparing(candidate -> candidate.option().reserveType())
            .thenComparing(candidate -> candidate.option().id());

    private static final Comparator<Candidate> PRODUCT_ORDER =
        Comparator.comparingLong(Candidate::acceleratedMonths)
            .reversed()
            .thenComparing(Comparator.comparingLong(Candidate::interest).reversed())
            .thenComparing(candidate -> candidate.product().finCoNo())
            .thenComparing(candidate -> candidate.product().finPrdtCd());
  }
}
