package net.kdt.pojavlaunch.fragments;

import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.ui.PremiumPlayButtonView;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.util.List;

public class HomeProfileAdapter extends RecyclerView.Adapter<HomeProfileAdapter.ViewHolder> {

    private static final String TAG = "HomeProfileAdapter";

    private static final int PAYLOAD_MOD_COUNT = 1;

    private final List<MinecraftProfile> mProfileList;
    private final List<String> mProfileKeys;
    private final OnProfileActionListener mListener;
    private final int[] mModCountCache;
    private boolean mModCountsReady;
    private int mBoundCount = 0;

    public interface OnProfileActionListener {
        void onProfilePlay(String profileKey, MinecraftProfile profile);
        void onProfileBrowse(String profileKey, MinecraftProfile profile);
        void onProfileEdit(String profileKey, MinecraftProfile profile);
        void onProfileAddShortcut(String profileKey, MinecraftProfile profile);
    }

    public HomeProfileAdapter(List<String> profileKeys, List<MinecraftProfile> profiles,
                              OnProfileActionListener listener) {
        mProfileKeys = profileKeys;
        mProfileList = profiles;
        mListener = listener;
        mModCountCache = new int[profiles.size()];
        setHasStableIds(true);
        preloadModCounts();
    }

    @Override
    public long getItemId(int position) {
        return mProfileKeys.get(position).hashCode();
    }

    private void preloadVisualAssets(@NonNull android.content.res.Resources resources) {
        if (mProfileList.isEmpty()) return;
        PojavApplication.sExecutorService.execute(() -> {
            for (int i = 0; i < mProfileList.size(); i++) {
                MinecraftProfile profile = mProfileList.get(i);
                String key = mProfileKeys.get(i);
                try {
                    ProfileIconCache.fetchIcon(resources, key, profile.icon);
                    ProfileIconCache.fetchBackground(resources, key, profile.background);
                } catch (Throwable ignored) {}
            }
        });
    }

