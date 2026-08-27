package com.dacon.core.transaction;

import com.dacon.core.error.ApiException;
import com.dacon.core.goal.ScheduledExpenseRepository;
import com.dacon.core.transaction.dto.TransactionDtos.CategorySpending;
import com.dacon.core.transaction.dto.TransactionDtos.CategorySummary;
import com.dacon.core.transaction.dto.TransactionDtos.ImportResult;
import com.dacon.core.transaction.dto.TransactionDtos.MonthlySpending;
import com.dacon.core.transaction.dto.TransactionDtos.RefundAllocation;
import com.dacon.core.transaction.dto.TransactionDtos.RefundAllocationInput;
import com.dacon.core.transaction.dto.TransactionDtos.TransactionInput;
import com.dacon.core.transaction.dto.TransactionDtos.TransactionPage;
import com.dacon.core.transaction.dto.TransactionDtos.TransactionResponse;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL 충돌 무시 삽입으로 거래 일괄 적재의 멱등성을 보장하고 읽기 집계를 구성한다. */
@Service
public class TransactionServiceImpl implements TransactionService {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final TransactionRepository transactions;
  private final ScheduledExpenseRepository scheduledExpenses;
  private final ApplicationEventPublisher events;

  /**
   * 거래 저장소와 예정지출 소유권 확인 저장소로 유스케이스를 구성한다.
   *
   * @param transactions 거래 저장·집계 저장소
   * @param scheduledExpenses 연결 예정지출의 사용자 소유권 확인 저장소
   */
  @Autowired
  public TransactionServiceImpl(
      TransactionRepository transactions,
      ScheduledExpenseRepository scheduledExpenses,
      ApplicationEventPublisher events) {
    this.transactions = transactions;
    this.scheduledExpenses = scheduledExpenses;
    this.events = events;
  }

  // 테스트용 생성자
  TransactionServiceImpl(
      TransactionRepository transactions, ScheduledExpenseRepository scheduledExpenses) {
    this.transactions = transactions;
    this.scheduledExpenses = scheduledExpenses;
    this.events = event -> {};
  }

  /**
   * {@inheritDoc}
   *
   * <p>각 예정지출의 사용자 소유권을 먼저 확인하며 어느 입력이든 실패하면 일괄 삽입 전체가 rollback된다.
   */
  /** {@inheritDoc} */
  @Override
  @Transactional
  public ImportResult importAll(int userId, List<TransactionInput> inputs) {
    inputs.forEach(this::validateRefundAllocations);
    int inserted = 0;
    int allocationsInserted = 0;
    int allocationsPending = 0;
    List<Long> paymentIds = new ArrayList<>();
    for (TransactionInput input : inputs) {
      verifyScheduledExpense(userId, input.scheduledExpenseId());
      int insertedTransaction =
          transactions.insertIgnoringDuplicate(
              userId,
              input.transactionAt(),
              input.amount(),
              input.transactionType().name(),
              input.category().trim(),
              input.merchantName(),
              input.mcc(),
              input.scheduledExpenseId(),
              input.sourceId().trim(),
              input.externalTransactionId().trim());
      inserted += insertedTransaction;
      if (insertedTransaction == 1 && input.transactionType() == TransactionType.REFUND) {
        long refundId =
            transactions.findIdByUserIdAndSourceIdAndExternalTransactionId(
                userId, input.sourceId().trim(), input.externalTransactionId().trim());
        for (RefundAllocationInput allocation : allocations(input)) {
          Long paymentId =
              transactions.findIdByUserIdAndSourceIdAndExternalTransactionId(
                  userId,
                  allocation.paymentSourceId().trim(),
                  allocation.paymentExternalTransactionId().trim());
          transactions.insertRefundAllocation(
              userId,
              refundId,
              paymentId,
              allocation.paymentSourceId().trim(),
              allocation.paymentExternalTransactionId().trim(),
              allocation.amount());
          allocationsInserted++;
          if (paymentId == null) {
            allocationsPending++;
          }
        }
      }
      if (insertedTransaction == 1 && input.transactionType() == TransactionType.PAYMENT) {
        long paymentId =
            transactions.findIdByUserIdAndSourceIdAndExternalTransactionId(
                userId, input.sourceId().trim(), input.externalTransactionId().trim());
        allocationsPending =
            Math.max(
                0,
                allocationsPending
                    - transactions.resolvePendingAllocations(
                        userId,
                        paymentId,
                        input.sourceId().trim(),
                        input.externalTransactionId().trim()));
        paymentIds.add(paymentId);
      }
    }
    publishAfterCommit(userId, paymentIds);
    return new ImportResult(
        inserted, inputs.size() - inserted, allocationsInserted, allocationsPending);
  }

