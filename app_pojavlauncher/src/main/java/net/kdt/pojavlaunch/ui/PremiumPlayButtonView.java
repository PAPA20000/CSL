package net.kdt.pojavlaunch.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;

/**
 * Premium launch button used by profile cards / FastClient home.
 *
 * Idle: platinum capsule + a slow diagonal sheen.
 * Launching: horizontal progress wave, violet edge glow, particle burst and a
 * subtle morph pulse — visually distinct from the normal "download" spinner.
 *
 * Perf notes:
 * - Fixed preallocated paints/path/particle pool; zero per-frame allocations.
 * - Animators only run while attached and visible.
 * - Automatically mirrors ProgressKeeper task state so failure/cancel paths
 *   never leave the button stuck in the launching morph.
 */
public class PremiumPlayButtonView extends FrameLayout implements TaskCountListener {

    private static final int PARTICLE_COUNT = 14;
    private static final long FAILSAFE_MS = 9000L;

    private final Paint mBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mParticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mRoundPath = new Path();
    private final RectF mRect = new RectF();

    private LinearGradient mBaseGradient;
    private LinearGradient mSheenGradient;
    private LinearGradient mWaveGradient;

    private ValueAnimator mIdleAnimator;
    private ValueAnimator mLaunchAnimator;
    private ValueAnimator mEnergyAnimator;
    private float mIdleFraction = 0f;
    private float mLaunchFraction = 0f;
    private float mEnergyFraction = 0f;
    private boolean mLaunching;
    private boolean mExplicitLaunch;
    /** Phase 4: installed-game launch → energy beam, never a progress wave. */
    private boolean mEnergyMode;
    private boolean mAnimationsAllowed = true;

    private final float[] mPX = new float[PARTICLE_COUNT];
    private final float[] mPY = new float[PARTICLE_COUNT];
    private final float[] mVX = new float[PARTICLE_COUNT];
    private final float[] mVY = new float[PARTICLE_COUNT];
    private final int[] mPLife = new int[PARTICLE_COUNT];

    private final Runnable mFailsafeReset = () -> {
        mExplicitLaunch = false;
        if (ProgressKeeper.getTaskCount() == 0) setLaunching(false);
    };

    public PremiumPlayButtonView(@NonNull Context context) {
        this(context, null);
    }

