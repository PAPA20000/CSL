package net.kdt.pojavlaunch.fragments;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.ui.PremiumPlayButtonView;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;

public class FastClientHomeFragment extends Fragment {

    public static final String TAG = "FastClientHomeFragment";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home_fastclient, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 1. Bind Account Data (Player Name & Head)
        bindAccountData(view);

        // 2. Bind Profile Data (Name, Icon, Version, Chips)
        bindProfileData(view);

        // 3. Play Button Functionality — premium Phase-3 launch morph
        PremiumPlayButtonView btnPlay = view.findViewById(R.id.btn_play_main);
        btnPlay.setOnClickListener(v -> {
            btnPlay.beginLaunch();
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
        });

        // 4. Profile Selection & Settings
        View btnSelectProfile = view.findViewById(R.id.btn_select_profile);
        if (btnSelectProfile != null) {
            btnSelectProfile.setOnClickListener(v -> {
                Tools.swapFragment(requireActivity(), InstancePickerFragment.class, InstancePickerFragment.TAG, null);
            });
        }

        View btnProfileSetting = view.findViewById(R.id.btn_profile_setting);
        if (btnProfileSetting != null) {
            btnProfileSetting.setOnClickListener(v -> {
                Tools.swapFragment(requireActivity(), ProfileEditorFragment.class, ProfileEditorFragment.TAG, null);
            });
        }

        // Server cards click listeners
        setupServerCards(view);

        // Infrawire Official Partner cards (home card below Play + feed card in right pane)
        setupInfrawireCard(view.findViewById(R.id.infrawire_card_play), 350);
        setupInfrawireCard(view.findViewById(R.id.infrawire_card_feed), 450);

        // Global sponsorship gate (Firebase admin panel): when disabled,
        // every sponsor card disappears automatically.
        net.kdt.pojavlaunch.remote.FirebaseSyncManager.gateSponsorView(
                view.findViewById(R.id.infrawire_card_play));
        net.kdt.pojavlaunch.remote.FirebaseSyncManager.gateSponsorView(
                view.findViewById(R.id.infrawire_card_feed));

