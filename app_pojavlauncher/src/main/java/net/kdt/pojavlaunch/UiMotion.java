package net.kdt.pojavlaunch;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;

import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.utils.animation.JellyBounceInterpolator;
import net.kdt.pojavlaunch.utils.animation.MotionSpeed;

/**
 * Small, dependency-free motion system used by launcher screens.
 *
 * It deliberately uses view-property animations: they are hardware accelerated on
 * API 21+, cancel safely when a fragment is replaced, and do not keep a reference
 * to an Activity. Motion is short enough to feel responsive on low-end devices.
 *
 * All durations run through {@link MotionSpeed} so the global "Animation Speed"
 * setting (launcher_animate_speed, A9) retimes every surface from one place.
 */
public final class UiMotion {
    private static final long ENTER_DURATION = 300L;
    private static final TimeInterpolator ENTER = new DecelerateInterpolator(1.7f);
    private static final TimeInterpolator PRESS_OUT = new OvershootInterpolator(1.6f);
    private static final JellyBounceInterpolator JELLY = new JellyBounceInterpolator();

    private UiMotion() { }

    /** Gives every newly created screen a consistent, subtle entrance. */
    public static void revealScreen(View root) {
        if (root == null || root.getWindowToken() == null) return;
        root.animate().cancel();
        root.setAlpha(0f);
        root.setTranslationY(dp(root, 14));
        root.setScaleX(0.985f);
        root.setScaleY(0.985f);
        root.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(MotionSpeed.scale(ENTER_DURATION))
                .setInterpolator(ENTER)
                .withEndAction(() -> {
                    root.setAlpha(1f);
                    root.setTranslationY(0f);
                    root.setScaleX(1f);
                    root.setScaleY(1f);
                })
                .start();
        cascadeChildren(root);
    }

    /**
     * Reveals the first visual layer of a page in a short cascade. Limiting this
     * to direct children keeps RecyclerView, text input and game-control internals
     * untouched while still making every page feel intentionally composed.
     */
    private static void cascadeChildren(View root) {
        if (!(root instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        int animated = 0;
        for (int i = 0; i < group.getChildCount() && animated < 8; i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0 || child.getHeight() == 0) continue;
            final float originalTranslationY = child.getTranslationY();
            final float originalScaleX = child.getScaleX();
            final float originalScaleY = child.getScaleY();
            child.animate().cancel();
            child.setAlpha(0f);
            child.setTranslationY(originalTranslationY + dp(child, 10));
            child.setScaleX(originalScaleX * 0.99f);
            child.setScaleY(originalScaleY * 0.99f);
            child.animate()
                    .alpha(1f)
                    .translationY(originalTranslationY)
                    .scaleX(originalScaleX)
                    .scaleY(originalScaleY)
                    .setStartDelay(MotionSpeed.scale(animated * 38L))
                    .setDuration(MotionSpeed.scale(260L))
                    .setInterpolator(ENTER)
                    .start();
            animated++;
        }
    }

    /** Animates app chrome (header/account/settings) after its layout is attached. */
    public static void revealChrome(View chrome) {
        if (chrome == null) return;
        chrome.post(() -> {
            if (chrome.getWindowToken() == null) return;
            chrome.setAlpha(0f);
            chrome.setTranslationY(-dp(chrome, 12));
            chrome.animate().alpha(1f).translationY(0f)
                    .setDuration(MotionSpeed.scale(280L)).setInterpolator(ENTER).start();
        });
    }

    /**
     * Tactile press feedback for touch targets: quick scale-down on touch down,
     * springy settle on release. Returns false from the listener so the view's
     * own click handling keeps working.
     */
    public static void pressFeedback(View... views) {
        for (View view : views) {
            if (view == null) continue;
            view.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().cancel();
                        v.animate().scaleX(0.92f).scaleY(0.92f)
                                .setDuration(70L).setInterpolator(ENTER).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f)
                                .setDuration(150L).setInterpolator(PRESS_OUT).start();
                        break;
                }
                return false;
            });
        }
    }

    /** Soft drop-in for a single element (dialog content, chips, banners...). */
    public static void fadeInDown(View view, long startDelay) {
        if (view == null || view.getWindowToken() == null) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(-dp(view, 10));
        view.animate().alpha(1f).translationY(0f)
                .setStartDelay(MotionSpeed.scale(startDelay))
                .setDuration(MotionSpeed.scale(280L))
                .setInterpolator(ENTER)
                .start();
    }

    /**
     * Success/check "pop" (A3) — jelly-bounce overshoot on scale, used for
     * moments like a runtime install completing or a check badge appearing.
     * Cancel-safe: callers may pass views that get recycled.
     */
    public static void popIn(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setScaleX(0.3f);
        view.setScaleY(0.3f);
        view.setAlpha(1f);
        view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(MotionSpeed.scale(520L))
                .setInterpolator(JELLY)
                .start();
    }

    /**
     * Skeleton/shimmer pulse (A7) — infinite alpha pulse 0.3 ↔ 0.6 on a
     * 1000 ms linear loop, reversed. Start it on loading placeholders
     * (runtime download deck, download cards); {@link #stopPulse} on detach.
     */
    public static ValueAnimator pulseSkeleton(View view) {
        if (view == null) return null;
        ValueAnimator animator = ValueAnimator.ofFloat(0.3f, 0.6f);
        animator.setDuration(MotionSpeed.scale(1000L));
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.addUpdateListener(a -> view.setAlpha((Float) a.getAnimatedValue()));
        animator.start();
        return animator;
    }

    public static void stopPulse(ValueAnimator animator) {
        if (animator != null) animator.cancel();
    }

    /** Staggered child entrance for RecyclerViews (layout animation). */
    public static void revealList(RecyclerView list) {
        if (list == null || list.getContext() == null) return;
        LayoutAnimationController controller = AnimationUtils.loadLayoutAnimation(
                list.getContext(), R.anim.list_item_enter);
        list.setLayoutAnimation(controller);
        list.scheduleLayoutAnimation();
    }

    private static float dp(View view, float value) {
        return value * view.getResources().getDisplayMetrics().density;
    }
}