    public PremiumPlayButtonView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PremiumPlayButtonView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        setClickable(true);
        setFocusable(true);
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(dp(1.2f));
        mParticlePaint.setStyle(Paint.Style.FILL);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mRect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f);
        mRoundPath.reset();
        mRoundPath.addRoundRect(mRect, h / 2f, h / 2f, Path.Direction.CW);

        mBaseGradient = new LinearGradient(0, 0, w, h,
                new int[]{0xFFFAFAFC, 0xFFE4E4EA, 0xFFBFC1CB},
                new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP);
        mSheenGradient = new LinearGradient(0, 0, w * 0.32f, h,
                new int[]{0x00FFFFFF, 0x7FFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        mWaveGradient = new LinearGradient(0, 0, Math.max(1, w), 0,
                new int[]{0x00E4E4EA, 0x66E4E4EA, 0xCC9B59E8, 0x66E4E4EA, 0x00E4E4EA},
                new float[]{0f, 0.28f, 0.5f, 0.72f, 1f}, Shader.TileMode.CLAMP);
        mBasePaint.setShader(mBaseGradient);
        mSheenPaint.setShader(mSheenGradient);
        mWavePaint.setShader(mWaveGradient);
    }

    /** Immediate premium launch morph. Call before dispatching LAUNCH_GAME. */
    public void beginLaunch() {
        mEnergyMode = false;
        mExplicitLaunch = true;
        setLaunching(true);
        spawnParticles();
        morph();
        removeCallbacks(mFailsafeReset);
        postDelayed(mFailsafeReset, FAILSAFE_MS);
    }

    /**
     * Phase 4 — premium launch for an ALREADY-INSTALLED game.
     *
     * Deliberately different from {@link #beginLaunch()}: no progress wave,
     * no download connotation. The button glows with an energy beam that
     * sweeps horizontally (an energy pulse, not a progress bar), the edge
     * pulses and particles burst — then Minecraft takes over via the boot
     * overlay. The task-count listener never drives this mode, so download
     * tasks that still fire during a normal launch cannot turn it back into
     * a "download" animation.
     */
    public void beginInstalledLaunch() {
        mEnergyMode = true;
        mExplicitLaunch = true;
        setLaunching(true);
        spawnParticles();
        startEnergyAnimator();
        morph();
        removeCallbacks(mFailsafeReset);
        postDelayed(mFailsafeReset, FAILSAFE_MS);
    }

    public boolean isEnergyMode() {
        return mEnergyMode;
    }

    private void morph() {
        animate().cancel();
        animate().scaleX(0.965f).scaleY(0.90f).setDuration(80)
                .withEndAction(() -> animate().scaleX(1.035f).scaleY(1.06f)
                        .setDuration(180)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> animate().scaleX(1f).scaleY(1f)
                                .setDuration(120).start())
                        .start())
                .start();
    }

    /** Return to the idle platinum state. */
    public void reset() {
        mExplicitLaunch = false;
        mEnergyMode = false;
        stopEnergyAnimator();
        setLaunching(false);
    }

    public boolean isLaunching() {
        return mLaunching;
    }

    private void setLaunching(boolean launching) {
        if (mLaunching == launching) return;
        mLaunching = launching;
        if (launching) {
            startLaunchAnimator();
            if (mEnergyMode) startEnergyAnimator();
        } else {
            stopLaunchAnimator();
            stopEnergyAnimator();
        }
        setSelected(launching);
        invalidate();
    }

    @Override
    public void onUpdateTaskCount(int taskCount) {
        if (mEnergyMode) return; // installed-launch: tasks are launch chores, not progress
        post(() -> {
            if (taskCount > 0) {
                setLaunching(true);
            } else {
                // Small settle so the wave completes instead of snapping off;
                // also completes click-launched runs once every launch task ends.
                postDelayed(() -> {
                    if (ProgressKeeper.getTaskCount() == 0) {
                        mExplicitLaunch = false;
                        setLaunching(false);
                    }
                }, 360);
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ProgressKeeper.addTaskCountListener(this, false);
        onUpdateTaskCount(ProgressKeeper.getTaskCount());
        if (mAnimationsAllowed) startIdleAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mFailsafeReset);
        ProgressKeeper.removeTaskCountListener(this);
        stopIdleAnimator();
        stopLaunchAnimator();
        stopEnergyAnimator();
        mLaunching = false;
        mExplicitLaunch = false;
        mEnergyMode = false;
        clearParticles();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        mAnimationsAllowed = visibility == View.VISIBLE;
        if (!mAnimationsAllowed) {
            stopIdleAnimator();
            stopLaunchAnimator();
            stopEnergyAnimator();
        } else {
            startIdleAnimator();
            if (mLaunching) startLaunchAnimator();
            if (mLaunching && mEnergyMode) startEnergyAnimator();
        }
    }

    private void startIdleAnimator() {
        if (mIdleAnimator != null || !mAnimationsAllowed || !isAttachedToWindow()) return;
        mIdleAnimator = ValueAnimator.ofFloat(0f, 1f);
        mIdleAnimator.setDuration(2400);
        mIdleAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mIdleAnimator.setInterpolator(new LinearInterpolator());
        mIdleAnimator.addUpdateListener(a -> {
            mIdleFraction = (Float) a.getAnimatedValue();
            if (!mLaunching) invalidate();
        });
        mIdleAnimator.start();
    }

    private void stopIdleAnimator() {
        if (mIdleAnimator != null) {
            mIdleAnimator.cancel();
            mIdleAnimator = null;
        }
    }

    private void startLaunchAnimator() {
        if (!mAnimationsAllowed || !isAttachedToWindow()) return;
        if (mLaunchAnimator == null) {
            mLaunchAnimator = ValueAnimator.ofFloat(-0.25f, 1.25f);
            mLaunchAnimator.setDuration(1150);
            mLaunchAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mLaunchAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            mLaunchAnimator.addUpdateListener(a -> {
                mLaunchFraction = (Float) a.getAnimatedValue();
                stepParticles();
                invalidate();
            });
        }
        if (!mLaunchAnimator.isStarted()) mLaunchAnimator.start();
    }

    private void stopLaunchAnimator() {
        if (mLaunchAnimator != null) {
            mLaunchAnimator.cancel();
            mLaunchAnimator = null;
        }
        clearParticles();
    }

    /** Phase 4 energy beam: a sweeping energy pulse, never a progress bar. */
    private void startEnergyAnimator() {
        if (!mAnimationsAllowed || !isAttachedToWindow()) return;
        if (mEnergyAnimator == null) {
            mEnergyAnimator = ValueAnimator.ofFloat(-0.2f, 1.2f);
            mEnergyAnimator.setDuration(950);
            mEnergyAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mEnergyAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            mEnergyAnimator.addUpdateListener(a -> {
                mEnergyFraction = (Float) a.getAnimatedValue();
                stepParticles();
                invalidate();
            });
        }
        if (!mEnergyAnimator.isStarted()) mEnergyAnimator.start();
    }

    private void stopEnergyAnimator() {
        if (mEnergyAnimator != null) {
            mEnergyAnimator.cancel();
            mEnergyAnimator = null;
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(mRoundPath);
        canvas.drawPath(mRoundPath, mBasePaint);

        // Idle sheen: a quiet diagonal sweep; runs only while visible.
        float sheenX = (-0.35f + mIdleFraction * 1.7f) * getWidth();
        canvas.save();
        canvas.translate(sheenX, 0);
        canvas.skew(-0.25f, 0f);
        canvas.drawRect(-getWidth() * 0.25f, 0, getWidth() * 0.5f, getHeight(), mSheenPaint);
        canvas.restore();

        if (mLaunching && mEnergyMode) {
            // Phase 4 energy beam: a swept energy pulse + comet head.
            // Visually the opposite of a progress bar — it charges, then the
            // game takes over through the boot overlay.
            canvas.save();
            float sweep = mEnergyFraction * getWidth() * 1.6f - getWidth() * 0.35f;
            canvas.translate(sweep, 0);
            canvas.drawRect(-getWidth() * 0.55f, 0, getWidth() * 0.45f, getHeight(), mWavePaint);
            float headX = getWidth() * 0.14f;
            float headY = getHeight() * 0.5f;
            mParticlePaint.setColor(0xCCFFE3B8);
            canvas.drawCircle(headX, headY, getHeight() * 0.42f, mParticlePaint);
            canvas.restore();
        } else if (mLaunching) {
            // Premium horizontal wave: progress-like motion, not a circular download.
            canvas.save();
            canvas.translate((mLaunchFraction - 0.5f) * getWidth(), 0);
            canvas.drawRect(-getWidth(), 0, getWidth(), getHeight(), mWavePaint);
            canvas.restore();
        }
        canvas.restoreToCount(save);

        // Child content (icon/text).
        super.dispatchDraw(canvas);

        // Glow + edge highlight above content.
        if (mLaunching) {
            float pulse = mEnergyMode
                    ? 0.6f + 0.4f * (float) Math.sin(mEnergyFraction * Math.PI * 2.0)
                    : 0.5f + 0.5f * (float) Math.sin(mLaunchFraction * Math.PI * 2.0);
            mStrokePaint.setColor(mEnergyMode ? 0x66FFC97A : 0x559B59E8);
            mStrokePaint.setStrokeWidth(dp((mEnergyMode ? 5.5f : 4.5f) + pulse * 1.3f));
            canvas.drawPath(mRoundPath, mStrokePaint);
        }
        mStrokePaint.setColor(mLaunching ? 0x99F3F3F7 : 0x66FFFFFF);
        mStrokePaint.setStrokeWidth(dp(1.15f));
        canvas.drawPath(mRoundPath, mStrokePaint);

        drawParticles(canvas);
    }

    private void spawnParticles() {
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            double a = (Math.PI * 2.0 * i / PARTICLE_COUNT) + (Math.random() * 0.22);
            float speed = dp(1.2f + (float) Math.random() * 2.8f);
            mPX[i] = cx;
            mPY[i] = cy;
            mVX[i] = (float) Math.cos(a) * speed;
            mVY[i] = (float) Math.sin(a) * speed - dp(0.6f);
            mPLife[i] = 18 + (int) (Math.random() * 16);
        }
    }

    private void stepParticles() {
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            if (mPLife[i] <= 0) continue;
            mPLife[i]--;
            mPX[i] += mVX[i];
            mPY[i] += mVY[i];
            mVY[i] += dp(0.035f);
        }
    }

    private void drawParticles(Canvas canvas) {
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            int life = mPLife[i];
            if (life <= 0) continue;
            int alpha = Math.min(220, life * 9);
            mParticlePaint.setColor((alpha << 24) | (i % 3 == 0 ? 0x9B59E8 : 0xE4E4EA));
            canvas.drawCircle(mPX[i], mPY[i], dp(i % 4 == 0 ? 1.7f : 1.1f), mParticlePaint);
        }
    }

    private void clearParticles() {
        for (int i = 0; i < PARTICLE_COUNT; i++) mPLife[i] = 0;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
