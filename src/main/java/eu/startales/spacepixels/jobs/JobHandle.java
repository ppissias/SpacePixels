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

import eu.startales.spacepixels.session.ProcessingSession;

/**
 * The identity of one job: its number, kind and owning session. The session is null for work that belongs to no
 * imported session, such as iterative detection of another folder.
 */
public record JobHandle(long id, JobKind kind, ProcessingSession session) {
}
