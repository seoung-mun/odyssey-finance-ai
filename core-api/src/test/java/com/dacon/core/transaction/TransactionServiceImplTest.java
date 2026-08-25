package com.dacon.core.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dacon.core.goal.ScheduledExpenseRepository;
import com.dacon.core.transaction.dto.TransactionDtos.ImportResult;
import com.dacon.core.transaction.dto.TransactionDtos.TransactionInput;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
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
            anyInt(), any(), anyLong(), anyString(), anyString(), any(), any(), any(), anyString()))
        .thenReturn(1, 0);
    TransactionService service = new TransactionServiceImpl(transactions, scheduledExpenses);

    ImportResult result = service.importAll(4, List.of(input("manual-1"), input("manual-1")));

    assertThat(result.inserted()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(1);
  }

  private TransactionInput input(String externalId) {
    return new TransactionInput(
        OffsetDateTime.parse("2026-05-01T12:00:00+09:00"),
        10_000L,
        TransactionType.PAYMENT,
        "식비",
        null,
        null,
        null,
        externalId);
  }
}
