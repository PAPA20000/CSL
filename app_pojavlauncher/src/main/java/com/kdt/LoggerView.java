package com.kdt;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.R;

/**
 * A class able to display logs to the user.
 * It has support for the Logger class
 */
public class LoggerView extends ConstraintLayout {
    private Logger.eventLogListener mLogListener;
    private ToggleButton mLogToggle;
    private DefocusableScrollView mScrollView;
    private TextView mLogTextView;


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

    /**
     * Inflate the layout, and add component behaviors
     */
    private void init(){
        inflate(getContext(), R.layout.view_logger, this);
        mLogTextView = findViewById(R.id.content_log_view);
        mLogTextView.setTypeface(Typeface.MONOSPACE);
        //TODO clamp the max text so it doesn't go oob
        mLogTextView.setMaxLines(Integer.MAX_VALUE);
        mLogTextView.setEllipsize(null);
        mLogTextView.setVisibility(GONE);

        // Toggle log visibility
        mLogToggle = findViewById(R.id.content_log_toggle_log);
        mLogToggle.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    mLogTextView.setVisibility(isChecked ? VISIBLE : GONE);
                    if(isChecked) {
                        // Premium empty state while the game warms up (Req-3)
                        if (mLogTextView.length() == 0) {
                            android.text.SpannableString hint = new android.text.SpannableString("› Waiting for game output…");
                            hint.setSpan(new android.text.style.ForegroundColorSpan(0xFF7C7C88), 0, hint.length(),
                                    android.text.SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                            mLogTextView.setText(hint);
                        }
                        Logger.addLogListener(mLogListener);
                    }else{
                        mLogTextView.setText("");
                        Logger.removeLogListener(mLogListener);
                    }
                });
        mLogToggle.setChecked(false);

        // Remove the loggerView from the user View
        ImageButton cancelButton = findViewById(R.id.log_view_cancel);
        cancelButton.setOnClickListener(view -> LoggerView.this.setVisibility(GONE));

        // Set the scroll view
        mScrollView = findViewById(R.id.content_log_scroll);
        mScrollView.setKeepFocusing(true);

        //Set up the autoscroll switch
        ToggleButton autoscrollToggle = findViewById(R.id.content_log_toggle_autoscroll);
        autoscrollToggle.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    if(isChecked) mScrollView.fullScroll(View.FOCUS_DOWN);
                    mScrollView.setKeepFocusing(isChecked);
                }
        );
        autoscrollToggle.setChecked(true);

        // Listen to logs — Phase-5 premium stream (Req-3):
        // level-tinted lines + glide-to-bottom instead of a hard jump
        mLogListener = text -> {
            if(mLogTextView.getVisibility() != VISIBLE) return;
            post(() -> {
                mLogTextView.append(colorizeLine(text) + "\n");
                if(mScrollView.isKeepFocusing()) {
                    mScrollView.post(() -> {
                        // Smooth glide: the newest line slips softly into view
                        int max = Math.max(0, mLogTextView.getBottom() - mScrollView.getHeight()
                                + mScrollView.getPaddingBottom() + mLogTextView.getPaddingBottom());
                        mScrollView.smoothScrollTo(0, Math.min(max, mScrollView.getScrollY() + dp(56)));
                    });
                }
            });

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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** Tints a log line by severity (terminal-appropriate muted tones). */
    private CharSequence colorizeLine(String line) {
        android.text.SpannableString span = new android.text.SpannableString(line);
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
        span.setSpan(new android.text.style.ForegroundColorSpan(color), 0, line.length(),
                android.text.SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

}
