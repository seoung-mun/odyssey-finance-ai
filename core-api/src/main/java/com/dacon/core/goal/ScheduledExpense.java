package com.dacon.core.goal;

import com.dacon.core.auth.UserAccount;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** 예정지출과 사용자 외래키 관계를 저장한다. */
@Entity
@Table(name = "scheduled_expenses")
public class ScheduledExpense {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private UserAccount user;

  private String name;
  private long amount;
  private LocalDate scheduledDate;
  private String status;
  private Instant createdAt;
  private Instant updatedAt;

  protected ScheduledExpense() {}

  public ScheduledExpense(UserAccount user, String name, long amount, LocalDate scheduledDate) {
    this.user = user;
    this.name = name;
    this.amount = amount;
    this.scheduledDate = scheduledDate;
    status = "PLANNED";
    createdAt = Instant.now();
    updatedAt = createdAt;
  }

  public int id() {
    return id;
  }

  public long amount() {
    return amount;
  }

  public LocalDate scheduledDate() {
    return scheduledDate;
  }
}
