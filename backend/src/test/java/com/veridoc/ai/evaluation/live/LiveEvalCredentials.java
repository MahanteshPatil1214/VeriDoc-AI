package com.veridoc.ai.evaluation.live;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Presence check for the two provider credentials the live evaluation needs.
 *
 * <p><strong>Presence is not proof of validity.</strong> This class only rejects
 * the configurations that obviously cannot work - a missing variable, a blank
 * value, a copied placeholder from a template, or a string far too short to be
 * a real key. It never contacts a provider and never claims a key is valid; a
 * real authentication probe belongs to a later, separately approved step.
 *
 * <p>No value is ever returned, logged or embedded in a message. The
 * diagnostics name the <em>variable</em> and the <em>reason</em> only - see
 * {@link Entry#describe()} and {@link #redact(String)}. That is what keeps a
 * partially configured run from printing half a secret into a CI log.
 */
public final class LiveEvalCredentials {

    /** Gemini embedding credential, read by {@code spring.ai.google.genai.embedding.api-key}. */
    public static final String GEMINI_VARIABLE = "GEMINI_API_KEY";

    /** Groq chat credential, read by {@code spring.ai.openai.api-key}. */
    public static final String GROQ_VARIABLE = "GROQ_API_KEY";

    /**
     * Shortest value that is not treated as an obvious typo. Real provider keys
     * are far longer (Gemini is ~39 characters, Groq ~56); the bound only has
     * to catch strings such as {@code abc} or {@code key1}.
     */
    static final int MINIMUM_LENGTH = 16;

    /** Values that are literally a placeholder, after trimming and lower-casing. */
    private static final Set<String> PLACEHOLDER_VALUES = Set.of(
            "test-key-not-used", "test_key_not_used", "test-key", "test_key",
            "changeme", "change-me", "change_me", "changeit",
            "your-api-key", "your_api_key", "your-api-key-here", "your_api_key_here",
            "placeholder", "replace-me", "replace_me", "insert-key", "insert_key",
            "dummy", "dummy-key", "fake", "fake-key", "example", "example-key",
            "sample", "sample-key", "none", "null", "undefined", "todo", "tbd",
            "secret", "apikey", "api-key");

    /**
     * Fragments that only appear in placeholders. Real provider keys are random
     * strings, so a substring match here cannot reject a genuine key in
     * practice.
     */
    private static final List<String> PLACEHOLDER_FRAGMENTS = List.of(
            "test-key-not-used", "placeholder", "changeme", "change-me", "change_me",
            "your-api-key", "your_api_key", "replace-me", "replace_me",
            "insert-key", "insert_key", "dummy", "fake", "example-key", "sample-key",
            "xxxx", "****");

    private LiveEvalCredentials() {
    }

    /** What a credential variable looks like right now. */
    public enum Status {

        /** A plausible key: present, non-blank, not a placeholder, long enough. */
        PRESENT("present"),

        /** The variable is not set at all. */
        MISSING("missing"),

        /** The variable is set but contains only whitespace. */
        BLANK("blank"),

        /** The value is a known or recognisable placeholder. */
        PLACEHOLDER("an obvious placeholder"),

        /** The value is too short to be a real key. */
        TOO_SHORT("too short to be a real key");

        private final String description;

        Status(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }

        /** Whether the run may proceed with this value. */
        public boolean acceptable() {
            return this == PRESENT;
        }
    }

    /**
     * One credential variable and its status.
     *
     * <p>The record deliberately has no field for the value itself, so neither
     * {@code toString()} nor a debugger dump can expose one.
     */
    public record Entry(String variable, Status status) {

        public Entry {
            if (variable == null || variable.isBlank()) {
                throw new IllegalArgumentException("variable must be named");
            }
            if (status == null) {
                throw new IllegalArgumentException("status must be known");
            }
        }

        public String describe() {
            return variable + " is " + status.description();
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /**
     * The credential picture of one environment.
     *
     * <p>Only statuses are stored, never values.
     */
    public record Report(List<Entry> entries) {

        public Report {
            entries = List.copyOf(entries);
        }

        public boolean acceptable() {
            return entries.stream().allMatch(entry -> entry.status().acceptable());
        }

        public List<Entry> rejected() {
            return entries.stream().filter(entry -> !entry.status().acceptable()).toList();
        }

        public String describe() {
            return entries.stream().map(Entry::describe).reduce((a, b) -> a + "; " + b).orElse("");
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /**
     * Classifies both credentials.
     *
     * @param environment the process environment (may be null)
     * @return a report that never contains a credential value
     */
    public static Report inspect(Map<?, ?> environment) {
        return new Report(List.of(inspect(GEMINI_VARIABLE, environment), inspect(GROQ_VARIABLE, environment)));
    }

    private static Entry inspect(String variable, Map<?, ?> environment) {
        Object raw = environment == null ? null : environment.get(variable);
        if (raw == null) {
            return new Entry(variable, Status.MISSING);
        }
        String value = String.valueOf(raw);
        if (value.isBlank()) {
            return new Entry(variable, Status.BLANK);
        }
        if (isPlaceholder(value)) {
            return new Entry(variable, Status.PLACEHOLDER);
        }
        if (value.strip().length() < MINIMUM_LENGTH) {
            return new Entry(variable, Status.TOO_SHORT);
        }
        return new Entry(variable, Status.PRESENT);
    }

    /**
     * Whether a value is an obvious placeholder rather than a key.
     *
     * @param value the raw value (never echoed back)
     */
    static boolean isPlaceholder(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        if (PLACEHOLDER_VALUES.contains(normalized)) {
            return true;
        }
        for (String fragment : PLACEHOLDER_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        if (normalized.startsWith("<") || normalized.endsWith(">") || normalized.startsWith("${")
                || normalized.startsWith("$")) {
            return true;
        }
        // Only filler characters: "xxxx...", "****", "0000", "....".
        return normalized.replaceAll("[x*._\\-\\s0]", "").isEmpty();
    }

    /**
     * A diagnostic label for a credential value.
     *
     * <p>Returns a category, never any part of the value - not even a length or
     * a suffix, because both narrow a guess.
     *
     * @param value the raw value, or null
     * @return {@code <missing>}, {@code <blank>}, {@code <placeholder>} or
     *         {@code <set>}
     */
    public static String redact(String value) {
        if (value == null) {
            return "<missing>";
        }
        if (value.isBlank()) {
            return "<blank>";
        }
        return isPlaceholder(value) ? "<placeholder>" : "<set>";
    }
}
