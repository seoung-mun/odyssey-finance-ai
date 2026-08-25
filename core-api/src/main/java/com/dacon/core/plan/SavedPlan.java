package com.dacon.core.plan;

/**
 * 계획 graph 저장 결과와 후속 설명 큐 발행에 필요한 값을 전달한다.
 *
 * @param planVersionId 새로 저장된 계획 버전 식별자
 * @param inputHash 계산 입력 SHA-256 해시; 계산 불가능 계획이면 {@code null}
 */
public record SavedPlan(int planVersionId, String inputHash) {}
