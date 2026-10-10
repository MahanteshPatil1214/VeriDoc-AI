package com.veridoc.ai.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/**
 * Deterministic {@link ChatModel} for tests.
 *
 * <p>Reads the grounding evidence from the system message (rendered by
 * {@code GroundedPromptBuilder} as {@code <evidence ...>...</evidence>} blocks)
 * and answers by quoting the first passage. If no evidence is present it says
 * so, which lets tests distinguish "grounded answer" from a refusal that was
 * generated instead of short-circuited.
 */
public class DeterministicChatModel implements ChatModel {

    private static final Pattern EVIDENCE = Pattern.compile(
            "<evidence[^>]*>(.*?)</evidence>", Pattern.DOTALL);

    @Override
    public ChatResponse call(Prompt prompt) {
        String system = prompt.getSystemMessage() == null
                ? ""
                : prompt.getSystemMessage().getText();
        List<String> passages = extractPassages(system);
        String answer;
        if (passages.isEmpty()) {
            answer = "No passages were provided to me.";
        } else {
            answer = "Based on the uploaded documents, I can confirm: " + truncate(passages.get(0));
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    /**
     * Emits the same answer as {@link #call(Prompt)} but split into word-sized
     * chunks, so streaming tests exercise real incremental accumulation rather
     * than a single delivery.
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatResponse full = call(prompt);
        String text = full.getResult() == null || full.getResult().getOutput() == null
                ? "" : full.getResult().getOutput().getText();
        if (text == null || text.isEmpty()) {
            return Flux.just(full);
        }
        List<ChatResponse> chunks = new ArrayList<>();
        for (String piece : text.split("(?<=\\s)")) {
            if (!piece.isEmpty()) {
                chunks.add(new ChatResponse(List.of(new Generation(new AssistantMessage(piece)))));
            }
        }
        return Flux.fromIterable(chunks);
    }

    private List<String> extractPassages(String system) {
        List<String> passages = new ArrayList<>();
        Matcher matcher = EVIDENCE.matcher(system);
        while (matcher.find()) {
            String block = matcher.group(1);
            StringBuilder content = new StringBuilder();
            for (String line : block.split("\\r?\\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("Source:")
                        || trimmed.startsWith("Page:") || trimmed.startsWith("Section:")) {
                    continue;
                }
                if (content.length() > 0) {
                    content.append(' ');
                }
                content.append(trimmed);
            }
            if (content.length() > 0) {
                passages.add(content.toString());
            }
        }
        return passages;
    }

    private static String truncate(String s) {
        return s.length() <= 400 ? s : s.substring(0, 400) + "...";
    }
}