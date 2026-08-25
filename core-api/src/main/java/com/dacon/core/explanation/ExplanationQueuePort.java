package com.dacon.core.explanation;

/** 설명 작업 식별자를 비동기 queue에 발행하는 port다. */
public interface ExplanationQueuePort {
  boolean publish(long planVersionId, String inputHash, String promptVersion);
}
