package eu.startales.spacepixels.mpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sends an ADES PSV file to the Minor Planet Center's test service, {@value #TEST_URL}, as its command-line
 * submission page describes: a multipart form with an acknowledgement line ({@code ack}), the e-mail address for the
 * MPC's replies ({@code ac2}), optionally the object type ({@code obj_type}), and the file ({@code source}). The test
 * service "runs the same basic checks" as a submission and answers with an ID, but the batch "is NOT passed to the
 * processing routines". SpacePixels never sends anything to the MPC's submission service itself.
 */
public final class MpcTestSubmission {
    /** The MPC's test service for ADES PSV. */
    public static final String TEST_URL = "https://minorplanetcenter.net/submit_psv_test";

    /** The HTTP status and the MPC's answer as text. */
    public record Reply(int status, String text) {
        /** Whether the service accepted the request (HTTP 2xx); the text still says what the checks found. */
        public boolean received() { return status >= 200 && status < 300; }
    }

    private MpcTestSubmission() { }

    /** The form fields in the order sent; {@code obj_type} only when given. */
    static Map<String, String> fields(String psv, String ack, String email, String objectType) {
        if (email == null || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new IllegalArgumentException("an e-mail address is needed");
        var fields = new LinkedHashMap<String, String>();
        fields.put("ack", ack == null ? "" : ack);
        fields.put("ac2", email.trim());
        if (objectType != null && !objectType.isBlank()) fields.put("obj_type", objectType.trim());
        fields.put("source", psv);
        return fields;
    }

    /** The multipart body for the fields, with {@code boundary}. */
    static byte[] body(Map<String, String> fields, String boundary) {
        var out = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) {
            // As curl's -F "name=<file": the file's text as an ordinary field, not a file upload.
            String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n";
            out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
            out.writeBytes(field.getValue().getBytes(StandardCharsets.UTF_8));
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** Sends the file to the test service and returns its answer. */
    public static Reply send(String psv, String ack, String email, String objectType) throws IOException, InterruptedException {
        String boundary = "SpacePixels" + UUID.randomUUID().toString().replace("-", "");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(TEST_URL)).timeout(Duration.ofSeconds(90))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("User-Agent", "SpacePixels")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body(fields(psv, ack, email, objectType), boundary))).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(response.statusCode(), response.body() == null ? "" : response.body().strip());
    }
}
