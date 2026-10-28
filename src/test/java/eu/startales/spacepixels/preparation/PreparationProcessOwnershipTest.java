package eu.startales.spacepixels.preparation;

import org.junit.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import static org.junit.Assert.*;

/** A reused Windows parent PID must never admit an older unrelated application into an owned process tree. */
public class PreparationProcessOwnershipTest {
    /** Rejects the entire stale branch, even when that unrelated application has spawned newer children. */
    @Test public void rejectsOlderChildrenBeforeTraversingTheirDescendants() {
        Handle root = new Handle(1, 1000), owned = new Handle(2, 1001), grandchild = new Handle(3, 1002);
        Handle unrelated = new Handle(4, 500), unrelatedNewChild = new Handle(5, 1100);
        root.children = List.of(owned, unrelated); owned.children = List.of(grandchild); unrelated.children = List.of(unrelatedNewChild);
        Set<ProcessHandle> handles = new LinkedHashSet<>(List.of(root));
        PreparationProcess.discoverChildren(handles);
        assertEquals(Set.of(root, owned, grandchild), handles);
        assertEquals(0, unrelated.childQueries);
    }

    /** A dead parent cannot claim new processes that now report its recycled PID. */
    @Test public void neverQueriesAnExpiredParent() {
        Handle root = new Handle(1, 1000); root.alive = false;
        root.children = List.of(new Handle(2, 2000));
        Set<ProcessHandle> handles = new LinkedHashSet<>(List.of(root));
        PreparationProcess.discoverChildren(handles);
        assertEquals(Set.of(root), handles); assertEquals(0, root.childQueries);
    }

    /** A minimal deterministic OS snapshot; recursive descendant enumeration is deliberately forbidden. */
    private static final class Handle implements ProcessHandle {
        private final long id, birth;
        private boolean alive = true;
        private int childQueries;
        private List<ProcessHandle> children = List.of();
        /** Supplies PID and millisecond birth time independently to model PID reuse. */
        Handle(long id, long birth) { this.id = id; this.birth = birth; }
        @Override public long pid() { return id; }
        @Override public Optional<ProcessHandle> parent() { return Optional.empty(); }
        @Override public Stream<ProcessHandle> children() { childQueries++; return children.stream(); }
        @Override public Stream<ProcessHandle> descendants() { throw new AssertionError("Do not traverse unverified branches"); }
        @Override public Info info() {
            return new Info() {
                @Override public Optional<String> command() { return Optional.empty(); }
                @Override public Optional<String> commandLine() { return Optional.empty(); }
                @Override public Optional<String[]> arguments() { return Optional.empty(); }
                @Override public Optional<Instant> startInstant() { return Optional.of(Instant.ofEpochMilli(birth)); }
                @Override public Optional<Duration> totalCpuDuration() { return Optional.empty(); }
                @Override public Optional<String> user() { return Optional.empty(); }
            };
        }
        @Override public CompletableFuture<ProcessHandle> onExit() { return CompletableFuture.completedFuture(this); }
        @Override public boolean supportsNormalTermination() { return true; }
        @Override public boolean destroy() { alive = false; return true; }
        @Override public boolean destroyForcibly() { return destroy(); }
        @Override public boolean isAlive() { return alive; }
        @Override public int compareTo(ProcessHandle other) { return Long.compare(id, other.pid()); }
    }
}
