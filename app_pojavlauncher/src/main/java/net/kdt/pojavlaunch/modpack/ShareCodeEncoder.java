package net.kdt.pojavlaunch.modpack;

import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * ShareCode encoding/decoding for modpack manifests.
 * Offline format: CS-MP-<base64(gzip(json))>
 * Gist format:    CS-MP-GH-<gistId>
 */
public class ShareCodeEncoder {

    private static final String PREFIX_OFFLINE = "CS-MP-";
    private static final String PREFIX_GIST    = "CS-MP-GH-";

    /** Maximum length for a Base64 offline code before we suggest Gist. */
    private static final int MAX_OFFLINE_CHARS = 16_384;

    /**
     * Encode a JSON manifest into an offline share code.
     * @return "CS-MP-" + Base64(gzip(json))
     * @throws IOException if compression fails
     */
    public static String encodeOffline(String jsonManifest) throws IOException {
        byte[] compressed = gzipCompress(jsonManifest.getBytes("UTF-8"));
        String b64 = Base64.encodeToString(compressed, Base64.NO_WRAP | Base64.URL_SAFE);
        return PREFIX_OFFLINE + b64;
    }

    /**
     * Decode an offline share code back to the original JSON manifest.
     * @param code "CS-MP-..." (with or without prefix)
     */
    public static String decodeOffline(String code) throws IOException {
        String b64 = stripPrefix(code, PREFIX_OFFLINE);
        byte[] compressed = Base64.decode(b64, Base64.NO_WRAP | Base64.URL_SAFE);
        byte[] decompressed = gzipDecompress(compressed);
        return new String(decompressed, "UTF-8");
    }

    /**
     * Build a Gist share code from a gist ID.
     */
    public static String encodeGist(String gistId) {
        return PREFIX_GIST + gistId;
    }

    /**
     * Extract a gist ID from a Gist share code.
     */
    public static String decodeGistId(String code) {
        return stripPrefix(code, PREFIX_GIST);
    }

    /** Check if a code is a gist-type code. */
    public static boolean isGistCode(String code) {
        return code != null && code.startsWith(PREFIX_GIST);
    }

    /** Check if a code is an offline-type code. */
    public static boolean isOfflineCode(String code) {
        return code != null && code.startsWith(PREFIX_OFFLINE);
    }

    /** Suggest using Gist if the offline code exceeds the length threshold. */
    public static boolean isOfflineCodeTooLong(String code) {
        return code != null && code.length() > MAX_OFFLINE_CHARS;
    }

    // ── internal helpers ──

    private static String stripPrefix(String code, String prefix) {
        if (code.startsWith(prefix)) {
            return code.substring(prefix.length());
        }
        return code;
    }

    private static byte[] gzipCompress(byte[] input) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(input);
        }
        return bos.toByteArray();
    }

    private static byte[] gzipDecompress(byte[] compressed) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(compressed.length * 2);
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = gz.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
        }
        return bos.toByteArray();
    }
}
