package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.config.VariableStarReportOptions;
import io.github.ppissias.jtransient.photometry.StarLightCurve;
import io.github.ppissias.jtransient.photometry.VariabilityTier;
import io.github.ppissias.jtransient.photometry.VariableStarAnalysis;

import java.util.*;

/** One report's card selection and display order, with identities fixed to the original engine order. */
final class VariableStarPresentation {
    final List<StarLightCurve> identities;
    final List<StarLightCurve> ordered;
    final List<StarLightCurve> cards;
    private final Map<Integer, Integer> numbers = new HashMap<>();
    private final Set<Integer> cardIds = new HashSet<>();

    VariableStarPresentation(DetectionReportContext context, VariableStarAnalysis analysis) {
        identities = analysis.candidates.stream().filter(s -> s.tier == VariabilityTier.HIGH_CONFIDENCE
                || s.tier == VariabilityTier.POSSIBLE).toList();
        for (int i = 0; i < identities.size(); i++) numbers.put(identities.get(i).id, i + 1);
        var options = context.settings.getVariableStarReportOptions();
        Map<Integer, Double> periods = new HashMap<>();
        Map<Integer, Integer> changes = new HashMap<>();
        for (var star : identities) {
            if (options.sortOrder() == VariableStarReportOptions.SortOrder.SHORTEST_PERIOD) {
                var target = PhotometryReportSectionWriter.skyTarget(context, star.x, star.y);
                var matches = target == null ? List.<SessionCatalogue.VariableMatch>of()
                        : context.catalogue().variablesNear(target.raDegrees, target.decDegrees, target.radiusArcsec);
                Double period = matches.isEmpty() ? null : matches.get(0).star.periodDays;
                periods.put(star.id, period != null && Double.isFinite(period) && period > 0 ? period : Double.POSITIVE_INFINITY);
            }
            if (options.sortOrder() == VariableStarReportOptions.SortOrder.OBSERVED_CHANGES) {
                String change = PhotometryReportSectionWriter.describeChange(star);
                changes.put(star.id, Set.of("Varied up and down", "Dipped and recovered", "Brightened and faded back").contains(change) ? 0 : 1);
            }
        }
        Comparator<StarLightCurve> amplitude = Comparator.comparingDouble(s -> descending(s.amplitude));
        Comparator<StarLightCurve> significance = Comparator.comparingDouble(s -> descending(s.scatterZ));
        Comparator<StarLightCurve> withinTier = switch (options.sortOrder()) {
            case OBSERVED_CHANGES -> Comparator.<StarLightCurve>comparingInt(s -> changes.get(s.id)).thenComparing(amplitude);
            case SHORTEST_PERIOD -> Comparator.<StarLightCurve>comparingDouble(s -> periods.get(s.id)).thenComparing(amplitude);
            case LARGEST_VARIATION -> amplitude;
            case SIGNIFICANCE -> significance;
        };
        List<StarLightCurve> display = new ArrayList<>(identities);
        display.sort(Comparator.<StarLightCurve>comparingInt(s -> s.tier == VariabilityTier.HIGH_CONFIDENCE ? 0 : 1)
                .thenComparing(withinTier).thenComparing(significance).thenComparingInt(this::number));
        ordered = List.copyOf(display);
        cards = List.copyOf(ordered.subList(0, Math.min(options.maxCandidateCards(), ordered.size())));
        for (var star : cards) cardIds.add(star.id);
    }

    int number(StarLightCurve star) { return numbers.get(star.id); }
    boolean hasCard(StarLightCurve star) { return cardIds.contains(star.id); }
    private static double descending(double value) { return Double.isFinite(value) ? -value : Double.POSITIVE_INFINITY; }
}
