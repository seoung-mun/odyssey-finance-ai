package com.dacon.core.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

class DemoControllerTest {
  @Test
  void exposesContractPathsAndUsesJwtUserIdForSeed() throws Exception {
    DemoService service = mock(DemoService.class);
    Jwt jwt = mock(Jwt.class);
    when(jwt.getSubject()).thenReturn("7");
    when(service.testers()).thenReturn(List.of());
    DemoController controller = new DemoController(service);

    assertThat(controller.testers()).isEmpty();
    controller.seed(jwt, new DemoDtos.DemoSeedRequest("youth"));

    verify(service).seed(7, "youth");
    assertThat(
            DemoController.class
                .getDeclaredMethod("testers")
                .getAnnotation(GetMapping.class)
                .value())
        .containsExactly("/demo/testers");
    assertThat(
            DemoController.class
                .getDeclaredMethod("seed", Jwt.class, DemoDtos.DemoSeedRequest.class)
                .getAnnotation(PostMapping.class)
                .value())
        .containsExactly("/me/demo-seed");
  }

  @Test
  void rejectsBlankTesterId() {
    assertThat(
            Validation.buildDefaultValidatorFactory()
                .getValidator()
                .validate(new DemoDtos.DemoSeedRequest(" ")))
        .isNotEmpty();
  }
}
