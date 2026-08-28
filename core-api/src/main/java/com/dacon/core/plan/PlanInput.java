package com.dacon.core.plan;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;

/**
 * 계획 계산 직전 DB에서 조립한 입력 스냅샷이다. 저장 트랜잭션은 같은 값을 잠금 아래 다시 만들어 입력 변경을 감지한다.
 *
 * @param userId 목표 소유 사용자 식별자
 * @param goalId 계산 대상 목표 식별자
 * @param goalName 계산 당시 목표 이름
 * @param targetAmount 계산 당시 목표 금액
 * @param currentSavedAmount 계산 당시 저축 금액
 * @param targetDate 목표 달성 예정일
 * @param monthlyIncome 계산 당시 월 소득
 * @param monthlyFixedCost 계산 당시 월 고정비
 * @param history 현재 달을 제외한 과거 월별 유동지출 목록
 * @param scheduledExpenses 기준일부터 목표일까지 남은 예정지출 목록
 * @param horizonMonths 현재 달과 목표 달을 포함한 계산 개월 수
 * @param periodRatios 첫 달과 마지막 달의 부분 기간을 반영한 월별 비율
 * @param availableVariableBudget 목표 저축과 고정비·예정지출·기지출을 차감한 전체 가용 유동지출
 * @param currentMonthSpent 현재 달 기준 시각까지의 순지출
 * @param currentAverage 과거 월별 유동지출의 반올림 평균
 * @param policySnapshot 계산 당시 정책 파라미터 JSON
 * @param profileComplete 인적 프로필 존재 여부
 * @param planState 입력 변경 감지에 포함할 최신 계획 상태 표식
 */
public record PlanInput(
    int userId,
    int goalId,
    String goalName,
    long targetAmount,
    long currentSavedAmount,
    LocalDate targetDate,
    long monthlyIncome,
    long monthlyFixedCost,
    List<Long> history,
    List<ScheduledInput> scheduledExpenses,
    int horizonMonths,
    List<Double> periodRatios,
    long availableVariableBudget,
    long currentMonthSpent,
    long currentAverage,
    JsonNode policySnapshot,
    boolean profileComplete,
    String planState) {
  /**
   * 계산 기간 안에 남은 예정지출 한 건이다.
   *
   * @param monthIndex 계획 시작을 1로 세는 지출 예정 월
   * @param amount 해당 월에 차감할 원 단위 금액
   */
  public record ScheduledInput(int monthIndex, long amount) {}
}
