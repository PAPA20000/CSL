package net.kdt.pojavlaunch;

import android.animation.TimeInterpolator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Small, dependency-free motion system used by launcher screens.
 *
 * It deliberately uses view-property animations: they are hardware accelerated on
 * API 21+, cancel safely when a fragment is replaced, and do not keep a reference
 * to an Activity. Motion is short enough to feel responsive on low-end devices.
 */
public final class UiMotion {
    private static final long ENTER_DURATION = 300L;
    private static final TimeInterpolator ENTER = new DecelerateInterpolator(1.7f);

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
                .setDuration(ENTER_DURATION)
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
                    .setStartDelay(animated * 38L)
                    .setDuration(260L)
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
                    .setDuration(280L).setInterpolator(ENTER).start();
        });
    }

    private static float dp(View view, float value) {
        return value * view.getResources().getDisplayMetrics().density;
    }
}
