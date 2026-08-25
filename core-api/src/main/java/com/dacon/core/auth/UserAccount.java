package com.dacon.core.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
class UserAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  protected UserAccount() {}

  int id() {
    return id;
  }
}
