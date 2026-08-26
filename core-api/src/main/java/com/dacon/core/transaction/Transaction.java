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

/** 사용자의 외부 거래 한 건과 선택적인 사용자 소유 예정지출 연결을 저장한다. 금액 부호는 거래 종류가 담당한다. */
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

  private String sourceId;
  private String externalTransactionId;
  private Instant createdAt;

  /** JPA가 영속 상태를 복원할 때만 사용하는 생성자다. */
  protected Transaction() {}

  /**
   * 커서 페이지네이션에 사용할 거래 식별자를 제공한다.
   *
   * @return DB가 할당한 거래 ID
   */
  public long id() {
    return id;
  }

  /** 거래 소유 사용자의 내부 식별자를 제공한다. */
  public int userId() {
    return user.id();
  }

  /**
   * 기간 조회와 월 집계의 기준이 되는 거래 시각을 제공한다.
   *
   * @return 원본 거래 시각과 offset
   */
  public OffsetDateTime transactionAt() {
    return transactionAt;
  }

  /**
   * 거래 종류와 결합해 순 소비를 계산할 절댓값을 제공한다.
   *
   * @return 항상 양수인 거래 금액(원)
   */
  public long amount() {
    return amount;
  }

  /**
   * 금액을 순 소비에 더할지 뺄지 결정하는 종류를 제공한다.
   *
   * @return PAYMENT 또는 REFUND 거래 종류
   */
  public String transactionType() {
    return transactionType;
  }

  /**
   * 원본 거래에 업종 코드가 있으면 이를 제공한다.
   *
   * @return 원본 업종 코드, 없으면 {@code null}
   */
  public Short mcc() {
    return mcc;
  }

  /**
   * 소비 분석과 필터에 사용할 서비스 카테고리를 제공한다.
   *
   * @return 서비스 소비 카테고리
   */
  public String category() {
    return category;
  }

  /**
   * 화면에 표시할 원본 가맹점 정보를 제공한다.
   *
   * @return 가맹점 이름, 없으면 {@code null}
   */
  public String merchantName() {
    return merchantName;
  }

  /**
   * 확정 예정지출로 분리할 연결 대상을 제공한다.
   *
   * @return 연결된 예정지출 ID, 일반 유동지출이면 {@code null}
   */
  public Integer scheduledExpenseId() {
    return scheduledExpense == null ? null : scheduledExpense.id();
  }

  /**
   * 동일 사용자 거래의 재적재를 판정할 외부 식별자를 제공한다.
   *
   * @return 사용자 범위 멱등 키로 쓰는 외부 거래 ID
   */
  public String externalTransactionId() {
    return externalTransactionId;
  }

  /** 외부 거래 공급원 식별자를 제공한다. */
  public String sourceId() {
    return sourceId;
  }
}
