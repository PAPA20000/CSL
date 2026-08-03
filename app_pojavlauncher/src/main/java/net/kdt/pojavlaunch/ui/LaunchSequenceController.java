package net.kdt.pojavlaunch.ui;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

/**
 * Phase 4 — premium game-launch sequence for ALREADY-INSTALLED profiles.
 *
 * <p>Choreographs the home screen through a deliberately non-download feel:</p>
 * <pre>
 *   PLAY pressed
 *    ├─ button morphs + energy beam            (PremiumPlayButtonView)
 *    ├─ profile card lifts, glow ring pulses
 *    ├─ background aura breathes
 *    ├─ game icon scales up
 *    ├─ particles + glow pulse                (PremiumPlayButtonView)
 *    ├─ "Launching…" status text fades in
 *    └─ dispatch LAUNCH_GAME → boot overlay takes over
 * </pre>
 *
 * <p>The sequence is view-animation based (hardware accelerated, cancel-safe)
 * and calls {@code onLaunch} roughly 650 ms after the tap — long enough to
 * read as a premium launch, short enough to feel instant.</p>
 */
public final class LaunchSequenceController {

    private LaunchSequenceController() { }

    /**
     * Runs the sequence and fires {@code onLaunch} at the hand-off moment.
     *
     * @param button    the PremiumPlayButtonView (already animated by caller)
     * @param card      profile card that should react (may be null)
     * @param background view that should breathe (may be null)
     * @param icon      game icon that scales (may be null)
     * @param status    "Launching…" label (may be null)
     * @param onLaunch  runnable that actually starts the game
     */
    public static void play(@Nullable PremiumPlayButtonView button,
                            @Nullable View card,
                            @Nullable View background,
                            @Nullable ImageView icon,
                            @Nullable TextView status,
                            Runnable onLaunch) {
        // 1) Profile card reaction: lift + glow ring + icon pulse.
        if (card != null) {
            card.animate().cancel();
            card.animate().scaleX(1.02f).scaleY(1.03f).translationY(-dp(card, 3))
                    .setDuration(120).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> card.animate().scaleX(1f).scaleY(1f).translationY(0)
                            .setDuration(220).setInterpolator(new DecelerateInterpolator())
                            .start())
                    .start();
            animateGlowRing(card);
        }

        // 2) Background aura breathes (subtle scale + brightness flicker).
        if (background != null && background.getVisibility() == View.VISIBLE) {
            background.animate().cancel();
            background.animate().scaleX(1.015f).scaleY(1.015f).alpha(0.92f)
                    .setDuration(650).setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> background.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(900).start())
                    .start();
        }

        // 3) Game icon scales up with a springy settle.
        if (icon != null) {
            icon.animate().cancel();
            icon.animate().scaleX(1.18f).scaleY(1.18f)
                    .setDuration(140).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> icon.animate().scaleX(1f).scaleY(1f)
                            .setDuration(260).setInterpolator(new AccelerateDecelerateInterpolator())
                            .start())
                    .start();
        }

        // 4) "Launching…" status text.
        if (status != null) {
            status.setVisibility(View.VISIBLE);
            status.setAlpha(0f);
            status.setTranslationY(dp(status, 6));
            status.animate().alpha(1f).translationY(0f)
                    .setStartDelay(180).setDuration(240).start();
        }

        // 5) Hand-off to the game.
        if (button != null) {
            button.postDelayed(() -> {
                if (onLaunch != null) onLaunch.run();
            }, 620);
        } else if (onLaunch != null) {
            onLaunch.run();
        }
    }

    /** Pulsing violet/amber glow ring around the profile card. */
    private static void animateGlowRing(View card) {
        View ring = card.getRootView() != null ? card.findViewById(R.id.launch_glow_ring) : null;
        if (ring == null) return;
        ring.setVisibility(View.VISIBLE);
        ring.setAlpha(0f);
        ring.animate().alpha(0.9f).setDuration(150).start();
        ValueAnimator pulse = ValueAnimator.ofFloat(1f, 1.15f, 1f);
        pulse.setDuration(700);
        pulse.setRepeatCount(2);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.addUpdateListener(a -> {
            float v = (Float) a.getAnimatedValue();
            ring.setScaleX(v);
            ring.setScaleY(v);
        });
        pulse.start();
        ring.animate().alpha(0f).setStartDelay(1600).setDuration(400)
                .withEndAction(() -> {
                    ring.setVisibility(View.GONE);
                    ring.setScaleX(1f);
                    ring.setScaleY(1f);
                }).start();
    }

    private static float dp(View v, float value) {
        return value * v.getResources().getDisplayMetrics().density;
    }
}
