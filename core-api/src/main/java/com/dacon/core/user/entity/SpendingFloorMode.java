package com.dacon.core.user.entity;

/** 계획 계산에서 월 유동지출 하한을 적용하지 않을지, 자동 산출할지, 원 단위로 직접 지정할지 구분한다. */
public enum SpendingFloorMode {
  /** 월 유동지출 하한을 적용하지 않는다. */
  OFF,

  /** 시스템이 확정한 기준으로 월 유동지출 하한을 산출한다. */
  AUTO,

  /** 사용자가 원 단위 월 유동지출 하한을 직접 지정한다. */
  CUSTOM
}
