package com.veridoc.ai.conversation.api;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.veridoc.ai.rag.ChatService;
import com.veridoc.ai.security.CurrentUser;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;

import reactor.core.publisher.Flux;

/**
 * Server-Sent Events endpoint for streaming grounded answers.
 *
 * <p>Ownership and retrieval are resolved before any bytes of the response body
 * are written, so an unauthorized or malformed request is answered with a normal
 * JSON {@code 4xx} rather than a stream that fails halfway. The event contract is
 * {@code token*} (incremental answer), then {@code citations} (once, possibly
 * empty), then {@code done}; failures emit a terminal {@code error} event.
 */
@RestController
@RequestMapping("/api/v1/conversations/{id}/stream")
public class ChatStreamController {

    private final ChatService chatService;

    public ChatStreamController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(@CurrentUser AuthenticatedUser user,
                                                @PathVariable("id") UUID id,
                                                @RequestParam("message") String message) {
        return chatService.stream(user, id, message);
    }
}
