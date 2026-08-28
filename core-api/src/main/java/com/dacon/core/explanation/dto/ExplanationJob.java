package com.dacon.core.explanation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FastAPI 설명 요청과 숫자 검증에 필요한 불변 계획 스냅샷이다.
 *
 * @param planVersionId 설명 대상 계획 버전 식별자
 * @param status 현재 설명 처리 상태
 * @param inputHash 저장된 계산 입력의 SHA-256 해시
 * @param promptVersion 설명 생성에 사용할 프롬프트 버전
 * @param recommendedMonthlySpending 기준 PRESET 옵션의 권장 월 지출액; 조인 결과에 값이 없으면 {@code null}
 * @param currentAvgVariableSpending 계산 당시 월평균 유동지출
 * @param targetAmount 계산 당시 목표 금액
 * @param currentSavedAmount 계산 당시 저축 금액
 * @param asOfDate 계획 계산 기준일
 * @param targetDate 목표 달성 예정일
 * @param simulationCoverage 기준 옵션의 목표 달성 시뮬레이션 비율; 조인 결과에 값이 없으면 {@code null}
 * @param aggressiveWarning 기준 옵션의 과도한 절감 경고 여부; 조인 결과에 값이 없으면 {@code null}
 */
public record ExplanationJob(
    long planVersionId,
    String status,
    String inputHash,
    String promptVersion,
    Long recommendedMonthlySpending,
    long currentAvgVariableSpending,
    long targetAmount,
    long currentSavedAmount,
    LocalDate asOfDate,
    LocalDate targetDate,
    BigDecimal simulationCoverage,
    Boolean aggressiveWarning) {}
