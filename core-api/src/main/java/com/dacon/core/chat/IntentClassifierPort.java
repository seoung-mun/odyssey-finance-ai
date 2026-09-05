package com.dacon.core.chat;

@FunctionalInterface
public interface IntentClassifierPort {
  ChatIntent classify(String message);
}
