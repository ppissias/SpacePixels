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
 * The identity of one job: its number, its kind and the session it was started for. Until the application has a
 * session object, the session is whatever the caller uses for it (the window passes its {@code ImageProcessing}).
 */
public record JobHandle(long id, JobKind kind, Object session) {
}
