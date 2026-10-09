package com.veridoc.ai.conversation.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.veridoc.ai.conversation.api.dto.ChatDtos.ChatRequest;
import com.veridoc.ai.conversation.api.dto.ChatDtos.ChatResponseDto;
import com.veridoc.ai.conversation.api.dto.ChatDtos.MessageDto;
import com.veridoc.ai.rag.ChatService;
import com.veridoc.ai.security.CurrentUser;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/conversations/{id}/messages")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponseDto ask(@CurrentUser AuthenticatedUser user,
                               @PathVariable("id") UUID id,
                               @Valid @RequestBody ChatRequest request) {
        return chatService.ask(user, id, request.message());
    }

    @GetMapping
    public List<MessageDto> messages(@CurrentUser AuthenticatedUser user,
                                     @PathVariable("id") UUID id) {
        return chatService.messages(user, id);
    }
}