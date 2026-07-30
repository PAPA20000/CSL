package com.kdt;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageButton;
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
 * A class able to display logs to the user.
 * It has support for the Logger class
 *
 * Phase-6 premium terminal (Req: animated logs):
 * - one animated row per log line (fade+slide via item animator)
 * - silky smooth auto-scroll that tracks the stream
 * - severity-tinted lines (muted terminal palette)
 * - soft body fade-in when the terminal opens
 * - live indicator pulse while streaming
 */
public class LoggerView extends ConstraintLayout {
    private Logger.eventLogListener mLogListener;
    private ToggleButton mLogToggle;
    private RecyclerView mLogRecycler;
    private LogLineAdapter mAdapter;
    private LinearLayoutManager mLayoutManager;
    private boolean mKeepAutoscroll = true;
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
        mLogToggle.setChecked(visibility == VISIBLE);
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
        animator.setAddDuration(260);      // line fade+settle rhythm
        animator.setChangeDuration(120);
        animator.setMoveDuration(180);
        animator.setRemoveDuration(140);
        mLogRecycler.setItemAnimator(animator);
        mLogRecycler.setVisibility(GONE);

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
                                    tinted("› Waiting for game output…", 0xFF7C7C88)));
                        }
                        Logger.addLogListener(mLogListener);
                    }else{
                        mAdapter.clear();
                        Logger.removeLogListener(mLogListener);
                        mLogRecycler.animate().cancel();
                        mLogRecycler.setVisibility(GONE);
                    }
                });
        mLogToggle.setChecked(false);

        // Remove the loggerView from the user View
        ImageButton cancelButton = findViewById(R.id.log_view_cancel);
        cancelButton.setOnClickListener(view -> LoggerView.this.setVisibility(GONE));

        //Set up the autoscroll switch
        ToggleButton autoscrollToggle = findViewById(R.id.content_log_toggle_autoscroll);
        autoscrollToggle.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    mKeepAutoscroll = isChecked;
                    if(isChecked) scrollToEnd(false);
                }
        );
        autoscrollToggle.setChecked(true);

        // Listen to logs — batched + buttery: each line animates in on arrival
        mLogListener = text -> {
            if(mLogRecycler.getVisibility() != VISIBLE) return;
            synchronized (mPendingLines) {
                mPendingLines.add(text);
            }
            scheduleFlush();
        };

        // Soft pulse on the live indicator while the view is streaming
        View liveDot = findViewById(R.id.log_live_dot);
        if (liveDot != null) {
            android.animation.ValueAnimator pulse = android.animation.ValueAnimator.ofFloat(1f, 0.35f);
            pulse.setDuration(900);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            pulse.addUpdateListener(a -> liveDot.setAlpha((Float) a.getAnimatedValue()));
            pulse.start();
        }
    }

    private void scheduleFlush() {
        synchronized (mPendingLines) {
            if (mFlushScheduled) return;
            mFlushScheduled = true;
        }
        postDelayed(this::flushPending, 90); // micro-batch bursts, still feels instant
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
            mAdapter.appendLine(new LogLineAdapter.LogLine(colorizeLine(line)));
            inserted = true;
        }
        if (inserted && mKeepAutoscroll) scrollToEnd(true);
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

    /** Tints a log line by severity (terminal-appropriate muted tones). */
    private CharSequence colorizeLine(String line) {
        int color = 0xFFC9CBD6; // default body tone
        String up = line.toUpperCase(java.util.Locale.US);
        if (up.contains("ERROR") || up.contains("FATAL") || up.contains("EXCEPTION")
                || up.contains("CAUSED BY")) {
            color = 0xFFE5A0A6; // muted rose
        } else if (up.contains("WARN")) {
            color = 0xFFD8C79A; // muted amber
        } else if (up.startsWith("[INFO]") || up.contains(" INFO ")) {
            color = 0xFFE4E4EA; // silver
        } else if (up.contains("DEBUG")) {
            color = 0xFF7C7C88; // dim
        }
        return tinted(line, color);
    }

}
