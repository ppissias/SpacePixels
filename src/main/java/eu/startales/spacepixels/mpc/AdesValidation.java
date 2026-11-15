package eu.startales.spacepixels.mpc;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks an ADES PSV file against the ADES submission schema ({@code submit.xsd}), as the MPC's own validation tutorial
 * does: the PSV is turned into ADES XML (the observation context's {@code #} elements and {@code !} children, then one
 * {@code optical} element per data row, its columns in their order) and validated with the JDK's schema validator. The
 * schema comes from the IAU's ADES repository on first use and is kept in a folder of the user's; it is not shipped
 * with SpacePixels. Passing the schema is necessary, not sufficient: the MPC's test service adds its own checks.
 */
public final class AdesValidation {
    /** The schema files, and where they are fetched from. */
    public static final List<String> SCHEMA_FILES = List.of("submit.xsd", "general.xsd");
    public static final String SCHEMA_SOURCE = "https://raw.githubusercontent.com/IAU-ADES/ADES-Master/master/xsd/";
    /** The folder in the user's home that keeps the fetched schema. */
    public static final String SCHEMA_FOLDER = "spacepixels_ades_schema";

    private AdesValidation() { }

    /** The PSV as ADES XML, one observation block, without validation. */
    public static String toXml(String psv) {
        var lines = psv.replace("\r", "").lines().toList();
        String version = "2022";
        var context = new StringBuilder();
        String open = null;
        List<String> columns = null;
        var data = new StringBuilder();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("# version=")) {
                version = line.substring("# version=".length()).strip();
            } else if (line.startsWith("#")) {
                if (open != null) context.append("</").append(open).append('>');
                open = line.substring(1).strip();
                context.append('<').append(open).append('>');
            } else if (line.startsWith("!")) {
                String rest = line.substring(1).strip();
                int space = rest.indexOf(' ');
                String key = space < 0 ? rest : rest.substring(0, space);
                String value = space < 0 ? "" : rest.substring(space + 1).strip();
                context.append('<').append(key).append('>').append(escape(value)).append("</").append(key).append('>');
            } else if (columns == null) {
                if (open != null) { context.append("</").append(open).append('>'); open = null; }
                columns = List.of(line.split("\\|", -1)).stream().map(String::strip).toList();
            } else {
                String[] values = line.split("\\|", -1);
                data.append("<optical>");
                for (int k = 0; k < columns.size() && k < values.length; k++) {
                    String value = values[k].strip();
                    if (!value.isEmpty()) data.append('<').append(columns.get(k)).append('>').append(escape(value))
                            .append("</").append(columns.get(k)).append('>');
                }
                data.append("</optical>");
            }
        }
        if (open != null) context.append("</").append(open).append('>');
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ades version=\"" + escape(version) + "\"><obsBlock><obsContext>"
                + context + "</obsContext><obsData>" + data + "</obsData></obsBlock></ades>\n";
    }

    /**
     * The schema's complaints about the PSV, each with its line in the XML form; empty when it passes.
     *
     * @param schemaFolder a folder holding {@link #SCHEMA_FILES}
     */
    public static List<String> validate(String psv, Path schemaFolder) throws IOException {
        var problems = new ArrayList<String>();
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
            var schema = factory.newSchema(schemaFolder.resolve("submit.xsd").toFile());
            var validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.setErrorHandler(new ErrorHandler() {
                @Override public void warning(SAXParseException e) { }
                @Override public void error(SAXParseException e) { problems.add(e.getMessage()); }
                @Override public void fatalError(SAXParseException e) { problems.add(e.getMessage()); }
            });
            validator.validate(new StreamSource(new StringReader(toXml(psv))));
        } catch (org.xml.sax.SAXException e) {
            if (problems.isEmpty()) problems.add(e.getMessage());
        }
        return problems;
    }

    /** The schema folder in {@code home}, fetching the schema files that are missing. */
    public static Path schemaFolder(Path home) throws IOException, InterruptedException {
        Path folder = home.resolve(SCHEMA_FOLDER);
        Files.createDirectories(folder);
        HttpClient client = null;
        for (String name : SCHEMA_FILES) {
            Path file = folder.resolve(name);
            if (Files.isRegularFile(file) && Files.size(file) > 0) continue;
            if (client == null) client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(SCHEMA_SOURCE + name)).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "SpacePixels").GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length == 0) {
                throw new IOException("The ADES schema " + name + " could not be fetched (HTTP " + response.statusCode() + ").");
            }
            Path partial = folder.resolve(name + ".part");
            Files.write(partial, response.body());
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return folder;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
