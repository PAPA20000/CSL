package net.kdt.pojavlaunch.profiles;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple RecyclerView adapter for the wizard's version selection step.
 * Filters by release/snapshot based on user preference.
 */
public class WizardVersionAdapter extends RecyclerView.Adapter<WizardVersionAdapter.VH> {

    public interface OnVersionSelectedListener {
        void onVersionSelected(String versionId, boolean isSnapshot);
    }

    private final JMinecraftVersionList.Version[] mAllVersions;
    private final List<JMinecraftVersionList.Version> mFilteredVersions = new ArrayList<>();
    private boolean mShowSnapshots = false;
    private int mSelectedPosition = -1;
    private OnVersionSelectedListener mListener;

    public WizardVersionAdapter(JMinecraftVersionList.Version[] versions) {
        mAllVersions = versions;
        applyFilter();
    }

    public void setOnVersionSelectedListener(OnVersionSelectedListener listener) {
        mListener = listener;
    }

    public void setShowSnapshots(boolean show) {
        mShowSnapshots = show;
        mSelectedPosition = -1;
        applyFilter();
        notifyDataSetChanged();
    }

    private void applyFilter() {
        mFilteredVersions.clear();
        if (mAllVersions == null) return;
        for (JMinecraftVersionList.Version v : mAllVersions) {
            if (v.type.equals("release")) {
                mFilteredVersions.add(v);
            } else if (mShowSnapshots && v.type.equals("snapshot")) {
                mFilteredVersions.add(v);
            }
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_wizard_version, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        JMinecraftVersionList.Version version = mFilteredVersions.get(position);
        h.versionName.setText(version.id);

        // Type badge
        String badge = version.type;
        if ("release".equals(badge)) {
            h.versionTypeBadge.setText("Release");
            h.versionTypeBadge.setTextColor(0xFF00FF41);
        } else if ("snapshot".equals(badge)) {
            h.versionTypeBadge.setText("Snapshot");
            h.versionTypeBadge.setTextColor(0xFFFFAA00);
        } else {
            h.versionTypeBadge.setText(badge);
            h.versionTypeBadge.setTextColor(0xFFAAAAAA);
        }

        // Selected state
        boolean selected = position == mSelectedPosition;
        h.selectedDot.setVisibility(selected ? View.VISIBLE : View.GONE);
        h.itemView.setAlpha(selected ? 1f : 0.8f);

        h.itemView.setOnClickListener(v -> {
            int prev = mSelectedPosition;
            mSelectedPosition = h.getAdapterPosition();
            if (prev >= 0) notifyItemChanged(prev);
            notifyItemChanged(mSelectedPosition);
            if (mListener != null) {
                mListener.onVersionSelected(version.id, "snapshot".equals(version.type));
            }
        });
    }

    @Override
    public int getItemCount() {
        return mFilteredVersions.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView versionName;
        TextView versionTypeBadge;
        View selectedDot;

        VH(@NonNull View v) {
            super(v);
            versionName = v.findViewById(R.id.version_name);
            versionTypeBadge = v.findViewById(R.id.version_type_badge);
            selectedDot = v.findViewById(R.id.version_selected_dot);
        }
    }
}
