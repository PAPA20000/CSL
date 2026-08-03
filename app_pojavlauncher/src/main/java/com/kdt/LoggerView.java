package com.kdt;

import android.content.Context;
import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.R;

/**
 * CS PREMIUM GLASS TERMINAL (Phase 3).
 *
 * Keeps every legacy behavior (log toggle, autoscroll toggle, cancel, stream
 * batching, animated line arrival) and adds:
 * - translucent glass chrome + status strip (line counter / PAUSED state)
 * - live SEARCH (filter without losing the raw buffer)
 * - PAUSE (keeps buffering silently, resumes cleanly)
 * - COPY to clipboard · EXPORT via share sheet · CLEAR
 * - severity highlighting: error / warning / success / info / debug
 */
public class LoggerView extends ConstraintLayout {
    private Logger.eventLogListener mLogListener;
    private ToggleButton mLogToggle;
    private RecyclerView mLogRecycler;
    private LogLineAdapter mAdapter;
    private LinearLayoutManager mLayoutManager;
    private TextView mCountText;
    private View mLiveDot;
    private boolean mKeepAutoscroll = true;
    private boolean mPaused = false;
    /** Batches rapid emissions so the animator keeps up with burst logger traffic. */
    private final java.util.ArrayDeque<String> mPendingLines = new java.util.ArrayDeque<>();
    private boolean mFlushScheduled = false;

    public LoggerView(@NonNull Context context) {
        this(context, null);
    }

