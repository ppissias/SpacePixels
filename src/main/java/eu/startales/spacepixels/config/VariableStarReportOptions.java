package eu.startales.spacepixels.config;

import java.util.Objects;

/** SpacePixels presentation choices; they never alter detection or photometry. */
public record VariableStarReportOptions(int maxCandidateCards, SortOrder sortOrder) {
    public static final int DEFAULT_MAX_CANDIDATE_CARDS = 40;
    public static final int MAX_CANDIDATE_CARDS = 1000;
    public static final VariableStarReportOptions DEFAULT = new VariableStarReportOptions(
            DEFAULT_MAX_CANDIDATE_CARDS, SortOrder.OBSERVED_CHANGES);

    public VariableStarReportOptions {
        if (maxCandidateCards < 1 || maxCandidateCards > MAX_CANDIDATE_CARDS)
            throw new IllegalArgumentException("Maximum detailed candidate cards must be between 1 and " + MAX_CANDIDATE_CARDS + ".");
        Objects.requireNonNull(sortOrder, "sortOrder");
    }

    public enum SortOrder {
        OBSERVED_CHANGES("Observed changes first"),
        SHORTEST_PERIOD("Shortest known period first"),
        LARGEST_VARIATION("Largest observed variation first"),
        SIGNIFICANCE("Variability significance");

        private final String label;
        SortOrder(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
}
