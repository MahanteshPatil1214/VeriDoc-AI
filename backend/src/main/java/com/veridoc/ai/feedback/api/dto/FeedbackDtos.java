package com.veridoc.ai.feedback.api.dto;

import java.util.UUID;

import com.veridoc.ai.feedback.domain.FeedbackRating;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class FeedbackDtos {

    private FeedbackDtos() {
    }

    /**
     * A thumb plus an optional note. Submitting again updates the existing row
     * ({@code ux_feedback_message_user} keeps it one rating per user/message).
     */
    @Schema(name = "FeedbackRequest")
    public record FeedbackRequest(

            @NotNull(message = "rating is required")
            FeedbackRating rating,

            @Size(max = 2000, message = "comment must not exceed 2000 characters")
            String comment
    ) {
    }

    @Schema(name = "FeedbackResponse")
    public record FeedbackResponse(
            UUID id,
            UUID messageId,
            String rating,
            String comment
    ) {
    }
}
