package net.kdt.pojavlaunch.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.utils.animation.MotionSpeed;

/**
 * About page — premium animated page with the launcher story, credits to
 * PojavLauncher ("Puja") and Amethyst ("Amit"), community links and the
 * GPL v3 legal notice.
 */
public class AboutFragment extends Fragment {

    public static final String TAG = "AboutFragment";

    private static final String URL_DISCORD = "https://discord.gg/qcu5Hb5Xe";
    private static final String URL_WEBSITE = "https://cs-launcher.netlify.app/";
    private static final String URL_GITHUB = "https://github.com/craftstudioteam";

    public AboutFragment() {
        super(R.layout.fragment_about);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Version chip
        TextView versionChip = view.findViewById(R.id.about_version_chip);
        if (versionChip != null) {
            String v = "Version " + BuildConfig.VERSION_NAME;
            versionChip.setText(v);
        }

        // Back
        View back = view.findViewById(R.id.about_back_button);
        if (back != null) {
            UiMotion.pressFeedback(back);
            back.setOnClickListener(v -> navigateBack());
        }
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() { navigateBack(); }
                });

        // Links
        wireLink(view, R.id.about_link_discord, URL_DISCORD);
        wireLink(view, R.id.about_link_website, URL_WEBSITE);
        wireLink(view, R.id.about_link_github, URL_GITHUB);

        // ── Entrance choreography (best & fast; no-op when animations Off) ──
        if (MotionSpeed.isEnabled()) {
            view.post(() -> {
                if (!isAdded() || isRemoving()) return;
                // Hero pops in with a jelly overshoot on the logo
                View logo = view.findViewById(R.id.about_cs_logo);
                if (logo != null) UiMotion.popIn(logo);

                // Cards cascade: credits → links → legal
                cascade(view.findViewById(R.id.about_credits_heading), 120);
                cascade(view.findViewById(R.id.about_pojav_card), 200);
                cascade(view.findViewById(R.id.about_amethyst_card), 280);
                cascade(view.findViewById(R.id.about_links_heading), 360);
                cascade(view.findViewById(R.id.about_link_discord), 420);
                cascade(view.findViewById(R.id.about_link_website), 470);
                cascade(view.findViewById(R.id.about_link_github), 520);
                cascade(view.findViewById(R.id.about_legal_heading), 580);
                cascade(view.findViewById(R.id.about_legal_card), 640);
            });
        }
    }

    private void cascade(@Nullable View v, long delayMs) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(18f * getResources().getDisplayMetrics().density);
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(MotionSpeed.scale(delayMs))
                .setDuration(MotionSpeed.scale(300L))
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .start();
    }

    private void wireLink(@NonNull View root, int id, final String url) {
        View row = root.findViewById(id);
        if (row == null) return;
        UiMotion.pressFeedback(row);
        row.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable t) {
                Tools.showError(requireContext(), t);
            }
        });
    }

    private void navigateBack() {
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).refreshHomeState();
        } else if (parent != null) {
            parent.getChildFragmentManager().popBackStackImmediate();
        } else {
            Tools.removeCurrentFragment(requireActivity());
        }
    }
}
