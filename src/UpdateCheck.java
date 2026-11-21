package local.taskscope.manager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

final class UpdateCheck {
    private static final String REPOSITORY = "S3lfuve/Taskscope";
    private static final String METADATA = "https://raw.githubusercontent.com/" + REPOSITORY + "/release/release.json";
    static final String RELEASES = "https://github.com/" + REPOSITORY + "/releases";
    private static final int MAX_BYTES = 8192;

    static final class Release {
        final long code;
        final String version;

        Release(long code, String version) {
            this.code = code;
            this.version = version;
        }
    }

    private UpdateCheck() { }

    static Release fetch(String packageName) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(METADATA).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null;
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[1024];
                for (int count; (count = input.read(bytes)) != -1; ) {
                    if (body.size() + count > MAX_BYTES) throw new IOException("Release metadata is too large");
                    body.write(bytes, 0, count);
                }
                return parse(new String(body.toByteArray(), StandardCharsets.UTF_8), packageName);
            }
        } finally {
            connection.disconnect();
        }
    }

    static Release parse(String json, String packageName) throws JSONException {
        JSONObject data = new JSONObject(json);
        Object code = data.get("versionCode");
        Object version = data.get("versionName");
        if (!packageName.equals(data.get("packageName")) || !(code instanceof Integer || code instanceof Long)
                || !(version instanceof String) || ((Number) code).longValue() <= 0
                || !((String) version).matches("[0-9]+(?:\\.[0-9]+){1,3}(?:-[a-zA-Z0-9.-]+)?")
                || ((String) version).length() > 40) {
            throw new JSONException("Invalid Taskscope release metadata");
        }
        return new Release(((Number) code).longValue(), (String) version);
    }
}
