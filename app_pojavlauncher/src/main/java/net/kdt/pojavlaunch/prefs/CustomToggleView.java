package net.kdt.pojavlaunch.prefs;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

public class CustomToggleView extends View {

    private boolean mChecked = false;
    private float mAnimProgress = 0f;
    private android.graphics.LinearGradient mOnTrackShader;
    private float mOnTrackShaderWidth;
    private ValueAnimator mAnimator;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTrackRect = new RectF();
    private OnCheckedChangeListener mListener;

    public interface OnCheckedChangeListener {
        void onCheckedChanged(CustomToggleView view, boolean isChecked);
    }

    public CustomToggleView(Context context) {
        super(context);
        init();
    }

    public CustomToggleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CustomToggleView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        mAnimProgress = mChecked ? 1f : 0f;
    }

    public void setChecked(boolean checked) {
        setChecked(checked, true);
    }

    public void setChecked(boolean checked, boolean animate) {
        if (mChecked == checked) return;
        mChecked = checked;
        
        if (mAnimator != null) {
            mAnimator.cancel();
        }

        if (animate) {
            mAnimator = ValueAnimator.ofFloat(mAnimProgress, checked ? 1f : 0f);
            mAnimator.setDuration(220);
            mAnimator.setInterpolator(new DecelerateInterpolator());
            mAnimator.addUpdateListener(animation -> {
                mAnimProgress = (float) animation.getAnimatedValue();
                invalidate();
            });
            mAnimator.start();
        } else {
            mAnimProgress = checked ? 1f : 0f;
            invalidate();
        }

        if (mListener != null) {
            mListener.onCheckedChanged(this, mChecked);
        }
    }

    public boolean isChecked() {
        return mChecked;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        mListener = listener;
    }

    @Override
    public boolean performClick() {
        toggle();
        return super.performClick();
    }

    public void toggle() {
        setChecked(!mChecked, true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        float density = getResources().getDisplayMetrics().density;
        int defaultWidth = Math.round(52 * density);
        int defaultHeight = Math.round(28 * density);

        int width = (widthMode == MeasureSpec.EXACTLY) ? widthSize : defaultWidth;
        int height = (heightMode == MeasureSpec.EXACTLY) ? heightSize : defaultHeight;

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();

        // ── S4 toggle: obsidian glass track → platinum gradient beam ──
        mTrackRect.set(0, 0, w, h);
        float radius = h / 2f;
        float density = getResources().getDisplayMetrics().density;

        // Off track: obsidian glass (always drawn base, crossfaded under the beam)
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(0xFF14151B);
        canvas.drawRoundRect(mTrackRect, radius, radius, mPaint);

        // On track: horizontal platinum gradient beam fading in
        if (mAnimProgress > 0.001f) {
            if (mOnTrackShader == null || mOnTrackShaderWidth != w) {
                mOnTrackShader = new android.graphics.LinearGradient(0, 0, w, 0,
                        0xFFF4F4FA, 0xFFB9BBC4, android.graphics.Shader.TileMode.CLAMP);
                mOnTrackShaderWidth = w;
            }
            mPaint.setShader(mOnTrackShader);
            mPaint.setAlpha((int) (255 * mAnimProgress));
            canvas.drawRoundRect(mTrackRect, radius, radius, mPaint);
            mPaint.setShader(null);
            mPaint.setAlpha(255);
        }

        // Rim light on the track
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(Math.max(1f, density));
        mPaint.setColor(blendColors(0x2EFFFFFF, 0x66B9BBC4, mAnimProgress));
        canvas.drawRoundRect(mTrackRect, radius, radius, mPaint);
        mPaint.setStyle(Paint.Style.FILL);

        // Thumb
        float padding = 3.5f * density;
        float thumbRadius = radius - padding;
        float minX = radius;
        float maxX = w - radius;
        float thumbX = minX + (maxX - minX) * mAnimProgress;
        float thumbY = h / 2f;

        // Violet energy halo when enabled
        if (mAnimProgress > 0.05f) {
            mPaint.setColor(blendColors(0x009B59E8, 0x4D9B59E8, mAnimProgress));
            canvas.drawCircle(thumbX, thumbY, thumbRadius + padding * 0.8f, mPaint);
        }

        // Platinum thumb body
        mPaint.setColor(blendColors(0xFFFFFFFF, 0xFFF2F2F6, mAnimProgress));
        canvas.drawCircle(thumbX, thumbY, thumbRadius, mPaint);

        // Thumb rim
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(Math.max(1f, density * 0.8f));
        mPaint.setColor(blendColors(0x338E929E, 0x99B9BBC4, mAnimProgress));
        canvas.drawCircle(thumbX, thumbY, thumbRadius - mPaint.getStrokeWidth() / 2f, mPaint);
        mPaint.setStyle(Paint.Style.FILL);

        // Obsidian pupil in the center of the thumb
        mPaint.setColor(blendColors(0xFF2A2B31, 0xFF191A20, mAnimProgress));
        canvas.drawCircle(thumbX, thumbY, thumbRadius * 0.42f, mPaint);
    }

    private int blendColors(int color1, int color2, float ratio) {
        int a = (int) (((color1 >> 24) & 0xff) * (1 - ratio) + ((color2 >> 24) & 0xff) * ratio);
        int r = (int) (((color1 >> 16) & 0xff) * (1 - ratio) + ((color2 >> 16) & 0xff) * ratio);
        int g = (int) (((color1 >> 8) & 0xff) * (1 - ratio) + ((color2 >> 8) & 0xff) * ratio);
        int b = (int) ((color1 & 0xff) * (1 - ratio) + (color2 & 0xff) * ratio);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
