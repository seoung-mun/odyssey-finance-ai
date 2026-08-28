package com.dacon.core.user.entity;

/** 금융정보 저장으로 재계획이 불필요했는지, 제안이 생겼는지, 달성이 불가능한지 구분한다. */
public enum ReplanOutcome {
  /** 입력이 같거나 ACTIVE 목표가 없어 재계획이 필요하지 않다. */
  NOT_REQUIRED,

  /** 변경에 대응하는 새 계획 제안이 생성됐다. */
  PROPOSED,

  /** 변경된 조건으로 목표 달성 가능한 계획을 만들 수 없다. */
  INFEASIBLE
}
