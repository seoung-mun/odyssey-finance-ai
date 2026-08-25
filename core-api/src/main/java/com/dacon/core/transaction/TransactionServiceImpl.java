package com.dacon.core.transaction;

import com.dacon.core.error.ApiException;
import com.dacon.core.goal.ScheduledExpenseRepository;
import com.dacon.core.transaction.dto.TransactionDtos.*;
import com.dacon.core.transaction.dto.TransactionDtos.ImportResult;
import com.dacon.core.transaction.dto.TransactionDtos.TransactionInput;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 거래 멱등 insert를 한 command transaction에서 수행한다. */
@Service
public class TransactionServiceImpl implements TransactionService {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final TransactionRepository transactions;
  private final ScheduledExpenseRepository scheduledExpenses;

  /** 거래와 예정지출 repository를 받는다. */
  public TransactionServiceImpl(
      TransactionRepository transactions, ScheduledExpenseRepository scheduledExpenses) {
    this.transactions = transactions;
    this.scheduledExpenses = scheduledExpenses;
  }

  /** 사용자 소유권을 확인하고 거래를 원자적으로 일괄 적재한다. */
  @Override
  @Transactional
  public ImportResult importAll(int userId, List<TransactionInput> inputs) {
    int inserted = 0;
    for (TransactionInput input : inputs) {
      verifyScheduledExpense(userId, input.scheduledExpenseId());
      inserted +=
          transactions.insertIgnoringDuplicate(
              userId,
              input.transactionAt(),
              input.amount(),
              input.transactionType().name(),
              input.category().trim(),
              input.merchantName(),
              input.mcc(),
              input.scheduledExpenseId(),
              input.externalTransactionId().trim());
    }
    return new ImportResult(inserted, inputs.size() - inserted);
  }

  @Override
  @Transactional(readOnly = true)
  public TransactionPage list(
      int userId,
      OffsetDateTime from,
      OffsetDateTime to,
      String category,
      String cursor,
      int limit) {
    long cursorId;
    try {
      cursorId = cursor == null ? Long.MAX_VALUE : Long.parseLong(cursor);
    } catch (NumberFormatException exception) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "커서를 확인해 주세요.");
    }
    List<Transaction> values =
        transactions.findPage(userId, from, to, category, cursorId, PageRequest.of(0, limit + 1));
    boolean more = values.size() > limit;
    List<Transaction> page = more ? values.subList(0, limit) : values;
    List<TransactionResponse> items = page.stream().map(this::response).toList();
    return new TransactionPage(items, more ? Long.toString(page.getLast().id()) : null);
  }

  @Override
  @Transactional(readOnly = true)
  public List<MonthlySpending> monthlySummary(int userId, int months) {
    OffsetDateTime to =
        YearMonth.now(KST).plusMonths(1).atDay(1).atStartOfDay(KST).toOffsetDateTime();
    OffsetDateTime from =
        YearMonth.now(KST).minusMonths(months - 1L).atDay(1).atStartOfDay(KST).toOffsetDateTime();
    return transactions.monthlySummary(userId, from, to).stream()
        .map(
            value ->
                new MonthlySpending(
                    value.getYearMonth(),
                    value.getTotalVariableSpending(),
                    value.getBootstrapEligibleSpending()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public CategorySummary categorySummary(int userId, int months) {
    OffsetDateTime to =
        YearMonth.now(KST).plusMonths(1).atDay(1).atStartOfDay(KST).toOffsetDateTime();
    OffsetDateTime from =
        YearMonth.now(KST).minusMonths(months - 1L).atDay(1).atStartOfDay(KST).toOffsetDateTime();
    List<TransactionRepository.CategoryTotalView> totals =
        transactions.categoryTotals(userId, from, to);
    List<CategorySpending> categories =
        totals.stream()
            .map(
                value ->
                    new CategorySpending(
                        value.getCategory(), Math.round(value.getTotal() / (double) months)))
            .toList();
    long average =
        Math.round(
            totals.stream().mapToLong(TransactionRepository.CategoryTotalView::getTotal).sum()
                / (double) months);
    return new CategorySummary(months, average, categories);
  }

  private TransactionResponse response(Transaction value) {
    return new TransactionResponse(
        value.id(),
        value.transactionAt(),
        value.amount(),
        value.transactionType(),
        value.category(),
        value.merchantName(),
        value.mcc(),
        value.scheduledExpenseId(),
        value.externalTransactionId());
  }

  private void verifyScheduledExpense(int userId, Integer scheduledExpenseId) {
    if (scheduledExpenseId != null
        && !scheduledExpenses.existsByIdAndUserId(scheduledExpenseId, userId)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SCHEDULED_EXPENSE", "예정지출을 확인해 주세요.");
    }
  }
}
