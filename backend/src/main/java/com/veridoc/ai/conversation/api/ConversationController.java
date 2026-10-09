package com.veridoc.ai.conversation.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.veridoc.ai.conversation.api.dto.ChatDtos.AttachDocumentsRequest;
import com.veridoc.ai.conversation.api.dto.ChatDtos.ConversationListItem;
import com.veridoc.ai.conversation.api.dto.ChatDtos.ConversationResponse;
import com.veridoc.ai.conversation.api.dto.ChatDtos.CreateConversationRequest;
import com.veridoc.ai.conversation.application.ConversationService;
import com.veridoc.ai.security.CurrentUser;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public ResponseEntity<ConversationResponse> create(
            @CurrentUser AuthenticatedUser user,
            @Valid @RequestBody CreateConversationRequest request) {
        ConversationResponse response = conversationService.create(user, request.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ConversationListItem>> list(@CurrentUser AuthenticatedUser user) {
        return ResponseEntity.ok(conversationService.list(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConversationResponse> detail(@CurrentUser AuthenticatedUser user,
                                                       @PathVariable("id") UUID id) {
        return ResponseEntity.ok(conversationService.detail(user, id));
    }

    @PostMapping("/{id}/documents")
    public ResponseEntity<ConversationResponse> attachDocuments(
            @CurrentUser AuthenticatedUser user,
            @PathVariable("id") UUID id,
            @Valid @RequestBody AttachDocumentsRequest request) {
        return ResponseEntity.ok(conversationService.attach(user, id, request.documentIds()));
    }
}