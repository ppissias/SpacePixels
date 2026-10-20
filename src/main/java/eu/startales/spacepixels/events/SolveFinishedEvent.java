package eu.startales.spacepixels.events;

import io.github.ppissias.jplatesolve.PlateSolveResult;

public class SolveFinishedEvent {
    private final int rowIndex;
    private final PlateSolveResult result; // Can be null if an exception occurred
    private final String failureMessage;

    public SolveFinishedEvent(int rowIndex, PlateSolveResult result) {
        this(rowIndex, result, null);
    }

    /** A solve that ended with an error before the solver gave a result, with the reason. */
    public SolveFinishedEvent(int rowIndex, PlateSolveResult result, String failureMessage) {
        this.rowIndex = rowIndex;
        this.result = result;
        this.failureMessage = failureMessage;
    }
    public int getRowIndex() { return rowIndex; }
    public PlateSolveResult getResult() { return result; }
    /** Why the solve failed when there is no result, or null. */
    public String getFailureMessage() { return failureMessage; }
}
