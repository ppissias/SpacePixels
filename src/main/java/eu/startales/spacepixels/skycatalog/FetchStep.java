/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.skycatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * One question asked of an online service while fetching a sky catalogue or the known asteroids and comets.
 * The clients fill it, and the fetch summary reads it. Gson writes the public fields; a missing list in an older
 * file is null until the store replaces it. {@link #url} is the request that was sent. A JPL address includes the
 * observing site. {@link #liveUrl} repeats that request for this visit and is not written; after a reload
 * {@link #openUrl()} uses the saved address.
 */
public final class FetchStep {

    /** Whether the question was answered, failed, or never sent. */
    public enum Status {
        /** The service answered, including an answer that listed nothing in the field. */
        SUCCEEDED,
        /** The service was asked and did not answer, or answered only in part. */
        FAILED,
        /** The question was not sent. */
        NOT_ASKED
    }

    /** The service, for example "SIMBAD" or "JPL Horizons". */
    public String service;
    /** What was asked, for example "Variable stars" or one object's name. */
    public String question;
    public Status status;
    /** A count, or what was kept, such as a straight-line path. */
    public String outcome;
    /** Why it failed, or why it was not asked. */
    public String detail;
    /**
     * Address saved with the fetch. For a TAP query this is the endpoint, not a page that runs the query.
     * A JPL address includes the observing site.
     */
    public String url;
    /** The request address for this visit. Not saved; null after the file is loaded again. */
    public transient String liveUrl;
    /** ADQL of a TAP query. Null for a GET that is already a complete address. */
    public String query;

    /**
     * A finished or skipped step. The caller owns the returned object. {@code url} is stored as given, including
     * an observing site on a JPL address.
     */
    public static FetchStep of(String service, String question, Status status, String outcome, String detail,
            String url, String query) {
        FetchStep step = new FetchStep();
        step.service = service;
        step.question = question;
        step.status = status;
        step.outcome = outcome;
        step.detail = detail;
        step.liveUrl = url;
        step.url = url;
        step.query = query;
        return step;
    }

    /** The word shown in the summary. */
    public String resultWord() {
        if (status == Status.FAILED) {
            return "Failed";
        }
        if (status == Status.NOT_ASKED) {
            return "Not asked";
        }
        return "Succeeded";
    }

    /** The address to open now: the request that was sent while this visit still has it, otherwise the saved one. */
    public String openUrl() {
        return liveUrl != null && !liveUrl.isBlank() ? liveUrl : url;
    }

    /**
     * Rows to show for a saved result. Steps written with the fetch are kept. An older file has only problem
     * sentences: each sentence is a failed row and has no link.
     */
    public static List<FetchStep> rowsFor(List<FetchStep> steps, List<String> problems) {
        if (steps != null && !steps.isEmpty()) {
            return steps;
        }
        List<FetchStep> rows = new ArrayList<>();
        if (problems == null) {
            return rows;
        }
        for (String problem : problems) {
            if (problem != null && !problem.isBlank()) {
                rows.add(of(problem, "", Status.FAILED, "", "", null, null));
            }
        }
        return rows;
    }
}
