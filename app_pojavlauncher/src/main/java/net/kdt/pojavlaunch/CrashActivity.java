package net.kdt.pojavlaunch;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;


import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Dedicated crash screen shown when the game process exits with a non-zero code.
 * Displays a structured summary + full log, and supports uploading the log to
 * mclo.gs for a shareable link.
 */
public class CrashActivity extends AppCompatActivity {

    public static final String EXTRA_LOG_PATH = "crash_log_path";
    public static final String EXTRA_EXIT_CODE = "crash_exit_code";
    private static final String MCLOGS_ENDPOINT = "https://api.mclo.gs/1/log";

    private TextView mSummary;
    private TextView mLogView;
    private TextView mResultUrl;
    private ProgressBar mLoading;
    private View mUploadResultCard;
    private Button mUploadBtn;
    private String mFullLog = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash);

        mSummary = findViewById(R.id.crash_summary);
        mLogView = findViewById(R.id.crash_log_view);
        mResultUrl = findViewById(R.id.upload_result_url);
        mLoading = findViewById(R.id.crash_loading);
        mUploadResultCard = findViewById(R.id.upload_result_card);
        mUploadBtn = findViewById(R.id.btn_upload_log);

        String logPath = getIntent().getStringExtra(EXTRA_LOG_PATH);
        int exitCode = getIntent().getIntExtra(EXTRA_EXIT_CODE, -1);

        if (exitCode != -1) {
            mSummary.setText("The game exited with code " + exitCode + ". " +
                    "Check the log below to find the cause.");
        }

        loadLog(logPath);

        findViewById(R.id.crash_back_button).setOnClickListener(v -> goHome());
        findViewById(R.id.btn_home).setOnClickListener(v -> goHome());

        mUploadBtn.setOnClickListener(v -> uploadLog());
        findViewById(R.id.btn_copy_log).setOnClickListener(v -> copyLog());

        findViewById(R.id.btn_copy_url).setOnClickListener(v -> copyText(mResultUrl.getText().toString(), "Link copied"));
        findViewById(R.id.btn_share_url).setOnClickListener(v -> shareText(mResultUrl.getText().toString(), "Crash log"));
    }

    private void goHome() {
        // Return to the launcher's main screen instead of fully exiting the app.
        Intent home = new Intent(this, LauncherActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home);
        finish();
    }

    @Override
    public void onBackPressed() {
        goHome();
    }

    private void loadLog(String logPath) {
        File logFile = (logPath != null) ? new File(logPath)
                : new File(Tools.DIR_GAME_HOME, Tools.LAST_CRASH_LOG_NAME);
        if (logFile == null || !logFile.exists()) {
            logFile = new File(Tools.DIR_GAME_HOME, Tools.LATEST_LOG_NAME);
        }
        if (logFile.exists()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                int lines = 0;
                while ((line = br.readLine()) != null && lines < 4000) {
                    sb.append(line).append('\n');
                    lines++;
                }
                mFullLog = sb.toString();
                mLogView.setText(mFullLog);
                detectSummary(mFullLog);
            } catch (Exception e) {
                mLogView.setText("(Unable to read log file)");
            }
        } else {
            mLogView.setText("(No crash log found)");
        }
    }

    /** Best-effort extraction of a short, human-readable crash reason. */
    private void detectSummary(String log) {
        if (TextUtils.isEmpty(log)) return;
        String lower = log.toLowerCase();
        String reason = null;
        if (lower.contains("outofmemoryerror") || lower.contains("java.lang.outofmemory")) {
            reason = "Out of memory — try lowering the RAM allocation or resolution.";
        } else if (lower.contains("could not create the java virtual machine")) {
            reason = "JVM failed to start — check your Java arguments.";
        } else if (lower.contains("nosuchmethoderror") || lower.contains("nosuchfielderr")) {
            reason = "Mod incompatibility detected (NoSuchMethodError).";
        } else if (lower.contains("gl4es") && lower.contains("error")) {
            reason = "Renderer/GPU error detected — try a different renderer.";
        } else if (lower.contains("exception")) {
            int idx = lower.lastIndexOf("exception");
            int start = Math.max(0, idx - 80);
            String snippet = log.substring(start, Math.min(log.length(), idx + 40)).replace('\n', ' ').trim();
            reason = "Exception: " + snippet;
        }
        if (reason != null) {
            mSummary.setText(reason);
        }
    }

    private void copyLog() {
        if (TextUtils.isEmpty(mFullLog)) {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show();
            return;
        }
        copyText(mFullLog, "Log copied to clipboard");
    }

    private void copyText(String text, String msg) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("crashlog", text));
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareText(String text, String title) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(share, title));
    }

    private void uploadLog() {
        if (TextUtils.isEmpty(mFullLog)) {
            Toast.makeText(this, "No log to upload", Toast.LENGTH_SHORT).show();
            return;
        }
        mUploadBtn.setEnabled(false);
        mLoading.setVisibility(View.VISIBLE);
        mUploadResultCard.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                String url = uploadToMclogs(mFullLog);
                new Handler(Looper.getMainLooper()).post(() -> {
                    mLoading.setVisibility(View.GONE);
                    mUploadBtn.setEnabled(true);
                    if (url != null) {
                        mResultUrl.setText(url);
                        mUploadResultCard.setVisibility(View.VISIBLE);
                        Toast.makeText(CrashActivity.this, "Upload successful", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(CrashActivity.this, "Upload failed. Check your connection.", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    mLoading.setVisibility(View.GONE);
                    mUploadBtn.setEnabled(true);
                    Toast.makeText(CrashActivity.this, "Upload failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * Uploads the given log to the mclo.gs API.
     * @return the shareable URL, or null on failure.
     */
    private static String uploadToMclogs(String content) throws Exception {
        URL url = new URL(MCLOGS_ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        conn.setRequestProperty("User-Agent", "CSLauncher");

        String body = "content=" + Uri.encode(content);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder resp = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) resp.append(line);
        reader.close();

        if (code < 200 || code >= 300) return null;

        JSONObject json = new JSONObject(resp.toString());
        if (json.optBoolean("success", false)) {
            return json.optString("url", null);
        }
        return null;
    }
}
