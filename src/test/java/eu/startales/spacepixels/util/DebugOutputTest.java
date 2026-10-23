package eu.startales.spacepixels.util;

import io.github.ppissias.jtransient.execution.DiagnosticContext;
import io.github.ppissias.jtransient.execution.DiagnosticOptions;
import org.junit.Test;

import static org.junit.Assert.*;

/** Verifies application debug policy capture and explicit caller overrides without engine globals. */
public class DebugOutputTest {
    /** An operation captures the application property once and restores unscoped silence afterwards. */
    @Test
    public void capturesApplicationSettingAtScopeEntry() {
        String previous = System.getProperty(DebugOutput.PROPERTY);
        try {
            System.setProperty(DebugOutput.PROPERTY, "true");
            try (var diagnostics = DebugOutput.openEngineDiagnostics()) {
                assertTrue(DiagnosticContext.enabled());
                System.setProperty(DebugOutput.PROPERTY, "false");
                assertTrue(DiagnosticContext.enabled());
            }
            assertFalse(DiagnosticContext.enabled());
        } finally {
            restore(previous);
        }
    }

    /** Explicit caller policy wins over the application property and survives a nested failure. */
    @Test
    public void honorsCallerPolicyAndRestoresAfterFailure() {
        String previous = System.getProperty(DebugOutput.PROPERTY);
        try {
            System.setProperty(DebugOutput.PROPERTY, "true");
            try (var diagnostics = DiagnosticContext.open(new DiagnosticOptions(false, message -> fail("Silent sink called")))) {
                assertEquals(42, DebugOutput.withEngineDiagnostics(() -> {
                    assertFalse(DiagnosticContext.enabled());
                    return 42;
                }).intValue());
                RuntimeException failure = new IllegalStateException("scientific failure");
                assertSame(failure, assertThrows(IllegalStateException.class,
                        () -> DebugOutput.withEngineDiagnostics(() -> { throw failure; })));
                assertFalse(DiagnosticContext.enabled());
            }
            assertFalse(DiagnosticContext.enabled());
        } finally {
            restore(previous);
        }
    }

    /** Restores the caller's JVM property after an isolated application-policy test. */
    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty(DebugOutput.PROPERTY);
        } else {
            System.setProperty(DebugOutput.PROPERTY, previous);
        }
    }
}
