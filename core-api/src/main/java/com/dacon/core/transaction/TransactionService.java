package com.dacon.core.transaction;

import com.dacon.core.transaction.dto.TransactionDtos.CategorySummary;
import com.dacon.core.transaction.dto.TransactionDtos.ImportResult;
import com.dacon.core.transaction.dto.TransactionDtos.MonthlySpending;
import com.dacon.core.transaction.dto.TransactionDtos.TransactionInput;
import com.dacon.core.transaction.dto.TransactionDtos.TransactionPage;
import java.time.OffsetDateTime;
import java.util.List;

/** 거래 import 유스케이스 계약이다. */
public interface TransactionService {
  ImportResult importAll(int userId, List<TransactionInput> inputs);

  TransactionPage list(
      int userId,
      OffsetDateTime from,
      OffsetDateTime to,
      String category,
      String cursor,
      int limit);

  List<MonthlySpending> monthlySummary(int userId, int months);

  CategorySummary categorySummary(int userId, int months);
}
