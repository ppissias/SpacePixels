package spv.events;

public class SolveStartedEvent {

    private final int rowIndex;

    public SolveStartedEvent(int rowIndex) {
        this.rowIndex = rowIndex;
    }
    public int getRowIndex() { return rowIndex; }
}
