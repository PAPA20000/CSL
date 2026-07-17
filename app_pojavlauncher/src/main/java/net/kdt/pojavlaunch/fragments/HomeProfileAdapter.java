package net.kdt.pojavlaunch.fragments;

import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.LauncherPreferences;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.util.List;

public class HomeProfileAdapter extends RecyclerView.Adapter<HomeProfileAdapter.ViewHolder> {

    private static final String TAG = "HomeProfileAdapter";

    private static final int PAYLOAD_MOD_COUNT = 1;

    private final List<MinecraftProfile> mProfileList;
    private final List<String> mProfileKeys;
    private final OnProfileActionListener mListener;
    // Pre-computed mod counts (0 = not loaded yet or no mods). Populated on background thread.
    private final int[] mModCountCache;
    private boolean mModCountsReady;

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
        // Kick off background loading of mod counts
        preloadModCounts();
    }

    @Override
    public long getItemId(int position) {
        return mProfileKeys.get(position).hashCode();
    }

    /** Pre-compute mod counts on a background thread so onBindViewHolder never blocks on I/O. */
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
            // Use payload update so only mod count TextView rebinds, not the full card
            Tools.runOnUiThread(() -> notifyItemRangeChanged(0, mProfileList.size(), PAYLOAD_MOD_COUNT));
        });
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_home_profile_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        onBindViewHolder(holder, position, null);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        // Payload-based partial binding: skip expensive icon binding when only mod count changed
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

        // Exact Loader/Version — displayed exactly as stored (e.g. fabric-loader-0.19.3-26.2)
        holder.tvVersion.setText(profile.lastVersionId != null && !profile.lastVersionId.isEmpty()
                ? profile.lastVersionId : "");

        // Read from pre-computed cache — never blocks on I/O
        int modCount = mModCountCache[position];
        holder.tvModCount.setText("Installed Mods: " + modCount);

        // Exact Allocated RAM — per-profile value when set, otherwise the global launcher setting
        int ramMB = (profile.ramAllocationMB != null)
                ? profile.ramAllocationMB : LauncherPreferences.PREF_RAM_ALLOCATION;
        holder.tvRam.setText(holder.itemView.getContext().getString(R.string.allocated_ram)
                + ": " + ramMB + " MB");

        bindIcon(holder.imgIcon, profileKey, profile);
        bindBackground(holder.imgBackground, profileKey, profile);

        holder.cardRoot.setOnClickListener(v -> {
            if (mListener != null) mListener.onProfileEdit(profileKey, profile);
        });

        // Three-dot (⋮) menu replaces the old long-press shortcut behavior.
        holder.btnMenu.setOnClickListener(v -> showProfileMenu(v, profileKey, profile));

        holder.btnPlay.setOnClickListener(v -> {
            if (mListener != null) mListener.onProfilePlay(profileKey, profile);
        });

        holder.btnBrowse.setOnClickListener(v -> {
            if (mListener != null) mListener.onProfileBrowse(profileKey, profile);
        });
    }

    /**
     * Shows the profile overflow menu (Edit Profile + Shortcuts) anchored to the three-dot button.
     */
    private void showProfileMenu(View anchor, String profileKey, MinecraftProfile profile) {
        PopupMenu popup = new PopupMenu(anchor.getContext(), anchor);
        popup.getMenu().add(0, R.id.menu_profile_edit, 0, R.string.edit_profile);
        popup.getMenu().add(0, R.id.menu_profile_shortcuts, 1, R.string.profile_menu_shortcuts);
        popup.setOnMenuItemClickListener(item -> {
            if (mListener == null) return false;
            int id = item.getItemId();
            if (id == R.id.menu_profile_edit) {
                mListener.onProfileEdit(profileKey, profile);
                return true;
            } else if (id == R.id.menu_profile_shortcuts) {
                mListener.onProfileAddShortcut(profileKey, profile);
                return true;
            }
            return false;
        });
        popup.show();
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        // Clear image drawable reference to help GC and avoid stale bitmap retention
        holder.imgIcon.setImageDrawable(null);
        holder.imgBackground.setImageDrawable(null);
    }

    /**
     * Binds the graphical icon resource to the profile card image view.
     * Falls back to a typed icon (fabric / quilt / default) if the data icon
     * is missing or invalid, eliminating empty/hollow gray boxes.
     */
    private void bindIcon(ImageView target, String profileKey, MinecraftProfile profile) {
        String icon = profile.icon;
        Drawable drawable = null;

        try {
            drawable = ProfileIconCache.fetchIcon(target.getResources(), profileKey, icon);
        } catch (Exception e) {
            Log.w(TAG, "Icon load failed for " + profileKey, e);
        }

        if (drawable == null) {
            drawable = resolveTypeFallback(target, profile.lastVersionId);
        }
        if (drawable == null) {
            drawable = ContextCompat.getDrawable(target.getContext(), R.drawable.ic_pojav_full);
        }
        target.setImageDrawable(drawable);
    }

    /**
     * Binds the profile background banner image. Hides the banner when no background is set,
     * so the card falls back to its default background drawable.
     */
    private void bindBackground(ImageView target, String profileKey, MinecraftProfile profile) {
        Drawable drawable = null;
        try {
            drawable = ProfileIconCache.fetchBackground(target.getResources(), profileKey, profile.background);
        } catch (Exception e) {
            Log.w(TAG, "Background load failed for " + profileKey, e);
        }
        if (drawable != null) {
            target.setImageDrawable(drawable);
            target.setVisibility(View.VISIBLE);
        } else {
            target.setImageDrawable(null);
            target.setVisibility(View.GONE);
        }
    }

    /**
     * Picks a type-aware fallback icon based on the profile's MC version id
     * (e.g. "fabric-loader-1.20.1" → fabric icon). Avoids empty boxes when
     * the base64 icon payload is missing or corrupted.
     */
    private Drawable resolveTypeFallback(ImageView target, String lastVersionId) {
        if (lastVersionId == null) return null;
        String lower = lastVersionId.toLowerCase();
        int resId = -1;
        if (lower.contains("fabric")) resId = R.drawable.ic_fabric;
        else if (lower.contains("quilt")) resId = R.drawable.ic_quilt;
        else if (lower.contains("forge")) resId = R.drawable.ic_pojav_full;
        else if (lower.contains("neoforge")) resId = R.drawable.ic_pojav_full;
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
        final ImageButton btnMenu;
        final FrameLayout btnPlay;
        final FrameLayout btnBrowse;

        ViewHolder(View itemView) {
            super(itemView);
            cardRoot = itemView.findViewById(R.id.card_profile_root);
            imgIcon = itemView.findViewById(R.id.img_profile_icon);
            imgBackground = itemView.findViewById(R.id.img_profile_background);
            tvName = itemView.findViewById(R.id.tv_profile_name);
            tvVersion = itemView.findViewById(R.id.tv_profile_version);
            tvModCount = itemView.findViewById(R.id.tv_profile_mod_count);
            tvRam = itemView.findViewById(R.id.tv_profile_ram);
            btnMenu = itemView.findViewById(R.id.btn_profile_menu);
            btnPlay = itemView.findViewById(R.id.btn_profile_play);
            btnBrowse = itemView.findViewById(R.id.btn_profile_browse);
        }
    }
}
