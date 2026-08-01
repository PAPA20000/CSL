package net.kdt.pojavlaunch.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class SkinFetchUtils {

    /** Fetch skin for a username from mc-heads.net and save it locally */
    public static void fetchAndSaveSkin(String username, File destSkinFile) {
        try {
            Log.i("SkinFetch", "Fetching skin for " + username);
            Tools.downloadFile("https://mc-heads.net/skin/" + username, destSkinFile.getAbsolutePath());
            Log.i("SkinFetch", "Skin saved to " + destSkinFile.getAbsolutePath());
        } catch (Exception e) {
            Log.w("SkinFetch", "Failed to fetch skin for " + username, e);
        }
    }

    /** Fetch head for a username and save it to cache */
    public static void fetchAndSaveHead(String username, File destHeadFile) {
        try {
            Log.i("SkinFetch", "Fetching head for " + username);
            Tools.downloadFile("https://mc-heads.net/head/" + username + "/100", destHeadFile.getAbsolutePath());
            Log.i("SkinFetch", "Head saved to " + destHeadFile.getAbsolutePath());
        } catch (Exception e) {
            Log.w("SkinFetch", "Failed to fetch head for " + username, e);
        }
    }
}