  private void publishAfterCommit(int userId, List<Long> paymentIds) {
    if (paymentIds.isEmpty()) {
      return;
    }
    TransactionImportedEvent event = new TransactionImportedEvent(userId, List.copyOf(paymentIds));
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      try {
        events.publishEvent(event);
      } catch (RuntimeException ignored) {
        // 트랜잭션 없는 직접 호출에서도 후속 자동화 실패를 import 실패로 바꾸지 않는다.
      }
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              events.publishEvent(event);
            } catch (RuntimeException ignored) {
              // 거래 commit 뒤 자동 재계획 실패는 import 응답 실패로 역전시키지 않는다.
            }
          }
        });
  }

  /** {@inheritDoc} */
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

  /** {@inheritDoc} */
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
                    value.getGrossPaymentSpending(),
                    value.getLinkedRefundAmount(),
                    value.getUnmatchedRefundInflow(),
                    value.getAdjustedConsumption(),
                    value.getNetCashFlow(),
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

  /**
   * 영속 거래를 외부 응답으로 복사하되 연관 엔티티 대신 예정지출 ID만 노출한다.
   *
   * @param value 변환할 사용자 소유 거래
   * @return API 거래 항목
   */
  private TransactionResponse response(Transaction value) {
    List<RefundAllocation> allocations =
        transactions.findAllocations(value.userId(), value.id()).stream()
            .map(
                allocation ->
                    new RefundAllocation(
                        allocation.getId(),
                        allocation.getPaymentTransactionId(),
                        allocation.getPaymentSourceId(),
                        allocation.getPaymentExternalTransactionId(),
                        allocation.getAmount(),
                        allocation.getStatus()))
            .toList();
    long resolved =
        allocations.stream()
            .filter(allocation -> allocation.status().equals("RESOLVED"))
            .mapToLong(RefundAllocation::amount)
            .sum();
    long pending =
        allocations.stream()
            .filter(allocation -> allocation.status().equals("PENDING"))
            .mapToLong(RefundAllocation::amount)
            .sum();
    long unmatched =
        value.transactionType().equals("REFUND") ? value.amount() - resolved - pending : 0;
    return new TransactionResponse(
        value.id(),
        value.transactionAt(),
        value.amount(),
        value.transactionType(),
        value.category(),
        value.merchantName(),
        value.mcc(),
        value.scheduledExpenseId(),
        value.sourceId(),
        value.externalTransactionId(),
        refundStatus(value, resolved, pending, unmatched),
        resolved,
        pending,
        unmatched,
        allocations);
  }

  private void validateRefundAllocations(TransactionInput input) {
    if (input.transactionType() != TransactionType.REFUND && !allocations(input).isEmpty()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "INVALID_REFUND_ALLOCATION", "환불 거래만 결제 연결을 포함할 수 있습니다.");
    }
    long total = 0;
    for (RefundAllocationInput allocation : allocations(input)) {
      try {
        total = Math.addExact(total, allocation.amount());
      } catch (ArithmeticException exception) {
        throw new ApiException(
            HttpStatus.BAD_REQUEST, "INVALID_REFUND_ALLOCATION", "환불 배분 금액을 확인해 주세요.");
      }
    }
    if (total > input.amount()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "INVALID_REFUND_ALLOCATION", "환불 배분 금액을 확인해 주세요.");
    }
  }

  private String refundStatus(Transaction value, long resolved, long pending, long unmatched) {
    if (!value.transactionType().equals("REFUND")) {
      return "NOT_APPLICABLE";
    }
    if (resolved == value.amount()) {
      return "LINKED";
    }
    if (resolved > 0) {
      return "PARTIALLY_LINKED";
    }
    return pending > 0 ? "PENDING" : unmatched > 0 ? "UNMATCHED" : "LINKED";
  }

  private List<RefundAllocationInput> allocations(TransactionInput input) {
    return input.refundAllocations() == null ? List.of() : input.refundAllocations();
  }

  /**
   * 선택된 예정지출이 인증 사용자 소유인지 확인해 사용자 간 연결을 차단한다.
   *
   * @param userId 인증된 사용자 ID
   * @param scheduledExpenseId 연결할 예정지출 ID, 연결하지 않으면 {@code null}
   * @throws ApiException 예정지출 ID와 소유권이 함께 일치하지 않는 경우
   */
  private void verifyScheduledExpense(int userId, Integer scheduledExpenseId) {
    if (scheduledExpenseId != null
        && !scheduledExpenses.existsByIdAndUserId(scheduledExpenseId, userId)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SCHEDULED_EXPENSE", "예정지출을 확인해 주세요.");
    }
  }
}
