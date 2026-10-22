package com.veridoc.ai.support;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

import com.veridoc.ai.config.properties.RagProperties;
import com.veridoc.ai.document.persistence.RetrievedChunkRow;
import com.veridoc.ai.evaluation.EvidencePassage;
import com.veridoc.ai.rag.GroundedPromptBuilder;

/**
 * Test-only {@link GroundedPromptBuilder} that records the evidence bundle it
 * returned.
 *
 * <p>{@code ChatService} builds the model prompt through this class and feeds
 * the returned {@code bundle.evidence()} both to the model and to
 * {@code CitationMarkerResolver}. Recording that list therefore captures exactly
 * the evidence the model saw — after the context-token trim — with no second
 * retrieval pass and no production surface.
 *
 * <p>Also records the rendered system prompt and the history texts handed to the
 * model so multi-turn retention and prompt-sanitisation can be asserted without
 * changing production code.
 */
public class RecordingPromptBuilder extends GroundedPromptBuilder {

    private volatile List<EvidencePassage> lastEvidence = List.of();
    private volatile String lastSystemPrompt = "";
    private volatile List<String> lastHistory = List.of();

    public RecordingPromptBuilder(RagProperties properties) {
        super(properties);
    }

    @Override
    public PromptBundle build(String question, List<RetrievedChunkRow> evidence,
                              List<Message> history) {
        PromptBundle bundle = super.build(question, evidence, history);
        List<EvidencePassage> recorded = new ArrayList<>(bundle.evidence().size());
        for (RetrievedChunkRow row : bundle.evidence()) {
            recorded.add(new EvidencePassage(row.filename(), row.chunkId(), row.pageNumber()));
        }
        lastEvidence = List.copyOf(recorded);

        Prompt prompt = bundle.prompt();
        lastSystemPrompt = prompt.getSystemMessage() == null ? ""
                : String.valueOf(prompt.getSystemMessage().getText());

        List<Message> instructions = prompt.getInstructions();
        List<String> historyTexts = new ArrayList<>();
        if (instructions != null) {
            for (int i = 1; i < instructions.size() - 1; i++) {
                Message message = instructions.get(i);
                historyTexts.add(message == null || message.getText() == null
                        ? "" : message.getText());
            }
        }
        lastHistory = List.copyOf(historyTexts);
        return bundle;
    }

    /**
     * Evidence from the most recent {@link #build} since the last {@link #reset}.
     * Empty when the chat layer short-circuited on an empty retrieval, because
     * {@code build} is never called on that path.
     */
    public List<EvidencePassage> lastEvidence() {
        return lastEvidence;
    }

    public String lastSystemPrompt() {
        return lastSystemPrompt;
    }

    public List<String> lastHistory() {
        return lastHistory;
    }

    public void reset() {
        lastEvidence = List.of();
        lastSystemPrompt = "";
        lastHistory = List.of();
    }
}
