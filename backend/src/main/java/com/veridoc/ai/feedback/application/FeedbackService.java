package com.veridoc.ai.feedback.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.conversation.persistence.ConversationRepository;
import com.veridoc.ai.feedback.api.dto.FeedbackDtos.FeedbackRequest;
import com.veridoc.ai.feedback.api.dto.FeedbackDtos.FeedbackResponse;
import com.veridoc.ai.feedback.domain.Feedback;
import com.veridoc.ai.feedback.persistence.FeedbackRepository;
import com.veridoc.ai.message.domain.Message;
import com.veridoc.ai.message.domain.MessageRole;
import com.veridoc.ai.message.domain.MessageStatus;
import com.veridoc.ai.message.persistence.MessageRepository;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Ratings on assistant answers.
 *
 * <p>Ownership is checked through the conversation, never through the ids in
 * the URL alone: a foreign message is hidden as {@code 404}, the same way the
 * chat endpoints hide foreign conversations.
 */
@Service
public class FeedbackService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final FeedbackRepository feedbackRepository;
    private final MeterRegistry meterRegistry;

    public FeedbackService(ConversationRepository conversationRepository,
                           MessageRepository messageRepository,
                           FeedbackRepository feedbackRepository,
                           MeterRegistry meterRegistry) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.feedbackRepository = feedbackRepository;
        this.meterRegistry = meterRegistry;
    }

    /** Upserts the caller's rating for one completed assistant answer. */
    @Transactional
    public FeedbackResponse submit(AuthenticatedUser user, UUID conversationId, UUID messageId,
                                   FeedbackRequest request) {
        requireRatableMessage(user, conversationId, messageId);

        Feedback feedback = feedbackRepository
                .findByMessageIdAndUserId(messageId, user.userId())
                .map(existing -> {
                    existing.update(request.rating(), request.comment());
                    return existing;
                })
                .orElseGet(() -> Feedback.create(UUID.randomUUID(), messageId, user.userId(),
                        request.rating(), request.comment()));
        feedbackRepository.save(feedback);

        meterRegistry.counter("veridoc.feedback", "rating", request.rating().name()).increment();
        return new FeedbackResponse(feedback.getId(), messageId, feedback.getRating().name(),
                feedback.getComment());
    }

    /** Removes the caller's rating; a missing rating is still a success. */
    @Transactional
    public void clear(AuthenticatedUser user, UUID conversationId, UUID messageId) {
        requireRatableMessage(user, conversationId, messageId);
        feedbackRepository.findByMessageIdAndUserId(messageId, user.userId())
                .ifPresent(feedbackRepository::delete);
    }

    private Message requireRatableMessage(AuthenticatedUser user, UUID conversationId,
                                          UUID messageId) {
        conversationRepository.findByIdAndOwnerId(conversationId, user.userId())
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND,
                        "Conversation not found"));

        Message message = messageRepository.findByIdAndConversationId(messageId, conversationId)
                .orElseThrow(() -> new AppException(ErrorCode.MESSAGE_NOT_FOUND,
                        "Message not found"));

        if (message.getRole() != MessageRole.ASSISTANT) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Only assistant answers can be rated.");
        }
        if (message.getStatus() != MessageStatus.COMPLETE) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Only completed answers can be rated.");
        }
        return message;
    }
}
