package eu.startales.spacepixels.detection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import eu.startales.spacepixels.util.StagedFile;
import io.github.ppissias.jtransient.engine.ExcludedArea;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Rectangles of a session's frames that detection leaves out, such as sensor defects or blurred edges, in the pixel
 * coordinates of its (registered) frames. Every frame loses the same rectangles. Immutable; saved in the session's
 * folder as {@value #FILE_NAME}.
 */
public final class ExcludedAreas {
    /** The file in a session's folder. */
    public static final String FILE_NAME = "excluded_areas.json";
    static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final int width;
    private final int height;
    private final List<ExcludedArea> areas;

    /**
     * Rectangles for frames of this size, each clipped to the frame; rectangles outside it are dropped.
     *
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @param areas rectangles in frame pixels, in the order they were drawn
     */
    public ExcludedAreas(int width, int height, List<ExcludedArea> areas) {
        this.width = width;
        this.height = height;
        List<ExcludedArea> clipped = new ArrayList<>();
        if (areas != null) {
            for (ExcludedArea area : areas) {
                if (area == null) {
                    continue;
                }
                long x0 = Math.max(0, area.x());
                long y0 = Math.max(0, area.y());
                long x1 = Math.min(width, (long) area.x() + area.width());
                long y1 = Math.min(height, (long) area.y() + area.height());
                if (x1 > x0 && y1 > y0) {
                    clipped.add(new ExcludedArea((int) x0, (int) y0, (int) (x1 - x0), (int) (y1 - y0)));
                }
            }
        }
        this.areas = List.copyOf(clipped);
    }

    /** No excluded areas, for frames of this size. */
    public static ExcludedAreas none(int width, int height) {
        return new ExcludedAreas(width, height, List.of());
    }

    /** The rectangles, clipped to the frame, in the order they were drawn. */
    public List<ExcludedArea> areas() {
        return areas;
    }

    /** Frame width these areas belong to, in pixels. */
    public int width() {
        return width;
    }

    /** Frame height these areas belong to, in pixels. */
    public int height() {
        return height;
    }

    /** Whether there is no area. */
    public boolean isEmpty() {
        return areas.isEmpty();
    }

    /** Whether these areas belong to frames of this size. */
    public boolean fits(int frameWidth, int frameHeight) {
        return width == frameWidth && height == frameHeight;
    }

    /** These areas and one more, drawn last. */
    public ExcludedAreas with(ExcludedArea area) {
        List<ExcludedArea> more = new ArrayList<>(areas);
        more.add(area);
        return new ExcludedAreas(width, height, more);
    }

    /** These areas without the one at {@code index}. */
    public ExcludedAreas without(int index) {
        List<ExcludedArea> fewer = new ArrayList<>(areas);
        fewer.remove(index);
        return new ExcludedAreas(width, height, fewer);
    }

    /** These areas with the one at {@code index} replaced. */
    public ExcludedAreas replacing(int index, ExcludedArea area) {
        List<ExcludedArea> changed = new ArrayList<>(areas);
        changed.set(index, area);
        return new ExcludedAreas(width, height, changed);
    }

    /** The share of the frame inside at least one area, from 0 to 1; overlaps count once. */
    public double fraction() {
        if (areas.isEmpty() || width <= 0 || height <= 0) {
            return 0;
        }
        long covered = 0;
        for (int y = 0; y < height; y++) {
            List<int[]> runs = new ArrayList<>();
            for (ExcludedArea area : areas) {
                if (y >= area.y() && y < area.y() + area.height()) {
                    runs.add(new int[]{area.x(), area.x() + area.width()});
                }
            }
            if (runs.isEmpty()) {
                continue;
            }
            runs.sort((a, b) -> Integer.compare(a[0], b[0]));
            int end = Integer.MIN_VALUE;
            for (int[] run : runs) {
                int from = Math.max(run[0], end);
                if (run[1] > from) {
                    covered += run[1] - from;
                }
                end = Math.max(end, run[1]);
            }
        }
        return covered / ((double) width * height);
    }

    /** The file of a session's folder. */
    public static File fileIn(File folder) {
        return new File(folder, FILE_NAME);
    }

    /** Writes the areas, replacing the previous ones only once the new file is complete; no areas removes the file. */
    public static void save(File folder, ExcludedAreas areas) throws IOException {
        File file = fileIn(folder);
        if (areas == null || areas.isEmpty()) {
            Files.deleteIfExists(file.toPath());
            return;
        }
        Document document = new Document();
        document.version = CURRENT_VERSION;
        document.width = areas.width;
        document.height = areas.height;
        document.areas = new ArrayList<>();
        for (ExcludedArea area : areas.areas) {
            document.areas.add(new Rectangle(area.x(), area.y(), area.width(), area.height()));
        }
        try (StagedFile staged = new StagedFile(file)) {
            try (Writer writer = staged.newWriter()) {
                GSON.toJson(document, writer);
            }
            staged.publish();
        }
    }

    /** The saved areas of a session's folder, or null when there are none or the file cannot be read. */
    public static ExcludedAreas load(File folder) {
        File file = folder == null ? null : fileIn(folder);
        if (file == null || !file.isFile()) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Document document = GSON.fromJson(reader, Document.class);
            if (document == null || document.version > CURRENT_VERSION || document.width <= 0 || document.height <= 0
                    || document.areas == null) {
                return null;
            }
            List<ExcludedArea> areas = new ArrayList<>();
            for (Rectangle r : document.areas) {
                if (r != null && r.width > 0 && r.height > 0) {
                    areas.add(new ExcludedArea(r.x, r.y, r.width, r.height));
                }
            }
            return new ExcludedAreas(document.width, document.height, areas);
        } catch (IOException | JsonParseException | IllegalArgumentException failure) {
            return null;
        }
    }

    /** The saved form. */
    private static final class Document {
        int version;
        int width;
        int height;
        List<Rectangle> areas;
    }

    /** One saved rectangle, in frame pixels. */
    private record Rectangle(int x, int y, int width, int height) { }
}
