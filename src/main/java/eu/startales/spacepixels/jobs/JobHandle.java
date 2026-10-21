/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.jobs;

/**
 * The identity of one job: its number, kind and owning session. The window passes its ProcessingSession;
 * Object is retained for compatibility with non-window callers.
 */
public record JobHandle(long id, JobKind kind, Object session) {
}