    public LoggerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        // Triggers the log view shown state by default when viewing it
            if (mLogToggle != null) mLogToggle.setChecked(visibility == VISIBLE);
    }

    private void init(){
        inflate(getContext(), R.layout.view_logger, this);

        // ── Animated terminal stream ──
        mLogRecycler = findViewById(R.id.content_log_recycler);
        mAdapter = new LogLineAdapter();
        mLayoutManager = new LinearLayoutManager(getContext());
        mLogRecycler.setLayoutManager(mLayoutManager);
        mLogRecycler.setAdapter(mAdapter);
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setAddDuration(240);
        animator.setChangeDuration(100);
        animator.setMoveDuration(160);
        animator.setRemoveDuration(120);
        mLogRecycler.setItemAnimator(animator);
        mLogRecycler.setVisibility(GONE);

        mCountText = findViewById(R.id.log_count_text);
        mLiveDot = findViewById(R.id.log_live_dot);

        // Toggle log visibility
        mLogToggle = findViewById(R.id.content_log_toggle_log);
        mLogToggle.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    if(isChecked) {
                        // Soft body fade-in: the terminal "opens" instead of popping
                        mLogRecycler.setAlpha(0f);
                        mLogRecycler.setTranslationY(dp(14));
                        mLogRecycler.setVisibility(VISIBLE);
                        mLogRecycler.animate().alpha(1f).translationY(0f)
                                .setDuration(320)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .start();
                        if (mAdapter.getItemCount() == 0) {
                            mAdapter.appendLine(new LogLineAdapter.LogLine(
                                    tinted("› Waiting for game output…", 0xFF6B7280),
                                    "› Waiting for game output…"));
                        }
                        Logger.addLogListener(mLogListener);
                    }else{
                        mAdapter.clear();
                        Logger.removeLogListener(mLogListener);
                        mLogRecycler.animate().cancel();
                        mLogRecycler.setVisibility(GONE);
                    }
                    updateStatus();
                });
        mLogToggle.setChecked(false);

        // Remove the loggerView from the user View
        ImageButton cancelButton = findViewById(R.id.log_view_cancel);
        cancelButton.setOnClickListener(view -> LoggerView.this.setVisibility(GONE));

        // Autoscroll switch
        ToggleButton autoscrollToggle = findViewById(R.id.content_log_toggle_autoscroll);
        autoscrollToggle.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    mKeepAutoscroll = isChecked;
                    if(isChecked) scrollToEnd(false);
                }
        );
        autoscrollToggle.setChecked(true);

        // ── Phase 3 controls ──
        EditText search = findViewById(R.id.log_search_input);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                mAdapter.setFilter(s != null ? s.toString() : null);
                if (mKeepAutoscroll) scrollToEnd(false);
                updateStatus();
            }
        });

        ImageButton pause = findViewById(R.id.log_btn_pause);
        pause.setOnClickListener(v -> togglePause());

        ImageButton copy = findViewById(R.id.log_btn_copy);
        copy.setOnClickListener(v -> copyLogs());

        ImageButton export = findViewById(R.id.log_btn_export);
        export.setOnClickListener(v -> exportLogs());

        ImageButton clear = findViewById(R.id.log_btn_clear);
        clear.setOnClickListener(v -> {
            mAdapter.clear();
            updateStatus();
        });

        // Listen to logs — batched + buttery
        mLogListener = text -> {
            if(mLogRecycler.getVisibility() != VISIBLE) return;
            synchronized (mPendingLines) {
                mPendingLines.add(text);
                // paused → keep buffering silently (bounded) without flushing
                if (mPaused && mPendingLines.size() > 600) mPendingLines.poll();
            }
            if (!mPaused) scheduleFlush();
        };

        // Soft pulse on the live indicator while streaming
        if (mLiveDot != null) {
            android.animation.ValueAnimator pulse = android.animation.ValueAnimator.ofFloat(1f, 0.35f);
            pulse.setDuration(900);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            pulse.addUpdateListener(a -> {
                View dot = mLiveDot;
                if (dot != null) dot.setAlpha((Float) a.getAnimatedValue());
            });
            pulse.start();
        }
    }

    // ═══════════════════════ ACTIONS ═══════════════════════

    private void togglePause() {
        mPaused = !mPaused;
        if (!mPaused) scheduleFlush(); // drain everything buffered while paused
        if (mLiveDot != null) {
            mLiveDot.getBackground().setTint(mPaused ? 0xFFFFB020 : 0xFF22C55E);
        }
        ImageButton pause = findViewById(R.id.log_btn_pause);
        if (pause != null) pause.setColorFilter(mPaused ? 0xFFFFB020 : 0xFFE4E4EA);
        updateStatus();
    }

    private void copyLogs() {
        String text = mAdapter.dumpText();
        if (text.isEmpty()) {
            Toast.makeText(getContext(), R.string.cs_log_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("game_logs", text));
        }
        Toast.makeText(getContext(), R.string.cs_log_copied, Toast.LENGTH_SHORT).show();
    }

    private void exportLogs() {
        String text = mAdapter.dumpText();
        if (text.isEmpty()) {
            Toast.makeText(getContext(), R.string.cs_log_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "CS Launcher V3 — Game Log");
        send.putExtra(Intent.EXTRA_TEXT, text);
        try {
            getContext().startActivity(Intent.createChooser(send,
                    getContext().getString(R.string.cs_log_export)));
        } catch (Exception ignored) {}
    }

    private void updateStatus() {
        if (mCountText == null) return;
        String state = mPaused ? "PAUSED" : "LIVE";
        int raw = mAdapter.rawCount();
        int vis = mAdapter.visibleCount();
        mCountText.setText(vis == raw
                ? raw + " lines • " + state
                : vis + "/" + raw + " lines • " + state);
    }

    // ═══════════════════════ STREAM ═══════════════════════

    private void scheduleFlush() {
        synchronized (mPendingLines) {
            if (mFlushScheduled) return;
            mFlushScheduled = true;
        }
        postDelayed(this::flushPending, 90);
    }

    private void flushPending() {
        String line;
        boolean inserted = false;
        for (;;) {
            synchronized (mPendingLines) {
                line = mPendingLines.poll();
                if (line == null) {
                    mFlushScheduled = false;
                    break;
                }
            }
            mAdapter.appendLine(new LogLineAdapter.LogLine(colorizeLine(line), line));
            inserted = true;
        }
        if (inserted) {
            if (mKeepAutoscroll) scrollToEnd(true);
            updateStatus();
        }
    }

    /** Glide to the newest line; immediate jump for toggle-driven seeks. */
    private void scrollToEnd(boolean smooth) {
        int last = mAdapter.getItemCount() - 1;
        if (last < 0) return;
        if (smooth) {
            mLogRecycler.smoothScrollToPosition(last);
        } else {
            mLogRecycler.scrollToPosition(last);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static CharSequence tinted(String text, int color) {
        android.text.SpannableString s = new android.text.SpannableString(text);
        s.setSpan(new android.text.style.ForegroundColorSpan(color), 0, text.length(),
                android.text.SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** Severity highlighting: error → red, warn → amber, success → green, info → silver. */
    private CharSequence colorizeLine(String line) {
        int color = 0xFFC9CBD6; // default body tone
        String up = line.toUpperCase(java.util.Locale.US);
        if (up.contains("ERROR") || up.contains("FATAL") || up.contains("EXCEPTION")
                || up.contains("CAUSED BY") || up.contains("FAILED")) {
            color = 0xFFFF6B74; // error red
        } else if (up.contains("WARN")) {
            color = 0xFFFFB020; // warning amber
        } else if (up.contains("SUCCESS") || up.contains("DONE!") || up.contains("DONE ")
                || up.contains("FINISHED") || up.contains("STARTED SERVER")
                || up.contains("SUCCESSFULLY")) {
            color = 0xFF22C55E; // success green
        } else if (up.startsWith("[INFO]") || up.contains(" INFO ")) {
            color = 0xFFE4E4EA; // silver
        } else if (up.contains("DEBUG")) {
            color = 0xFF6B7280; // dim
        }
        return tinted(line, color);
    }
}
