package eu.startales.spacepixels.session;

import eu.startales.spacepixels.detection.AutoTuneSessionStore;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;
import eu.startales.spacepixels.fits.WcsSolutionResolver;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.skycatalog.SkyCatalogueStore;
import eu.startales.spacepixels.skycatalog.SkyField;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The file-store boundary, called on the session I/O worker or a standalone import worker, never the EDT. */
public interface SessionMetadataStore {
    SessionMetadata load(File folder, FitsFileInformation[] files) throws IOException;
    void saveCatalogue(File folder, SkyCatalogue catalogue) throws IOException;
    void saveCorrection(File folder, PlateSolutionCorrection correction) throws IOException;
    void saveAutoTune(File folder, AutoTuneSessionStore.Document document) throws IOException;

    static SessionMetadataStore filesystem() {
        return new SessionMetadataStore() {
            @Override
            public SessionMetadata load(File folder, FitsFileInformation[] files) {
                SkyCatalogue catalogue = SkyCatalogueStore.load(folder);
                PlateSolutionCorrection correction = PlateSolutionCorrection.load(folder);
                var solution = WcsSolutionResolver.resolveUncorrected(null, files);
                SkyField field = solution == null || files.length == 0 ? null
                        : SkyField.of(solution.getTransformer(), files[0].getSizeWidth(), files[0].getSizeHeight());
                if (catalogue != null && field != null && !field.isSameFieldAs(catalogue.field)) catalogue = null;
                if (correction != null && (solution == null
                        || !solution.getTransformer().solutionKey().equals(correction.solutionKey))) correction = null;
                AutoTuneSessionStore.Document tuning = files.length == 0 ? null : AutoTuneSessionStore.loadMatching(folder,
                        files.length, files[0].getSizeWidth(), files[0].getSizeHeight(),
                        Arrays.stream(files).map(file -> new File(file.getFilePath()).getName()).toList());
                if (AutoTuneSessionStore.slim(tuning)) {
                    try {
                        AutoTuneSessionStore.save(folder, tuning);
                    } catch (IOException failure) {
                        Logger.getLogger(SessionMetadataStore.class.getName()).log(Level.WARNING,
                                "Cannot compact saved Auto-Tune results; the loaded measurements remain available.", failure);
                    }
                }
                return new SessionMetadata(catalogue, correction, tuning);
            }

            @Override public void saveCatalogue(File folder, SkyCatalogue catalogue) throws IOException {
                SkyCatalogueStore.save(folder, catalogue);
            }
            @Override public void saveCorrection(File folder, PlateSolutionCorrection correction) throws IOException {
                PlateSolutionCorrection.save(folder, correction);
            }
            @Override public void saveAutoTune(File folder, AutoTuneSessionStore.Document document) throws IOException {
                AutoTuneSessionStore.save(folder, document);
            }
        };
    }
}