    private void preloadModCounts() {
        if (mProfileList.isEmpty()) return;
        PojavApplication.sExecutorService.execute(() -> {
            for (int i = 0; i < mProfileList.size(); i++) {
                MinecraftProfile profile = mProfileList.get(i);
                int count = 0;
                try {
                    java.io.File gameDir = profile.resolveGameDir();
                    if (gameDir != null) {
                        java.io.File modsDir = new java.io.File(gameDir, "mods");
                        if (modsDir.exists() && modsDir.isDirectory()) {
                            java.io.File[] files = modsDir.listFiles(f -> f.isFile() &&
                                    (f.getName().toLowerCase().endsWith(".jar") || f.getName().toLowerCase().endsWith(".jar.disabled")));
                            count = files != null ? files.length : 0;
                        }
                    }
                } catch (Throwable ignored) {}
                mModCountCache[i] = count;
            }
            mModCountsReady = true;
            Tools.runOnUiThread(() -> notifyItemRangeChanged(0, mProfileList.size(), PAYLOAD_MOD_COUNT));
        });
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_home_profile_card, parent, false);
        // Smooth staggered fade + slide-up entrance for premium feel.
        // No temporary hardware layer here: the state-list animator/compositor
        // already handles presses, and forcing LAYER_TYPE_HARDWARE for a ~300ms
        // entrance costs one extra offscreen buffer per profile card.
        android.view.animation.Animation enterAnim =
                AnimationUtils.loadAnimation(parent.getContext(), R.anim.item_fade_slide_in);
        int pos = mProfileList.isEmpty() ? 0 : Math.min(mBoundCount, 11);
        enterAnim.setStartOffset(pos * 45L);
        mBoundCount++;
        view.startAnimation(enterAnim);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        onBindViewHolder(holder, position, null);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            for (Object payload : payloads) {
                if (payload instanceof Integer && (Integer) payload == PAYLOAD_MOD_COUNT) {
                    int modCount = mModCountCache[position];
                    holder.tvModCount.setText("Installed Mods: " + modCount);
                    return;
                }
            }
        }

        MinecraftProfile profile = mProfileList.get(position);
        String profileKey = mProfileKeys.get(position);

        holder.tvName.setText(profile.name != null ? profile.name : "");

        // Exact Loader/Version
        String version = profile.lastVersionId != null ? profile.lastVersionId : "";
        if (version.length() > 20) {
            version = version.substring(0, 20) + "...";
        }
        holder.tvVersion.setText(version);

        // Mod count (may not be loaded yet)
        int modCount = mModCountsReady ? mModCountCache[position] : 0;
        holder.tvModCount.setText("Installed Mods: " + modCount);

        // RAM chip shows the effective global allocation (per-profile RAM was removed)
        holder.tvRam.setText("RAM: " + net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_RAM_ALLOCATION + " MB");

        bindIcon(holder.imgIcon, profileKey, profile);
        bindBackground(holder.imgBackground, profileKey, profile);

        holder.cardRoot.setOnClickListener(v -> {
            if (mListener != null) mListener.onProfileEdit(profileKey, profile);
        });

        // Long-press opens the shortcut creator. Without this the whole shortcut
        // feature was unreachable: onProfileAddShortcut was declared and
        // implemented, but nothing ever invoked it.
        holder.cardRoot.setOnLongClickListener(v -> {
            if (mListener == null) return false;
            // Short haptic tick so the gesture is discoverable by feel.
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            mListener.onProfileAddShortcut(profileKey, profile);
            return true;
        });

        holder.btnPlay.setOnClickListener(v -> {
            // Phase 3: "launch" is a unique morph, not the old download pulse.
            holder.btnPlay.beginLaunch();
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (mListener != null) mListener.onProfilePlay(profileKey, profile);
        });

        holder.btnBrowse.setOnClickListener(v -> {
            if (mListener != null) mListener.onProfileBrowse(profileKey, profile);
        });

        // Explicit affordance for users who never discover long-press.
        if (holder.btnShortcut != null) {
            holder.btnShortcut.setOnClickListener(v -> {
                if (mListener != null) mListener.onProfileAddShortcut(profileKey, profile);
            });
        }
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        // Pause GIF render threads promptly when cards leave the screen
        net.kdt.pojavlaunch.profiles.ProfileGifSupport.stopDrawable(holder.imgIcon.getDrawable());
        net.kdt.pojavlaunch.profiles.ProfileGifSupport.stopDrawable(holder.imgBackground.getDrawable());
        holder.imgIcon.setImageDrawable(null);
        holder.imgBackground.setImageDrawable(null);
        holder.btnPlay.reset();
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        // Warm icon/banner decode off the UI thread. First bind then hits the
        // shared LRU caches instead of decoding a Base64/GIF payload per row.
        preloadVisualAssets(recyclerView.getResources());
        // Rebind when a remotely-cached asset (e.g. the default animated GIF)
        // finishes downloading so it fades in without user action.
        net.kdt.pojavlaunch.profiles.ProfileGifSupport.addAssetReadyListener(mAssetReadyListener);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        net.kdt.pojavlaunch.profiles.ProfileGifSupport.removeAssetReadyListener(mAssetReadyListener);
        super.onDetachedFromRecyclerView(recyclerView);
    }

    private final net.kdt.pojavlaunch.profiles.ProfileGifSupport.OnAssetReadyListener mAssetReadyListener =
            assetKey -> Tools.runOnUiThread(() ->
                    notifyItemRangeChanged(0, mProfileList.size()));

    private void bindIcon(ImageView target, String profileKey, MinecraftProfile profile) {
        String icon = profile.icon;
        Drawable drawable = null;

        // 1) Custom user icon / modpack artwork (data URI) or named loader icons
        boolean hasCustomOrNamedIcon = icon != null
                && (icon.startsWith("data:") || icon.equals("fabric") || icon.equals("quilt"));
        if (hasCustomOrNamedIcon) {
            try {
                drawable = ProfileIconCache.fetchIcon(target.getResources(), profileKey, icon);
            } catch (Exception e) {
                Log.w(TAG, "Icon load failed for " + profileKey, e);
            }
        }

        // 2) Loader-specific artwork when the version names a mod loader
        if (drawable == null) {
            drawable = resolveTypeFallback(target, profile.lastVersionId);
        }

        // 3) Intelligent defaults: Vanilla → vanilla tile, modded → modpack tile
        if (drawable == null) {
            drawable = ContextCompat.getDrawable(target.getContext(),
                    isVanillaVersion(profile.lastVersionId)
                            ? R.drawable.ic_profile_vanilla
                            : R.drawable.ic_profile_modpack);
        }

        // 4) Absolute safety net: the official CS Launcher logo
        if (drawable == null) {
            drawable = ContextCompat.getDrawable(target.getContext(), R.drawable.ic_cs_logo_placeholder);
        }
        target.setImageDrawable(drawable);
        // GIF icons survive refresh/rebinds (Req-4 lifecycle): resume if paused
        net.kdt.pojavlaunch.profiles.ProfileGifSupport.resumeDrawable(drawable);
    }

    /** Filesystem-free vanilla heuristic for scroll-safe binding. */
    private static boolean isVanillaVersion(@Nullable String lastVersionId) {
        if (lastVersionId == null) return true;
        String lower = lastVersionId.toLowerCase();
        return !(lower.contains("fabric") || lower.contains("forge") || lower.contains("neoforge")
                || lower.contains("quilt") || lower.contains("liteloader") || lower.contains("optifine"));
    }

    private void bindBackground(ImageView target, String profileKey, MinecraftProfile profile) {
        Drawable drawable = null;
        try {
            drawable = ProfileIconCache.fetchBackground(target.getResources(), profileKey, profile.background);
        } catch (Exception e) {
            Log.w(TAG, "Background load failed for " + profileKey, e);
        }
        if (drawable != null) {
            target.setImageDrawable(drawable);
            // Cache-hit GIFs paused by recycling keep playing after a refresh
            net.kdt.pojavlaunch.profiles.ProfileGifSupport.resumeDrawable(drawable);
            target.setVisibility(View.VISIBLE);
            // Show scrim when background is present
            View scrim = ((ViewGroup) target.getParent()).findViewById(R.id.img_profile_background_scrim);
            if (scrim != null) scrim.setVisibility(View.VISIBLE);
        } else {
            target.setImageDrawable(null);
            target.setVisibility(View.GONE);
            View scrim = ((ViewGroup) target.getParent()).findViewById(R.id.img_profile_background_scrim);
            if (scrim != null) scrim.setVisibility(View.GONE);
        }
    }

    private Drawable resolveTypeFallback(ImageView target, String lastVersionId) {
        if (lastVersionId == null) return null;
        String lower = lastVersionId.toLowerCase();
        int resId = -1;
        if (lower.contains("fabric")) resId = R.drawable.ic_fabric;
        else if (lower.contains("quilt")) resId = R.drawable.ic_quilt;
        else if (lower.contains("neoforge") || lower.contains("forge") || lower.contains("liteloader"))
            resId = R.drawable.ic_profile_modpack; // loader found, no brand art → modpack tile
        if (resId == -1) return null;
        return ContextCompat.getDrawable(target.getContext(), resId);
    }

    @Override
    public int getItemCount() {
        return mProfileList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final View cardRoot;
        final ImageView imgIcon;
        final ImageView imgBackground;
        final TextView tvName;
        final TextView tvVersion;
        final TextView tvModCount;
        final TextView tvRam;
        final PremiumPlayButtonView btnPlay;
        final FrameLayout btnBrowse;
        final View btnShortcut;

        ViewHolder(View itemView) {
            super(itemView);
            cardRoot = itemView.findViewById(R.id.card_profile_root);
            imgIcon = itemView.findViewById(R.id.img_profile_icon);
            imgBackground = itemView.findViewById(R.id.img_profile_background);
            tvName = itemView.findViewById(R.id.tv_profile_name);
            tvVersion = itemView.findViewById(R.id.tv_profile_version);
            tvModCount = itemView.findViewById(R.id.tv_profile_mod_count);
            tvRam = itemView.findViewById(R.id.tv_profile_ram);
            btnPlay = itemView.findViewById(R.id.btn_profile_play);
            btnBrowse = itemView.findViewById(R.id.btn_profile_browse);
            btnShortcut = itemView.findViewById(R.id.btn_profile_shortcut);
        }
    }
}
