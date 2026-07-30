package net.kdt.pojavlaunch.multirt;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import net.kdt.pojavlaunch.NewJREUtil;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * First-launch runtime wizard (Phase-6, Req: onboarding wizard +
 * distinct runtime download design language).
 *
 * Shows Java 8 / 17 / 21 / 25 as selectable cards with live install
 * states, recommended picks based on which Minecraft generations need
 * which JVM, and a sequential amber-accented install sequence
 * (queued → downloading → ready / failed-with-retry).
 */
public class RuntimeWizardDialog {

    private static final String PREF_SHOWN = "runtimeWizardShown";

    // major → size estimate (MB) shown on the neutral chip
    private static final int[] MAJORS = {8, 17, 21, 25};
    private static final int[] CARD_IDS = {
            R.id.rw_card_8, R.id.rw_card_17, R.id.rw_card_21, R.id.rw_card_25};
    private static final int[] ROLE_RES = {
            R.string.rw_java8_role, R.string.rw_java17_role,
            R.string.rw_java21_role, R.string.rw_java25_role};
    private static final int[] SIZE_ESTIMATE_MB = {45, 55, 60, 65};

    private static class CardState {
        int major;
        View root;
        TextView chip;
        ImageView check;
        View spinner;
        boolean installed;
        boolean selectable;
        boolean selected;
        boolean failed;
        NewJREUtil.ExternalRuntime runtime;
    }

