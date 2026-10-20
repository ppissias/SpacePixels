package eu.startales.spacepixels.util.reporting;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** The report says which step it is on, and each item of a slow step; the bar stays below 100 %. */
public class ReportProgressTest {

    @Test
    public void stepsAndTheirItemsShareTheBar() {
        List<String> reports = new ArrayList<>();
        ReportProgress progress = new ReportProgress((percent, message) -> reports.add(percent + " " + message));

        progress.step(36, 66, "unclassified transients");
        progress.expect(3);
        progress.next();
        progress.next();
        progress.next();
        progress.next(); // one more than expected: not reported
        progress.step(96, 120, "diagnostics");

        assertEquals(List.of(
                "36 Writing the report: unclassified transients...",
                "36 Writing the report: unclassified transients (1 of 3)...",
                "46 Writing the report: unclassified transients (2 of 3)...",
                "56 Writing the report: unclassified transients (3 of 3)...",
                "96 Writing the report: diagnostics..."), reports);
    }

    @Test
    public void itemsWithoutAnExpectedCountAreNotReported() {
        List<String> reports = new ArrayList<>();
        ReportProgress progress = new ReportProgress((percent, message) -> reports.add(message));
        progress.step(0, 2, "preparing");
        progress.next();
        assertEquals(1, reports.size());
        ReportProgress.NONE.step(0, 1, "nothing listens");
    }
}
