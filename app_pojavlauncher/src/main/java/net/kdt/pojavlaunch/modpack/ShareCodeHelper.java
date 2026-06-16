package net.kdt.pojavlaunch.modpack;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Uploads/downloads modpack manifests to/from anonymous GitHub Gists.
 */
public class ShareCodeHelper {

    private static final String TAG = "ShareCodeHelper";
    private static final String GIST_API = "https://api.github.com/gists";
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT    = 30_000;

    /**
     * Upload a JSON manifest as an anonymous Gist.
     * @param manifestJson the full JSON manifest string
     * @param modpackName  display name for the gist description
     * @return the gist ID (the hex hash)
     * @throws IOException  on network / server error
     * @throws ShareCodeException on unexpected response shape
     */
    public static String uploadGist(String manifestJson, String modpackName)
            throws IOException, ShareCodeException {

        String filename = (modpackName != null ? sanitizeFilename(modpackName) : "modpack") + ".json";
        String description = "CS Launcher Modpack: " + (modpackName != null ? modpackName : "Untitled");

        // Build request body
        JSONObject body;
        try {
            JSONObject files = new JSONObject();
            JSONObject fileObj = new JSONObject();
            fileObj.put("content", manifestJson);
            files.put(filename, fileObj);

            body = new JSONObject();
            body.put("description", description);
            body.put("public", false);
            body.put("files", files);
        } catch (JSONException e) {
            throw new ShareCodeException("Failed to build request JSON", e);
        }

        byte[] postData = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(GIST_API);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData);
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String errorBody = readStream(conn.getErrorStream());
                throw new IOException("Gist API returned " + code + ": " + errorBody);
            }

            String responseBody = readStream(conn.getInputStream());
            try {
                JSONObject resp = new JSONObject(responseBody);

                if (!resp.has("id")) {
                    throw new ShareCodeException("Gist response missing 'id' field");
                }
                return resp.getString("id");
            } catch (JSONException e) {
                throw new ShareCodeException("Failed to parse Gist response", e);
            }

        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Download the content of a single-file Gist.
     * @param gistId the Gist ID (hex hash)
     * @return the raw content of the first file in the gist (expected manifest JSON)
     * @throws IOException on network / server error
     */
    public static String downloadGist(String gistId)
            throws IOException, ShareCodeException {

        HttpURLConnection conn = null;
        try {
            URL url = new URL(GIST_API + "/" + gistId);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String errorBody = readStream(conn.getErrorStream());
                throw new IOException("Gist download returned " + code + ": " + errorBody);
            }

            String responseBody = readStream(conn.getInputStream());
            try {
                JSONObject resp = new JSONObject(responseBody);

                JSONObject files = resp.optJSONObject("files");
                if (files == null || files.length() == 0) {
                    throw new ShareCodeException("Gist has no files");
                }

                // Pick the first file's content
                String firstKey = files.keys().next();
                JSONObject firstFile = files.getJSONObject(firstKey);
                String content = firstFile.optString("content", null);
                if (content == null) {
                    throw new ShareCodeException("Gist file \"" + firstKey + "\" has no content");
                }
                return content;
            } catch (JSONException e) {
                throw new ShareCodeException("Failed to parse Gist response", e);
            }

        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── helpers ──

    private static String readStream(InputStream stream) throws IOException {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private static String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    /** Specific exception for share-code logic. */
    public static class ShareCodeException extends Exception {
        public ShareCodeException(String message) { super(message); }
        public ShareCodeException(String message, Throwable cause) { super(message, cause); }
    }
}
