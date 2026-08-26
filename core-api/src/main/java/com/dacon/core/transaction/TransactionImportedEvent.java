package com.dacon.core.transaction;

import java.util.List;

/** 거래 commit 뒤 자동 재계획 판정을 시작하는 불변 이벤트다. */
public record TransactionImportedEvent(int userId, List<Long> paymentIds) {}
