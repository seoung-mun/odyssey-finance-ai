package com.dacon.core.plan;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * CUSTOM 원격 계산 전후에 계획과 시뮬레이션 입력의 동일성을 확인하는 스냅샷이다.
 *
 * @param planVersionId CUSTOM 옵션을 추가할 계획 버전 식별자
 * @param status 원격 호출 전 계획 상태
 * @param inputHash 저장된 시뮬레이션 입력 SHA-256 해시
 * @param inputSnapshot 원래 계획 계산에 사용한 입력 JSON의 방어적 복사본
 * @param horizonMonths CUSTOM 분위수 밴드가 채워야 할 계산 개월 수
 */
public record CustomOptionSnapshot(
    int planVersionId,
    String status,
    String inputHash,
    JsonNode inputSnapshot,
    int horizonMonths) {}
