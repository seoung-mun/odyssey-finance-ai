package com.dacon.core.transaction;

/** 항상 양수로 저장한 거래 금액을 순 소비 집계에서 더할지 뺄지 구분한다. */
public enum TransactionType {
  /** 순 소비 집계에서 금액을 더하는 지출 거래다. */
  PAYMENT,

  /** 순 소비 집계에서 금액을 빼는 환불 거래다. */
  REFUND
}
