package com.dacon.core.chat;

import com.dacon.core.chat.ChatDtos.ChatRequest;
import com.dacon.core.chat.ChatDtos.ChatResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
  private final ChatService service;

  public ChatController(ChatService service) {
    this.service = service;
  }

  @PostMapping("/messages")
  public ChatResponse message(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChatRequest request) {
    return service.respond(Integer.parseInt(jwt.getSubject()), request);
  }
}
