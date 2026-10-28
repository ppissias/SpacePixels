package eu.startales.spacepixels.preparation;

import nom.tam.fits.Fits;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Registration exclusions leave gaps in numeric source identities, never a shifted metadata association. */
public class PreparationAssociationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** Keeps a noncontiguous subset in original source order and rejects duplicates, unknown IDs and insufficient output. */
    @Test public void retainsKnownSubsetAndRejectsAmbiguousOrTooSmallResults() throws Exception {
        List<Path> sources = new ArrayList<>(), registered = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            Path source = image("source" + i + ".fit", i);
            sources.add(source);
            if (i % 2 == 0) registered.add(image(String.format("r_pp_lights_%05d.fit", i), i));
        }
        var frames = FramePreparationService.associate(sources, sources, registered.reversed());
        assertEquals(List.of(sources.get(1), sources.get(3), sources.get(5)), frames.stream().map(PreparedSequenceWriter.Frame::original).toList());
        assertThrows(IOException.class, () -> FramePreparationService.associate(sources, sources, registered.subList(0, 2)));
        assertThrows(IOException.class, () -> FramePreparationService.associate(sources, sources,
                List.of(registered.get(0), registered.get(0), registered.get(1))));
        Path unknown = image("r_pp_lights_99999.fit", 1);
        assertThrows(IOException.class, () -> FramePreparationService.associate(sources, sources,
                List.of(registered.get(0), registered.get(1), unknown)));
    }

    /** Creates a synthetic timestamped image without private observing metadata. */
    private Path image(String name, int identity) throws Exception {
        Path file = temporary.newFile(name).toPath();
        try (Fits fits = new Fits()) {
            var image = Fits.makeHDU(new short[][]{{1, 2, 3}});
            image.getHeader().addValue("DATE-OBS", "2020-01-01T00:" + String.format("%02d", identity) + ":00", null);
            image.getHeader().addValue("EXPTIME", 30, null);
            fits.addHDU(image); fits.write(file.toFile());
        }
        return file;
    }
}
