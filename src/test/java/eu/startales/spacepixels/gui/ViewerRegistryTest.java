/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.FitsFileInformation;
import org.junit.Test;

import javax.swing.JFrame;
import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that {@link ViewerRegistry} tracks viewers, automatically unregisters disposed windows, and closes every
 * viewer on session change and application exit.
 */
public class ViewerRegistryTest {

    private static void onEdt(Runnable action) throws Exception {
        if (EventQueue.isDispatchThread()) {
            action.run();
        } else {
            try {
                EventQueue.invokeAndWait(action);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof Exception ex) throw ex;
                throw new AssertionError(cause);
            }
        }
    }

    private static final class TestViewer extends JFrame implements SessionViewer {
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public void closeForSessionChange() {
            closeCalls.incrementAndGet();
            closed.set(true);
            dispose();
        }
    }

    @Test
    public void registersAndCountsViewers() throws Exception {
        onEdt(() -> {
            ViewerRegistry registry = new ViewerRegistry();
            TestViewer v1 = new TestViewer();
            TestViewer v2 = new TestViewer();
            try {
                assertEquals(0, registry.count());
                registry.register(v1);
                assertEquals(1, registry.count());
                assertTrue(registry.isRegistered(v1));
                assertFalse(registry.isRegistered(v2));

                // Duplicate registration is idempotent
                registry.register(v1);
                assertEquals(1, registry.count());

                registry.register(v2);
                assertEquals(2, registry.count());
                assertTrue(registry.isRegistered(v2));
            } finally {
                v1.dispose();
                v2.dispose();
            }
        });
    }

    @Test
    public void unregisterRemovesViewer() throws Exception {
        onEdt(() -> {
            ViewerRegistry registry = new ViewerRegistry();
            TestViewer v = new TestViewer();
            try {
                registry.register(v);
                assertTrue(registry.isRegistered(v));

                registry.unregister(v);
                assertFalse(registry.isRegistered(v));
                assertEquals(0, registry.count());
            } finally {
                v.pack();
            v.dispose();
            }
        });
    }

    @Test
    public void windowDisposalAutomaticallyUnregisters() throws Exception {
        ViewerRegistry registry = new ViewerRegistry();
        TestViewer[] ref = new TestViewer[1];
        onEdt(() -> {
            ref[0] = new TestViewer();
            registry.register(ref[0]);
            ref[0].pack();
        });
        assertEquals(1, registry.count());
        assertTrue(registry.isRegistered(ref[0]));

        onEdt(() -> ref[0].dispose());
        onEdt(() -> {
            assertTrue(ref[0].closed.get());
            assertEquals(1, ref[0].closeCalls.get());
            assertFalse(registry.isRegistered(ref[0]));
            assertEquals(0, registry.count());
        });
    }

    @Test
    public void sessionChangedClosesAllViewersAndClearsRegistry() throws Exception {
        onEdt(() -> {
            ViewerRegistry registry = new ViewerRegistry();
            TestViewer v1 = new TestViewer();
            TestViewer v2 = new TestViewer();
            try {
                registry.register(v1);
                registry.register(v2);
                assertEquals(2, registry.count());

                registry.sessionChanged(new FitsFileInformation[0]);

                assertTrue(v1.closed.get());
                assertTrue(v2.closed.get());
                assertEquals(1, v1.closeCalls.get());
                assertEquals(1, v2.closeCalls.get());
                assertEquals(0, registry.count());
            } finally {
                v1.dispose();
                v2.dispose();
            }
        });
    }

    @Test
    public void closeAllHandlesExceptionsGracefully() throws Exception {
        onEdt(() -> {
            ViewerRegistry registry = new ViewerRegistry();
            SessionViewer throwingViewer = () -> {
                throw new RuntimeException("Test exception closing viewer");
            };
            TestViewer v2 = new TestViewer();
            try {
                registry.register(throwingViewer);
                registry.register(v2);
                assertEquals(2, registry.count());

                registry.closeAll();

                assertTrue(v2.closed.get());
                assertEquals(0, registry.count());
            } finally {
                v2.dispose();
            }
        });
    }
}
