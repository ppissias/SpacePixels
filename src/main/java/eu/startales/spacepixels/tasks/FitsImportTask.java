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

import eu.startales.spacepixels.config.ApplicationSettings;

import eu.startales.spacepixels.session.SessionMetadata;
import eu.startales.spacepixels.session.SessionMetadataStore;
import eu.startales.spacepixels.session.SessionStorage;
import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.FitsImportInteraction;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.MixedExposureException;
import eu.startales.spacepixels.util.SessionReports;
import eu.startales.spacepixels.util.XisfImageConverter;

import java.io.File;
import java.util.logging.Logger;

/**
 * Imports a folder of aligned frames. A conversion that writes a new folder, or the choice of one exposure time,
 * continues the import with that folder. The result is the new session processor, which the window then owns, and
 * its frames; the import is declined when no frames were loaded, for example because a conversion was declined.
 */
public class FitsImportTask implements Job<FitsImportTask.Result> {

    /** The imported session: its processor, its frames, and the newest report beside them (or null). */
    public record Result(ImageProcessing processing, FitsFileInformation[] files, File lastReport, SessionMetadata metadata) {
        public Result(ImageProcessing processing, FitsFileInformation[] files, File lastReport) {
            this(processing, files, lastReport, SessionMetadata.empty());
        }
    }

    private static final Logger LOGGER = Logger.getLogger(FitsImportTask.class.getName());

    private final File directory;
    private final ApplicationSettings settings;
    private final SessionStorage storage;
    private final FitsImportInteraction interaction;

    public FitsImportTask(File directory) {
        this(directory, null);
    }

    public FitsImportTask(File directory, ApplicationSettings settings) {
        this(directory, settings, null);
    }

    public FitsImportTask(File directory, ApplicationSettings settings, SessionStorage storage) {
        this(directory, settings, storage, null);
    }

    /** Imports with explicit conversion and exposure-group decisions; null uses strict headless validation. */
    public FitsImportTask(File directory, ApplicationSettings settings, SessionStorage storage,
                          FitsImportInteraction interaction) {
        this.directory = directory;
        this.settings = settings;
        this.storage = storage;
        this.interaction = interaction;
    }

    @Override
    public JobOutcome<Result> run(JobContext context) throws Exception {
        ApplicationSettings preferences = settings == null ? ApplicationSettings.loadDefault() : settings;
        File folder = directory;
        while (true) {
            ImageProcessing imgProcessing = null;
            boolean ownershipTransferred = false;
            try {
                File importDirectory = XisfImageConverter.prepareDirectoryForFitsImport(folder, context::progress);
                if (!folder.equals(importDirectory)) {
                    LOGGER.info("Redirecting import to converted XISF directory: " + importDirectory.getAbsolutePath());
                    context.progress(90, "Loading generated FITS metadata...");
                }

                imgProcessing = ImageProcessing.openProcessor(importDirectory, preferences, interaction);
                FitsFileInformation[] filesInfo = imgProcessing.getFitsfileInformation();
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
                ownershipTransferred = true;
                return JobOutcome.completed(new Result(imgProcessing, filesInfo, lastReport, metadata));

            } catch (ImageProcessing.RedirectImportException e) {
                // The user opted to automatically load the newly converted directory!
                LOGGER.info("Redirecting import to newly created directory: " + e.getNewDirectory().getAbsolutePath());
                folder = e.getNewDirectory();
            } catch (MixedExposureException e) {
                // Only the window can ask; the API and batch runs reject the folder with the exception's message.
                if (interaction == null || !e.canImportLargestGroup()) {
                    throw e;
                }
                File largestGroup = ImageProcessing.offerToImportLargestExposureGroup(e, interaction);
                if (largestGroup == null) {
                    return JobOutcome.declined("The import was cancelled.");
                }
                LOGGER.info("Redirecting import to the images of one exposure time: " + largestGroup.getAbsolutePath());
                folder = largestGroup;
            } finally {
                if (!ownershipTransferred && imgProcessing != null) {
                    imgProcessing.close();
                }
            }
        }
    }
}