    public static boolean wasShown() {
        return LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_SHOWN, false);
    }

    public static void show(Activity activity, Runnable onDismiss) {
        LauncherPreferences.DEFAULT_PREF.edit().putBoolean(PREF_SHOWN, true).apply();

        View dialogView = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_runtime_wizard, null);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView statusStrip = dialogView.findViewById(R.id.rw_status_strip);
        Button skipButton = dialogView.findViewById(R.id.rw_btn_skip);
        Button actionButton = dialogView.findViewById(R.id.rw_btn_action);

        List<NewJREUtil.ExternalRuntime> downloadable = MultiRTUtils.getRuntimesToDownload();
        List<CardState> cards = new ArrayList<>();
        final boolean[] dismissHandled = {false};
        Runnable finishOnce = () -> {
            if (dismissHandled[0]) return;
            dismissHandled[0] = true;
            if (onDismiss != null) onDismiss.run();
        };

        for (int i = 0; i < MAJORS.length; i++) {
            int major = MAJORS[i];
            View cardRoot = dialogView.findViewById(CARD_IDS[i]);
            CardState st = new CardState();
            st.major = major;
            st.root = cardRoot;
            st.chip = cardRoot.findViewById(R.id.runtime_chip);
            st.check = cardRoot.findViewById(R.id.runtime_check);
            st.spinner = cardRoot.findViewById(R.id.runtime_spinner);

            ((TextView) cardRoot.findViewById(R.id.runtime_major_text))
                    .setText(String.valueOf(major));
            ((TextView) cardRoot.findViewById(R.id.runtime_name_text))
                    .setText("Java " + major);
            ((TextView) cardRoot.findViewById(R.id.runtime_role_text))
                    .setText(ROLE_RES[i]);

            st.installed = MultiRTUtils.getExactJreName(major) != null;
            for (NewJREUtil.ExternalRuntime rt : downloadable) {
                if (rt.majorVersion == major) { st.runtime = rt; break; }
            }
            st.selectable = st.runtime != null && !st.installed;

            if (st.installed) {
                showChip(st, activity.getString(R.string.rw_installed), true);
                applyCheck(st, true, false);
                st.root.setAlpha(0.62f);
            } else if (st.selectable) {
                // Recommended: Java 17 (broadest coverage) + Java 21 (current gen)
                boolean recommended = major == 17 || major == 21;
                st.selected = recommended;
                if (recommended) {
                    showChip(st, activity.getString(R.string.rw_recommended), false);
                } else {
                    showChip(st, activity.getString(R.string.rw_size_estimate,
                            SIZE_ESTIMATE_MB[i]), false);
                }
                applySelectionVisual(st);
                final CardState fst = st;
                st.root.setOnClickListener(v -> {
                    fst.selected = !fst.selected;
                    applySelectionVisual(fst);
                    updateActionText(actionButton, activity, cards);
                });
            } else {
                // Not installable on this device (e.g. x86 with 21+)
                cardRoot.setVisibility(View.GONE);
            }
            cards.add(st);
        }

        updateActionText(actionButton, activity, cards);

        skipButton.setOnClickListener(v -> {
            dialog.dismiss();
            finishOnce.run();
        });

        actionButton.setOnClickListener(v -> {
            List<CardState> queue = new ArrayList<>();
            for (CardState c : cards) {
                if (c.selectable && c.selected && !c.installed) queue.add(c);
            }
            if (queue.isEmpty()) { dialog.dismiss(); finishOnce.run(); return; }

            // Lock the UI into "sequence" mode
            skipButton.setVisibility(View.GONE);
            actionButton.setEnabled(false);
            actionButton.setText(R.string.rw_installing_cta);
            for (CardState c : cards) {
                c.root.setOnClickListener(null);
                c.root.setClickable(false);
                if (queue.contains(c)) {
                    c.root.setAlpha(0.75f);
                    showChip(c, activity.getString(R.string.rw_queued), false);
                } else if (c.selectable) {
                    c.root.setAlpha(0.35f);
                }
            }
            statusStrip.setVisibility(View.VISIBLE);
            statusStrip.setText(activity.getString(
                    R.string.rw_progress_status, 0, queue.size()));
            statusStrip.setAlpha(0f);
            statusStrip.animate().alpha(1f).setDuration(260).start();

            PojavApplication.sExecutorService.execute(() -> {
                int[] done = {0};
                for (CardState c : queue) {
                    runCardUi(activity, c, () -> {
                        c.root.setAlpha(1f);
                        c.spinner.setVisibility(View.VISIBLE);
                        c.root.setBackgroundResource(R.drawable.bg_runtime_card_selected);
                        showChip(c, activity.getString(R.string.rw_downloading), false);
                        c.check.setVisibility(View.GONE);
                    });
                    boolean ok = true;
                    try {
                        c.runtime.downloadRuntime(activity);
                    } catch (RuntimeException e) {
                        ok = false;
                        Tools.showErrorRemote(e);
                    }
                    final boolean success = ok;
                    done[0]++;
                    final int doneCount = done[0];
                    int total = queue.size();
                    runCardUi(activity, c, () -> {
                        c.spinner.setVisibility(View.GONE);
                        if (success) {
                            c.installed = true;
                            showChip(c, activity.getString(R.string.rw_ready), true);
                            applyCheck(c, true, true);
                        } else {
                            c.failed = true;
                            showChip(c, activity.getString(R.string.rw_failed), false);
                            c.chip.setTextColor(0xFFE5A0A6);
                            c.chip.setBackgroundResource(R.drawable.bg_runtime_chip_installed);
                            c.root.setBackgroundResource(R.drawable.bg_runtime_card);
                            c.root.setClickable(true);
                            c.root.setOnClickListener(v2 -> {
                                // retry a failed card individually
                                c.failed = false;
                                PojavApplication.sExecutorService.execute(() -> {
                                    runCardUi(activity, c, () -> {
                                        c.spinner.setVisibility(View.VISIBLE);
                                        showChip(c, activity.getString(R.string.rw_downloading), false);
                                    });
                                    boolean ok2 = true;
                                    try {
                                        c.runtime.downloadRuntime(activity);
                                    } catch (RuntimeException e) {
                                        ok2 = false;
                                        Tools.showErrorRemote(e);
                                    }
                                    final boolean success2 = ok2;
                                    runCardUi(activity, c, () -> {
                                        c.spinner.setVisibility(View.GONE);
                                        if (success2) {
                                            c.installed = true;
                                            showChip(c, activity.getString(R.string.rw_ready), true);
                                            applyCheck(c, true, true);
                                            maybeFinish(activity, cards, statusStrip,
                                                    actionButton, dialog, finishOnce);
                                        } else {
                                            c.failed = true;
                                            showChip(c, activity.getString(R.string.rw_failed), false);
                                            c.chip.setTextColor(0xFFE5A0A6);
                                        }
                                    });
                                });
                            });
                        }
                        statusStrip.setText(activity.getString(
                                R.string.rw_progress_status, doneCount, total));
                    });
                }
                activity.runOnUiThread(() ->
                        maybeFinish(activity, cards, statusStrip,
                                actionButton, dialog, finishOnce));
            });
        });

        dialog.setOnCancelListener(d -> finishOnce.run());
        dialog.show();

        // Entrance: sheet rises softly
        dialogView.setAlpha(0f);
        dialogView.setTranslationY(activity.getResources().getDisplayMetrics().density * 42);
        dialogView.animate().alpha(1f).translationY(0f)
                .setDuration(340)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .start();
    }

    /** After any state change: if every selectable runtime is installed, offer Finish. */
    private static void maybeFinish(Activity activity, List<CardState> cards,
                                    TextView statusStrip, Button actionButton,
                                    AlertDialog dialog, Runnable finishOnce) {
        boolean allGood = true;
        for (CardState c : cards) {
            if (c.selectable && c.selected && !c.installed) allGood = false;
        }
        if (allGood) {
            statusStrip.setText(R.string.rw_all_ready);
            statusStrip.setTextColor(0xFF9FD6AC);
            actionButton.setEnabled(true);
            actionButton.setText(R.string.rw_finish);
            actionButton.setOnClickListener(v -> {
                dialog.dismiss();
                finishOnce.run();
            });
        } else {
            statusStrip.setText(R.string.rw_some_failed);
            statusStrip.setTextColor(0xFFE5A0A6);
            actionButton.setEnabled(true);
            actionButton.setText(R.string.rw_finish_anyway);
            actionButton.setOnClickListener(v -> {
                dialog.dismiss();
                finishOnce.run();
            });
        }
    }

    private static void updateActionText(Button actionButton, Activity activity,
                                         List<CardState> cards) {
        int count = 0;
        for (CardState c : cards) if (c.selectable && c.selected && !c.installed) count++;
        if (count == 0) {
            boolean anyInstalled = false;
            for (CardState c : cards) if (c.installed) anyInstalled = true;
            actionButton.setText(anyInstalled
                    ? R.string.rw_finish
                    : R.string.rw_download_cta_none);
        } else {
            actionButton.setText(activity.getString(R.string.rw_download_cta, count));
        }
    }

    private static void applySelectionVisual(CardState st) {
        st.root.setBackgroundResource(st.selected
                ? R.drawable.bg_runtime_card_selected
                : R.drawable.bg_runtime_card);
        applyCheck(st, st.selected, false);
        st.root.animate().cancel();
        st.root.setScaleX(st.selected ? 0.985f : 1f);
        st.root.setScaleY(st.selected ? 0.985f : 1f);
        st.root.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
    }

    private static void applyCheck(CardState st, boolean visible, boolean amberReady) {
        if (!visible) {
            st.check.setVisibility(View.GONE);
            return;
        }
        st.check.setBackgroundResource(amberReady
                ? R.drawable.bg_check_circle_amber
                : R.drawable.bg_check_circle);
        st.check.setVisibility(View.VISIBLE);
        if (amberReady) {
            // Completion pop
            st.check.setScaleX(0f);
            st.check.setScaleY(0f);
            st.check.animate().scaleX(1f).scaleY(1f).setDuration(280)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2f))
                    .start();
        }
    }

    private static void showChip(CardState st, String text, boolean installedStyle) {
        st.chip.setText(text);
        st.chip.setBackgroundResource(installedStyle
                ? R.drawable.bg_runtime_chip_installed
                : R.drawable.bg_runtime_chip_recommended);
        st.chip.setTextColor(installedStyle ? 0xFF9FD6AC : 0xFFD8C79A);
        st.chip.setVisibility(View.VISIBLE);
    }

    private static void runCardUi(Activity activity, CardState c, Runnable r) {
        if (!activity.isFinishing() && !activity.isDestroyed()) {
            activity.runOnUiThread(r);
        }
    }
}
