package com.dacon.core.transaction;

import com.dacon.core.auth.UserAccount;
import com.dacon.core.goal.ScheduledExpense;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.OffsetDateTime;

/** 거래와 사용자·예정지출 외래키 관계를 저장한다. */
@Entity
@Table(
    name = "transactions",
    uniqueConstraints = @UniqueConstraint(columnNames = {"id", "user_id"}))
public class Transaction {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private UserAccount user;

  private OffsetDateTime transactionAt;
  private long amount;
  private String transactionType;
  private Short mcc;
  private String category;
  private String merchantName;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "scheduled_expense_id")
  private ScheduledExpense scheduledExpense;

  private String externalTransactionId;
  private Instant createdAt;

  protected Transaction() {}

  public long id() {
    return id;
  }

  public OffsetDateTime transactionAt() {
    return transactionAt;
  }

  public long amount() {
    return amount;
  }

  public String transactionType() {
    return transactionType;
  }

  public Short mcc() {
    return mcc;
  }

  public String category() {
    return category;
  }

  public String merchantName() {
    return merchantName;
  }

  public Integer scheduledExpenseId() {
    return scheduledExpense == null ? null : scheduledExpense.id();
  }

  public String externalTransactionId() {
    return externalTransactionId;
  }
}
