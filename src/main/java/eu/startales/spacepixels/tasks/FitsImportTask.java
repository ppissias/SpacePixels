/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.session.SessionMetadata;
import eu.startales.spacepixels.session.SessionMetadataStore;
import eu.startales.spacepixels.session.ApplicationStorage;
import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.FitsImportInteraction;
import eu.startales.spacepixels.fits.FitsImportService;
import eu.startales.spacepixels.fits.MixedExposureException;
import eu.startales.spacepixels.fits.RedirectImportException;
import eu.startales.spacepixels.report.SessionReports;
import eu.startales.spacepixels.fits.XisfImageConverter;
import io.github.ppissias.jtransient.execution.CancellationScope;

import java.io.File;
import java.util.logging.Logger;

/**
 * Imports a folder of aligned frames. A conversion that writes a new folder, or the choice of one exposure time,
 * continues the import with that folder. The result is the frames, the newest report beside them and the saved session
 * data that match them; the window builds the session from it. Each attempt closes its metadata readers before it
 * returns or redirects, so a result holds no resources. The import is declined when no frames were loaded, for example
 * because a conversion was declined.
 */
public class FitsImportTask implements Job<FitsImportTask.Result> {

    /** The imported frames, the newest report beside them (or null) and the saved session data that match them. */
    public record Result(FitsFileInformation[] files, File lastReport, SessionMetadata metadata) {
    }

    private static final Logger LOGGER = Logger.getLogger(FitsImportTask.class.getName());

    private final File directory;
    private final ApplicationStorage storage;
    private final FitsImportInteraction interaction;

    /** A strict headless import that reads the saved session data directly from the folder. */
    public FitsImportTask(File directory) {
        this(directory, null);
    }

    /** A strict headless import; saved session data is read on {@code storage}, or directly when it is null. */
    public FitsImportTask(File directory, ApplicationStorage storage) {
        this(directory, storage, null);
    }

    /** Imports with explicit conversion and exposure-group decisions; null uses strict headless validation. */
    public FitsImportTask(File directory, ApplicationStorage storage, FitsImportInteraction interaction) {
        this.directory = directory;
        this.storage = storage;
        this.interaction = interaction;
    }

    @Override
    public JobOutcome<Result> run(JobContext context) throws Exception {
        try (CancellationScope cancellation = CancellationScope.open(context::isCancelled)) {
            CancellationScope.check();
            return runCancellable(context);
        }
    }

    /** Runs import and its pixel inspection under the job's cooperative cancellation scope. */
    private JobOutcome<Result> runCancellable(JobContext context) throws Exception {
        File folder = directory;
        while (true) {
            try {
                File importDirectory = XisfImageConverter.prepareDirectoryForFitsImport(folder, context::progress,
                        eu.startales.spacepixels.fits.ImageScaling.interactive(interaction), true);
                if (!folder.equals(importDirectory)) {
                    LOGGER.info("Redirecting import to converted XISF directory: " + importDirectory.getAbsolutePath());
                    context.progress(90, "Loading generated FITS metadata...");
                }

                FitsFileInformation[] filesInfo;
                try (FitsImportService importer = new FitsImportService(importDirectory, interaction)) {
                    filesInfo = importer.readFiles();
                }
                context.progress(90, "FITS metadata loaded.");
                if (filesInfo.length == 0) {
                    return JobOutcome.declined("No frames were imported.");
                }
                File sessionFolder = new File(filesInfo[0].getFilePath()).getParentFile();
                File lastReport = SessionReports.latest(sessionFolder);
                context.progress(95, "Loading saved session metadata...");
                SessionMetadata metadata = storage == null
                        ? SessionMetadataStore.filesystem().load(sessionFolder, filesInfo)
                        : storage.submit(() -> storage.store().load(sessionFolder, filesInfo)).get();
                context.progress(100, "Import complete.");
                return JobOutcome.completed(new Result(filesInfo, lastReport, metadata));

            } catch (eu.startales.spacepixels.fits.ImageScaling.DecisionCancelledException declined) {
                return JobOutcome.declined("The import was cancelled.");
            } catch (RedirectImportException e) {
                // The user opted to automatically load the newly converted directory!
                LOGGER.info("Redirecting import to newly created directory: " + e.getNewDirectory().getAbsolutePath());
                folder = e.getNewDirectory();
            } catch (MixedExposureException e) {
                // Only the window can ask; the API and batch runs reject the folder with the exception's message.
                if (interaction == null || !e.canImportLargestGroup()) {
                    throw e;
                }
                File largestGroup = FitsImportService.offerToImportLargestExposureGroup(e, interaction);
                if (largestGroup == null) {
                    return JobOutcome.declined("The import was cancelled.");
                }
                LOGGER.info("Redirecting import to the images of one exposure time: " + largestGroup.getAbsolutePath());
                folder = largestGroup;
            }
        }
    }
}
