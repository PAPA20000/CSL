package net.kdt.pojavlaunch.shortcuts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads and caches player skin head icons from Mojang's API.
 *
 * Flow:
 * 1. Resolve username → UUID via {@code api.mojang.com}
 * 2. Get profile texture via {@code sessionserver.mojang.com}
 * 3. Decode Base64 texture data, extract skin URL
 * 4. Download skin PNG, crop head portion (8×8 area, top-left)
 * 5. Cache the head bitmap to internal storage
 */
public class ShortcutSkinHeadHelper {

    private static final String TAG = "ShortcutSkinHeadHelper";
    private static final String CACHE_DIR = "skin_heads";
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private static final String MOJANG_API_PROFILE =
            "https://api.mojang.com/users/profiles/minecraft/";
    private static final String SESSION_SERVER_PROFILE =
            "https://sessionserver.mojang.com/session/minecraft/profile/";

    /**
     * Get the skin head bitmap for a Minecraft username.
     * Checks local cache first, downloads if needed.
     *
     * @param context  Application context
     * @param username Minecraft username (case-insensitive)
     * @return The 64×64 head bitmap, or null on failure
     */
    @Nullable
    public static Bitmap getSkinHead(@NonNull Context context,
                                      @NonNull String username) {
        // Check cache first
        Bitmap cached = loadCached(context, username);
        if (cached != null) return cached;

        // Download skin head
        try {
            // Step 1: Resolve username → UUID
            String uuid = resolveUuid(username);
            if (uuid == null) {
                Log.w(TAG, "Failed to resolve UUID for: " + username);
                return null;
            }

            // Step 2: Get texture data from session server
            String skinUrl = getSkinUrl(uuid);
            if (skinUrl == null) {
                Log.w(TAG, "Failed to get skin URL for: " + uuid);
                return null;
            }

            // Step 3: Download skin and crop head
            Bitmap head = downloadAndCropHead(skinUrl);
            if (head != null) {
                cacheSkinHead(context, username, head);
            }
            return head;

        } catch (Exception e) {
            Log.e(TAG, "Failed to get skin head for: " + username, e);
            return null;
        }
    }

    /**
     * Delete cached skin head for a username.
     */
    public static void clearCache(@NonNull Context context,
                                   @NonNull String username) {
        File cacheFile = getCacheFile(context, username);
        if (cacheFile.exists()) {
            cacheFile.delete();
        }
    }

    // ─── UUID Resolution ──────────────────────────────────────────────

    @Nullable
    private static String resolveUuid(@NonNull String username)
            throws IOException {
        String url = MOJANG_API_PROFILE + username.toLowerCase();
        String json = Tools.read(url);

        if (json == null || json.isEmpty()) return null;

        // Parse UUID from the minimal JSON response: {"name":"...","id":"..."}
        int idIdx = json.indexOf("\"id\":\"");
        if (idIdx == -1) return null;
        idIdx += 6;
        int endIdx = json.indexOf("\"", idIdx);
        if (endIdx == -1) return null;

        String rawUuid = json.substring(idIdx, endIdx);

        // Format as standard UUID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        return rawUuid.replaceAll(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                "$1-$2-$3-$4-$5");
    }

    // ─── Skin URL Extraction ──────────────────────────────────────────

    @Nullable
    private static String getSkinUrl(@NonNull String uuid)
            throws IOException {
        String url = SESSION_SERVER_PROFILE + uuid;
        String json = Tools.read(url);

        if (json == null || json.isEmpty()) return null;

        // Extract the "value" field from the JSON response
        int valueIdx = json.indexOf("\"value\":\"");
        if (valueIdx == -1) return null;
        valueIdx += 9;
        int endIdx = json.indexOf("\"", valueIdx);
        if (endIdx == -1) return null;

        String base64Value = json.substring(valueIdx, endIdx);

        // Decode Base64
        byte[] decoded;
        try {
            decoded = Base64.decode(base64Value, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Failed to decode texture value", e);
            return null;
        }

        String decodedStr = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);

        // Extract skin URL from the decoded JSON
        // Looking for: "url":"http://textures.minecraft.net/texture/..."
        int urlIdx = decodedStr.indexOf("\"url\":\"");
        if (urlIdx == -1) return null;
        urlIdx += 7;
        int urlEnd = decodedStr.indexOf("\"", urlIdx);
        if (urlEnd == -1) return null;

        return decodedStr.substring(urlIdx, urlEnd);
    }

    // ─── Skin Download + Head Crop ─────────────────────────────────────

    @Nullable
    private static Bitmap downloadAndCropHead(@NonNull String skinUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL(skinUrl).openConnection();
            conn.setRequestProperty("User-Agent", Tools.APP_NAME);

            Bitmap skin = BitmapFactory.decodeStream(conn.getInputStream());
            conn.disconnect();

            if (skin == null) return null;

            // The head is the 8×8 pixel area starting at (8, 8) in a 64×64 skin.
            // We use the helmet/hat layer at (40, 8) for overlay.
            int skinWidth = skin.getWidth();
            int skinHeight = skin.getHeight();

            // For modern 64×64 skins: head is at (8, 8), size 8×8
            // Scale to 64×64 output for crisp shortcut icons
            int headSize; // in skin pixels
            int headX, headY;

            if (skinWidth == 64 && skinHeight == 64) {
                // Modern skin format
                headX = 8;
                headY = 8;
                headSize = 8;
            } else if (skinWidth == 64 && skinHeight == 32) {
                // Old skin format
                headX = 8;
                headY = 8;
                headSize = 8;
            } else {
                // Unknown format — use full image scaled down
                headX = 0;
                headY = 0;
                headSize = Math.min(skinWidth, skinHeight);
            }

            // Crop the head area
            Bitmap head = Bitmap.createBitmap(skin, headX, headY,
                    headSize, headSize);

            // Scale to 128×128 for crisp shortcut icons
            Bitmap scaled = Bitmap.createScaledBitmap(head, 128, 128, true);

            if (head != scaled) {
                head.recycle();
            }
            skin.recycle();

            return scaled;

        } catch (Exception e) {
            Log.e(TAG, "Failed to download/crop skin head", e);
            return null;
        }
    }

    // ─── Caching ──────────────────────────────────────────────────────

    @Nullable
    private static Bitmap loadCached(@NonNull Context context,
                                      @NonNull String username) {
        File cacheFile = getCacheFile(context, username);
        if (!cacheFile.exists()) return null;

        // Check TTL
        long age = System.currentTimeMillis() - cacheFile.lastModified();
        if (age > CACHE_TTL_MS) {
            cacheFile.delete();
            return null;
        }

        return BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
    }

    private static void cacheSkinHead(@NonNull Context context,
                                       @NonNull String username,
                                       @NonNull Bitmap head) {
        File cacheFile = getCacheFile(context, username);
        File parent = cacheFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
            head.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } catch (IOException e) {
            Log.w(TAG, "Failed to cache skin head", e);
        }
    }

    private static File getCacheFile(@NonNull Context context,
                                      @NonNull String username) {
        File dir = new File(context.getFilesDir(), CACHE_DIR);
        return new File(dir, username.toLowerCase() + ".png");
    }
}
