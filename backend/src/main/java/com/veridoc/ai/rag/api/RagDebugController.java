package com.veridoc.ai.rag.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.veridoc.ai.rag.RagDebugService;
import com.veridoc.ai.rag.api.dto.RagDebugDtos.RagDebugResponse;

/**
 * Admin-only RAG debugger. Authorization is enforced by the security filter
 * chain ({@code /api/v1/admin/**} requires {@code ROLE_ADMIN}); the debugger
 * itself additionally requires {@code veridoc.rag.debug-enabled=true}.
 */
@RestController
@RequestMapping("/api/v1/admin/rag")
public class RagDebugController {

    private final RagDebugService ragDebugService;

    public RagDebugController(RagDebugService ragDebugService) {
        this.ragDebugService = ragDebugService;
    }

    @GetMapping("/debug")
    public RagDebugResponse debug(@RequestParam UUID conversationId,
                                  @RequestParam String question) {
        return ragDebugService.debug(conversationId, question);
    }
}