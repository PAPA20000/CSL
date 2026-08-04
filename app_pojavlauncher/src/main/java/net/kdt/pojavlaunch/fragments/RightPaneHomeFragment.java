package net.kdt.pojavlaunch.fragments;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class RightPaneHomeFragment extends Fragment {

    public static final String TAG = "RightPaneHomeFragment";
    public static final String CUSTOM_BG_PATH = Tools.DIR_DATA + "/custom_launcher_bg";

    private RecyclerView mRecyclerView;
    private HomeProfileAdapter mAdapter;

    public RightPaneHomeFragment() {
        super(R.layout.fragment_right_pane_home);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        loadCustomWallpaper(view);

        // (Req-11: the big "Profiles" header bar is gone — cards own the screen.)

        mRecyclerView = view.findViewById(R.id.rv_home_profiles);
        mRecyclerView.setLayoutManager(new GridLayoutManager(getContext(), 2));
        setupProfileAdapter();

        // Floating "+" FAB opens the Version Setup Hub (3-category grid)
        View fab = view.findViewById(R.id.fab_create_profile);
        if (fab != null) {
            fab.setScaleX(0.6f);
            fab.setScaleY(0.6f);
            fab.setAlpha(0f);
            fab.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            fab.animate()
                    .scaleX(1f).scaleY(1f)
                    .alpha(1f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> fab.setLayerType(View.LAYER_TYPE_NONE, null))
                    .start();

            fab.setOnClickListener(v -> {
                if (!isAdded() || getContext() == null) return;
                Fragment parent = getParentFragment();
                if (parent instanceof MainMenuFragment) {
                    ((MainMenuFragment) parent).openChildPane(
                            ProfileTypeSelectFragment.class,
                            ProfileTypeSelectFragment.TAG, null);
                } else if (getActivity() != null) {
                    Tools.swapFragment(getActivity(),
                            ProfileTypeSelectFragment.class,
                            ProfileTypeSelectFragment.TAG, null);
                }
            });
        }

        View refreshBtn = view.findViewById(R.id.btn_refresh_profiles);
        if (refreshBtn != null) {
            refreshBtn.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            refreshBtn.setAlpha(0f);
            refreshBtn.setScaleX(0.6f);
            refreshBtn.setScaleY(0.6f);
            refreshBtn.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(220).setStartDelay(160)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> refreshBtn.setLayerType(View.LAYER_TYPE_NONE, null))
                    .start();
            refreshBtn.setOnClickListener(v -> {
                v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
                        .withEndAction(() -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                            v.setLayerType(View.LAYER_TYPE_NONE, null);
                        }).start();
                setupProfileAdapter();
                Toast.makeText(getContext(), "Profiles refreshed", Toast.LENGTH_SHORT).show();
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        setupProfileAdapter();
    }

    public void reloadBackground() {
        View v = getView();
        if (v != null) loadCustomWallpaper(v);
    }

    private void setupProfileAdapter() {
        LauncherProfiles.loadAsync(() -> {
            if (!isAdded() || getContext() == null) return;

            Map<String, MinecraftProfile> profilesMap = LauncherProfiles.mainProfileJson != null
                    ? LauncherProfiles.mainProfileJson.profiles : null;

            List<String> keys = new ArrayList<>();
            List<MinecraftProfile> profiles = new ArrayList<>();

            if (profilesMap != null && !profilesMap.isEmpty()) {
                List<Map.Entry<String, MinecraftProfile>> entries =
                        new ArrayList<>(profilesMap.entrySet());
                Collections.sort(entries, (a, b) -> {
                    String ua = a.getValue().lastUsed != null ? a.getValue().lastUsed : "";
                    String ub = b.getValue().lastUsed != null ? b.getValue().lastUsed : "";
                    return ub.compareTo(ua);
                });
                for (Map.Entry<String, MinecraftProfile> entry : entries) {
                    String key = entry.getKey();
                    MinecraftProfile profile = entry.getValue();
                    if (key == null || key.isEmpty()) continue;
                    if (profile == null) continue;
                    if (profile.name == null || profile.name.trim().isEmpty()) continue;
                    keys.add(key);
                    profiles.add(profile);
                }
            }

            mAdapter = new HomeProfileAdapter(keys, profiles,
                    new HomeProfileAdapter.OnProfileActionListener() {
                @Override
                public void onProfilePlay(String profileKey, MinecraftProfile profile) {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                            .apply();
                    ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                }

                @Override
                public void onProfileBrowse(String profileKey, MinecraftProfile profile) {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                            .apply();
                    Bundle args = new Bundle();
                    args.putString(ManageModsFragment.BUNDLE_PROFILE_KEY, profileKey);
                    Fragment parent = getParentFragment();
                    if (parent instanceof MainMenuFragment) {
                        ((MainMenuFragment) parent).openChildPane(
                                ModsSearchFragment.class, ModsSearchFragment.TAG, args);
                    } else if (getActivity() != null) {
                        Tools.swapFragment(requireActivity(),
                                ModsSearchFragment.class, ModsSearchFragment.TAG, args);
                    }
                }

                @Override
                public void onProfileEdit(String profileKey, MinecraftProfile profile) {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                            .apply();
                    Tools.swapFragment(requireActivity(),
                            ProfileEditorFragment.class, ProfileEditorFragment.TAG, null);
                }

                @Override
                public void onProfileAddShortcut(String profileKey, MinecraftProfile profile) {
                    Bundle args = new Bundle();
                    args.putString(
                            net.kdt.pojavlaunch.shortcuts.ShortcutIconPickerFragment.ARG_PROFILE_KEY,
                            profileKey);
                    Fragment parent = getParentFragment();
                    if (parent instanceof MainMenuFragment) {
                        ((MainMenuFragment) parent).openChildPane(
                                net.kdt.pojavlaunch.shortcuts.ShortcutIconPickerFragment.class,
                                net.kdt.pojavlaunch.shortcuts.ShortcutIconPickerFragment.TAG,
                                args);
                    } else if (getActivity() != null) {
                        Tools.swapFragment(requireActivity(),
                                net.kdt.pojavlaunch.shortcuts.ShortcutIconPickerFragment.class,
                                net.kdt.pojavlaunch.shortcuts.ShortcutIconPickerFragment.TAG,
                                args);
                    }
                }
            });

            mRecyclerView.setAdapter(mAdapter);
        });
    }

    private void loadCustomWallpaper(@NonNull View view) {
        ImageView wallpaper = view.findViewById(R.id.right_pane_wallpaper);
        File bgFile = new File(CUSTOM_BG_PATH);
        if (bgFile.exists()) {
            // Animated GIF wallpapers decode on a worker thread, then loop smoothly
            final String path = bgFile.getAbsolutePath();
            net.kdt.pojavlaunch.PojavApplication.sExecutorService.execute(() -> {
                Drawable d;
                try {
                    byte[] head = new byte[6];
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(path)) {
                        //noinspection ResultOfMethodCallIgnored
                        fis.read(head);
                    }
                    if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F') {
                        d = new pl.droidsonroids.gif.GifDrawable(path);
                    } else {
                        d = Drawable.createFromPath(path);
                    }
                } catch (Throwable t) {
                    d = Drawable.createFromPath(path);
                }
                final Drawable finalDrawable = d;
                if (getActivity() != null) {
                    Tools.runOnUiThread(() -> applyWallpaper(wallpaper, finalDrawable));
                }
            });
            return;
        }
        if (wallpaper.getVisibility() == View.VISIBLE) {
            wallpaper.animate().alpha(0f).setDuration(300)
                    .withEndAction(() -> wallpaper.setVisibility(View.GONE)).start();
        }
    }

    private void applyWallpaper(@NonNull ImageView wallpaper, @Nullable Drawable d) {
        if (d == null) return;
        net.kdt.pojavlaunch.profiles.ProfileGifSupport.stopDrawable(wallpaper.getDrawable());
        wallpaper.setImageDrawable(d);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setBackground(null);
        if (wallpaper.getVisibility() != View.VISIBLE) {
            wallpaper.setAlpha(0f);
            wallpaper.setVisibility(View.VISIBLE);
            wallpaper.animate().alpha(1f).setDuration(400).start();
        }
    }
}
