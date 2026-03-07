package spv.events;

import io.github.ppissias.astrolib.PlateSolveResult;

public class SolveFinishedEvent {
    private final int rowIndex;
    private final PlateSolveResult result; // Can be null if an exception occurred

    public SolveFinishedEvent(int rowIndex, PlateSolveResult result) {
        this.rowIndex = rowIndex;
        this.result = result;
    }
    public int getRowIndex() { return rowIndex; }
    public PlateSolveResult getResult() { return result; }
}
