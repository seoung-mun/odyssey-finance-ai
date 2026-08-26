package com.dacon.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class HttpClientConfigTest {
  @Test
  void sharedClientUsesHttp11ForUvicornRequestBodies() {
    assertThat(new HttpClientConfig().httpClient().version())
        .isEqualTo(HttpClient.Version.HTTP_1_1);
  }
}
