package com.veridoc.ai.feedback.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.veridoc.ai.feedback.api.dto.FeedbackDtos.FeedbackRequest;
import com.veridoc.ai.feedback.api.dto.FeedbackDtos.FeedbackResponse;
import com.veridoc.ai.feedback.application.FeedbackService;
import com.veridoc.ai.security.CurrentUser;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

/**
 * Thumbs up/down on an assistant answer. Scoped under the owning conversation so
 * the same ownership check as the thread applies.
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages/{messageId}/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PutMapping
    @Operation(summary = "Rate an assistant answer (upserts the caller's rating)")
    public ResponseEntity<FeedbackResponse> submit(
            @CurrentUser AuthenticatedUser user,
            @PathVariable("conversationId") UUID conversationId,
            @PathVariable("messageId") UUID messageId,
            @Valid @RequestBody FeedbackRequest request) {
        return ResponseEntity.ok(
                feedbackService.submit(user, conversationId, messageId, request));
    }

    @DeleteMapping
    @Operation(summary = "Remove the caller's rating from an assistant answer")
    public ResponseEntity<Void> clear(
            @CurrentUser AuthenticatedUser user,
            @PathVariable("conversationId") UUID conversationId,
            @PathVariable("messageId") UUID messageId) {
        feedbackService.clear(user, conversationId, messageId);
        return ResponseEntity.noContent().build();
    }
}
