package com.dacon.core.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dacon.core.goal.ScheduledExpenseRepository;
import com.dacon.core.transaction.dto.TransactionDtos.ImportResult;
import com.dacon.core.transaction.dto.TransactionDtos.RefundAllocationInput;
import com.dacon.core.transaction.dto.TransactionDtos.TransactionInput;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;

class TransactionServiceImplTest {
  @Test
  void listUsesAuthenticatedOwnerAndCursorLimit() {
    TransactionRepository transactions = mock(TransactionRepository.class);
    ScheduledExpenseRepository scheduledExpenses = mock(ScheduledExpenseRepository.class);
    TransactionService service = new TransactionServiceImpl(transactions, scheduledExpenses);
    when(transactions.findPage(4, null, null, null, 100L, PageRequest.of(0, 3)))
        .thenReturn(List.of());

    service.list(4, null, null, null, "100", 2);

    org.mockito.Mockito.verify(transactions)
        .findPage(4, null, null, null, 100L, PageRequest.of(0, 3));
  }

  @Test
  void duplicateExternalIdIsCountedAsSkipped() {
    TransactionRepository transactions = mock(TransactionRepository.class);
    ScheduledExpenseRepository scheduledExpenses = mock(ScheduledExpenseRepository.class);
    when(transactions.insertIgnoringDuplicate(
            anyInt(),
            any(),
            anyLong(),
            anyString(),
            anyString(),
            any(),
            any(),
            any(),
            anyString(),
            anyString()))
        .thenReturn(1, 0);
    when(transactions.findIdByUserIdAndSourceIdAndExternalTransactionId(4, "MANUAL", "manual-1"))
        .thenReturn(1L);
    TransactionService service = new TransactionServiceImpl(transactions, scheduledExpenses);

    ImportResult result = service.importAll(4, List.of(input("manual-1"), input("manual-1")));

    assertThat(result.inserted()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(1);
  }

  @Test
  void refundAllocationIsStoredPendingThenResolvedByExactPaymentSourceAndExternalId() {
    TransactionRepository transactions = mock(TransactionRepository.class);
    ScheduledExpenseRepository scheduledExpenses = mock(ScheduledExpenseRepository.class);
    when(transactions.insertIgnoringDuplicate(
            anyInt(),
            any(),
            anyLong(),
            anyString(),
            anyString(),
            any(),
            any(),
            any(),
            anyString(),
            anyString()))
        .thenReturn(1, 1);
    when(transactions.findIdByUserIdAndSourceIdAndExternalTransactionId(4, "CARD", "refund-1"))
        .thenReturn(20L);
    when(transactions.findIdByUserIdAndSourceIdAndExternalTransactionId(4, "CARD", "payment-1"))
        .thenReturn(null, 10L);
    when(transactions.resolvePendingAllocations(4, 10L, "CARD", "payment-1")).thenReturn(1);
    TransactionService service = new TransactionServiceImpl(transactions, scheduledExpenses);

    ImportResult result =
        service.importAll(
            4,
            List.of(
                refund("refund-1", "CARD", new RefundAllocationInput("CARD", "payment-1", 3_000L)),
                payment("payment-1", "CARD")));

    assertThat(result).isEqualTo(new ImportResult(2, 0, 1, 0));
    verify(transactions).insertRefundAllocation(4, 20L, null, "CARD", "payment-1", 3_000L);
    verify(transactions).resolvePendingAllocations(4, 10L, "CARD", "payment-1");
  }

  @Test
  void paymentCannotContainRefundAllocations() {
    TransactionRepository transactions = mock(TransactionRepository.class);
    ScheduledExpenseRepository scheduledExpenses = mock(ScheduledExpenseRepository.class);
    TransactionService service = new TransactionServiceImpl(transactions, scheduledExpenses);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                service.importAll(
                    4,
                    List.of(
                        payment(
                            "payment-1",
                            "CARD",
                            new RefundAllocationInput("CARD", "refund-1", 1L)))))
        .isInstanceOf(com.dacon.core.error.ApiException.class)
        .hasFieldOrPropertyWithValue("code", "INVALID_REFUND_ALLOCATION");
    verify(transactions, never())
        .insertIgnoringDuplicate(
            anyInt(),
            any(),
            anyLong(),
            anyString(),
            anyString(),
            any(),
            any(),
            any(),
            anyString(),
            anyString());
  }

  @Test
  void postCommitAutomationFailureDoesNotFailImportResult() {
    TransactionRepository transactions = mock(TransactionRepository.class);
    ScheduledExpenseRepository scheduledExpenses = mock(ScheduledExpenseRepository.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    when(transactions.insertIgnoringDuplicate(
            anyInt(),
            any(),
            anyLong(),
            anyString(),
            anyString(),
            any(),
            any(),
            any(),
            anyString(),
            anyString()))
        .thenReturn(1);
    when(transactions.findIdByUserIdAndSourceIdAndExternalTransactionId(4, "CARD", "payment-1"))
        .thenReturn(10L);
    doThrow(new IllegalStateException("analysis down"))
        .when(publisher)
        .publishEvent(any(Object.class));

    ImportResult result =
        new TransactionServiceImpl(transactions, scheduledExpenses, publisher)
            .importAll(4, List.of(payment("payment-1", "CARD")));

    assertThat(result.inserted()).isEqualTo(1);
  }

  private TransactionInput input(String externalId) {
    return payment(externalId, "MANUAL");
  }

  private TransactionInput payment(String externalId, String sourceId) {
    return payment(externalId, sourceId, List.of());
  }

  private TransactionInput payment(
      String externalId, String sourceId, RefundAllocationInput allocation) {
    return payment(externalId, sourceId, List.of(allocation));
  }

  private TransactionInput payment(
      String externalId, String sourceId, List<RefundAllocationInput> allocations) {
    return new TransactionInput(
        OffsetDateTime.parse("2026-05-01T12:00:00+09:00"),
        10_000L,
        TransactionType.PAYMENT,
        "식비",
        null,
        null,
        null,
        sourceId,
        externalId,
        allocations);
  }

  private TransactionInput refund(
      String externalId, String sourceId, RefundAllocationInput allocation) {
    return new TransactionInput(
        OffsetDateTime.parse("2026-05-01T12:00:00+09:00"),
        5_000L,
        TransactionType.REFUND,
        "식비",
        null,
        null,
        null,
        sourceId,
        externalId,
        List.of(allocation));
  }
}