        // Home entrance choreography (user req: nice home animation) —
        // deferred one frame so the window token is ready; internally
        // no-ops when animations are turned Off.
        view.post(() -> {
            if (isAdded() && !isRemoving()) {
                net.kdt.pojavlaunch.UiMotion.revealScreen(view);
            }
        });
    }

    /**
     * Wire one Infrawire sponsor card: card tap opens the Official Partners page,
     * Deploy VPS / Learn More open the device browser directly. Non-intrusive by design.
     */
    private void setupInfrawireCard(@Nullable View card, long fadeDelayMs) {
        if (card == null) return;

        net.kdt.pojavlaunch.sponsor.InfrawirePartner.applyPressAnimation(card);
        card.setOnClickListener(v ->
                net.kdt.pojavlaunch.sponsor.InfrawirePartner.openPartnerPage(requireActivity()));

        View deploy = card.findViewById(R.id.infrawire_btn_deploy);
        if (deploy != null) {
            net.kdt.pojavlaunch.sponsor.InfrawirePartner.applyPressAnimation(deploy);
            deploy.setOnClickListener(v -> net.kdt.pojavlaunch.sponsor.InfrawirePartner
                    .openLink(requireContext(), net.kdt.pojavlaunch.sponsor.InfrawirePartner.URL_VPS));
        }

        View learnMore = card.findViewById(R.id.infrawire_btn_learn_more);
        if (learnMore != null) {
            net.kdt.pojavlaunch.sponsor.InfrawirePartner.applyPressAnimation(learnMore);
            learnMore.setOnClickListener(v -> net.kdt.pojavlaunch.sponsor.InfrawirePartner
                    .openLink(requireContext(), net.kdt.pojavlaunch.sponsor.InfrawirePartner.URL_WEBSITE));
        }

        net.kdt.pojavlaunch.sponsor.InfrawirePartner.fadeIn(card, fadeDelayMs);
    }

    private void bindAccountData(View view) {
        MinecraftAccount account = PojavProfile.getCurrentProfileContent(requireContext(), null);
        TextView tvPlayerName = view.findViewById(R.id.tv_player_name);
        ImageView ivPlayerHead = view.findViewById(R.id.iv_player_head);
        TextView tvPlayerStatus = view.findViewById(R.id.tv_player_status);

        if (account != null) {
            tvPlayerName.setText(account.username);
            Bitmap head = account.getSkinFace();
            if (head != null) {
                ivPlayerHead.setImageBitmap(head);
            } else {
                ivPlayerHead.setImageResource(R.drawable.ic_cs_logo_placeholder);
            }
            
            boolean isOnline = account.accessToken != null && !account.accessToken.equals("0");
            tvPlayerStatus.setText(isOnline ? "Online" : "Offline");
            tvPlayerStatus.setBackgroundResource(isOnline ? R.drawable.bg_badge_online : R.drawable.bg_chip_dark);
            tvPlayerStatus.setTextColor(isOnline ? 0xFF7FA98C : 0xFFAAAAAA);
        }
    }

    private void bindProfileData(View view) {
        String profileKey = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
        LauncherProfiles.load();
        MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(profileKey);

        TextView tvProfileName = view.findViewById(R.id.tv_profile_name);
        TextView tvFolderName = view.findViewById(R.id.tv_folder_name);
        TextView chipVersion = view.findViewById(R.id.chip_version);
        TextView chipLoader = view.findViewById(R.id.chip_loader);
        TextView chipJava = view.findViewById(R.id.chip_java);
        TextView chipRam = view.findViewById(R.id.chip_ram);
        ImageView ivProfileIcon = view.findViewById(R.id.iv_player_head); // Reusing player head frame for modpack icon if preferred, or I should use a separate one? User said "replace or dynamically load"

        if (profile != null) {
            tvProfileName.setText(profile.name != null ? profile.name : "Default");
            tvFolderName.setText(profile.name != null ? profile.name : "Default");
            chipVersion.setText(profile.lastVersionId != null ? profile.lastVersionId : "Unknown");
            
            // Loader detection
            String loader = "Vanilla";
            if (profile.isOptiFine()) loader = "OptiFine";
            else if (profile.lastVersionId != null) {
                String vid = profile.lastVersionId.toLowerCase();
                if (vid.contains("fabric")) loader = "Fabric";
                else if (vid.contains("forge")) loader = "Forge";
                else if (vid.contains("quilt")) loader = "Quilt";
                else if (vid.contains("neoforge")) loader = "NeoForge";
            }
            chipLoader.setText(loader);

            // Java info
            String javaVer = "Java 8"; // Default fallback
            if (profile.javaDir != null) {
                if (profile.javaDir.contains("17")) javaVer = "Java 17";
                else if (profile.javaDir.contains("21")) javaVer = "Java 21";
            }
            chipJava.setText(javaVer);

            // RAM info
            int ramMb = LauncherPreferences.PREF_RAM_ALLOCATION;
            chipRam.setText(String.format("%.1fGB", ramMb / 1024.0));

            // Load profile icon if it's not the default one
            if (profile.icon != null && !profile.icon.equals("default")) {
                Drawable icon = ProfileIconCache.fetchIcon(getResources(), profileKey, profile.icon);
                ivProfileIcon.setImageDrawable(icon);
            }
        }
    }

    private void setupServerCards(View view) {
        int[] serverCardIds = {
            R.id.card_server_1, R.id.card_server_2, R.id.card_server_3, R.id.card_server_4, R.id.card_server_5
        };
        String[] serverAddresses = {
            "bananasmp.net", "fast.ascendiamc.com", "play.happymc.fun", "insanesmp.net", "fast.eternalnetwork.club"
        };

        for (int i = 0; i < serverCardIds.length; i++) {
            final String address = serverAddresses[i];
            View card = view.findViewById(serverCardIds[i]);
            if (card != null) {
                card.setOnClickListener(v -> {
                    Toast.makeText(getContext(), "Joining " + address + "...", Toast.LENGTH_SHORT).show();
                    // Implement actual quick join logic if available in Tools
                });
            }
        }
    }

    private File getCurrentProfileDirectory() {
        String currentProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if (!Tools.isValidString(currentProfile)) return new File(Tools.DIR_GAME_NEW);
        LauncherProfiles.load();
        MinecraftProfile profileObject = LauncherProfiles.mainProfileJson.profiles.get(currentProfile);
        if (profileObject == null) return new File(Tools.DIR_GAME_NEW);
        return Tools.getGameDirPath(profileObject);
    }

    private boolean hasOnlineProfile() {
        return Tools.hasOnlineProfile();
    }
}
