package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.client.ClientFeature;
import net.kdt.pojavlaunch.client.EnableClientInstallTask;
import java.util.ArrayList;
import java.util.List;

/**
 * Premium 3-step wizard that enables the CS CLIENT ecosystem:
 * version pick → install summary → fully automated install + profile creation.
 */
public class EnableClientWizardFragment extends Fragment {

    public static final String TAG = "EnableClientWizardFragment";

    private static final int STEP_VERSION = 1;
    private static final int STEP_SUMMARY = 2;
    private static final int STEP_INSTALL = 3;

    private LinearLayout mStep1, mStep2, mStep3;
    private TextView mChip1, mChip2, mChip3;
    private RecyclerView mVersionList;
    private ProgressBar mVersionsLoading;
    private TextView mVersionsError;
    private TextView mSummaryVersion;
    private TextView mStatus, mPercent;
    private ProgressBar mInstallProgress;

    private final List<String> mVersions = new ArrayList<>();
    private String mSelectedVersion;
    private int mStep = STEP_VERSION;

    public EnableClientWizardFragment() {
        super(R.layout.fragment_enable_client_wizard);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mStep1 = view.findViewById(R.id.step_1_container);
        mStep2 = view.findViewById(R.id.step_2_container);
        mStep3 = view.findViewById(R.id.step_3_container);
        mChip1 = view.findViewById(R.id.step_chip_1);
        mChip2 = view.findViewById(R.id.step_chip_2);
        mChip3 = view.findViewById(R.id.step_chip_3);
        mVersionList = view.findViewById(R.id.wizard_version_list);
        mVersionsLoading = view.findViewById(R.id.wizard_versions_loading);
        mVersionsError = view.findViewById(R.id.wizard_versions_error);
        mSummaryVersion = view.findViewById(R.id.summary_mc_version);
        mStatus = view.findViewById(R.id.wizard_install_status);
        mPercent = view.findViewById(R.id.wizard_progress_percent);
        mInstallProgress = view.findViewById(R.id.wizard_install_progress);

        View back = view.findViewById(R.id.wizard_back);
        UiMotion.pressFeedback(back);
        back.setOnClickListener(v -> onBack());

        mVersionList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mVersionList.setAdapter(new VersionAdapter());

        setSummaryRow(R.id.row_fabric_loader, "Fabric Loader", "Latest stable — required");
        setSummaryRow(R.id.row_cs_client, "CS CLIENT", "Premium client mod — latest release");

        if (getParentFragment() != null) UiMotion.revealScreen(view);

        View enableButton = view.findViewById(R.id.wizard_enable_button);
        UiMotion.pressFeedback(enableButton);
        enableButton.setOnClickListener(v -> startInstall());

        loadVersions();
    }

    private void setSummaryRow(int rowId, String title, String subtitle) {
        View row = getView() != null ? getView().findViewById(rowId) : null;
        if (row == null) return;
        TextView t = row.findViewById(R.id.component_title);
        TextView s = row.findViewById(R.id.component_subtitle);
        if (t != null) t.setText(title);
        if (s != null) s.setText(subtitle);
    }

    private void onBack() {
        if (mStep == STEP_SUMMARY) {
            showStep(STEP_VERSION);
        } else if (mStep == STEP_VERSION) {
            if (getParentFragmentManager().getBackStackEntryCount() > 0) {
                getParentFragmentManager().popBackStack();
            } else if (getActivity() != null) {
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            }
        }
    }

    // ── Step 1: versions ────────────────────────────────────────────────

    private void loadVersions() {
        mVersionsLoading.setVisibility(View.VISIBLE);
        mVersionsError.setVisibility(View.GONE);
        // Dynamic: fetch the Minecraft versions actually published on Modrinth and
        // keep only the ones CS CLIENT supports (stable 1.21.x <= 1.21.11). Falls
        // back to the static build-verified list when offline/Modrinth unreachable.
        PojavApplication.sExecutorService.execute(() -> {
            List<String> found = ClientFeature.fetchAvailableVersions();
            List<String> finalFound = found;
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                mVersionsLoading.setVisibility(View.GONE);
                if (finalFound.isEmpty()) {
                    mVersionsError.setVisibility(View.VISIBLE);
                    return;
                }
                mVersions.clear();
                mVersions.addAll(finalFound);
                mVersionList.getAdapter().notifyDataSetChanged();
                UiMotion.revealList(mVersionList);
            });
        });
    }

    private void onVersionSelected(String version) {
        mSelectedVersion = version;
        mSummaryVersion.setText("Minecraft " + version);
        mVersionList.getAdapter().notifyDataSetChanged();
        showStep(STEP_SUMMARY);
    }

    // ── Step 2: summary → install ───────────────────────────────────────

    private void startInstall() {
        if (mSelectedVersion == null) return;
        showStep(STEP_INSTALL);
        mStatus.setText("Preparing installation...");
        mInstallProgress.setProgress(0);
        mPercent.setText("0%");

        EnableClientInstallTask task = new EnableClientInstallTask(mSelectedVersion,
                new EnableClientInstallTask.Listener() {
                    @Override
                    public void onProgress(int percent, String message) {
                        Tools.runOnUiThread(() -> {
                            if (!isAdded()) return;
                            mInstallProgress.setProgress(percent);
                            mPercent.setText(percent + "%");
                            mStatus.setText(message);
                        });
                    }

                    @Override
                    public void onSuccess(String profileKey, String versionId) {
                        Tools.runOnUiThread(() -> {
                            if (!isAdded()) return;
                            ClientFeature.markEnabled(requireContext(), mSelectedVersion, profileKey);
                            mStatus.setText("Done! CS CLIENT is ready to launch.");
                            mInstallProgress.setProgress(100);
                            mPercent.setText("100%");
                            mStatus.postDelayed(() -> {
                                if (!isAdded()) return;
                                Toast.makeText(requireContext(),
                                        "CS CLIENT enabled — profile created", Toast.LENGTH_LONG).show();
                                finishWizard();
                            }, 900);
                        });
                    }

                    @Override
                    public void onError(Exception e) {
                        Tools.runOnUiThread(() -> {
                            if (!isAdded()) return;
                            mStatus.setText("Installation failed: " + e.getMessage());
                            mStatus.postDelayed(() -> {
                                if (!isAdded()) return;
                                showStep(STEP_SUMMARY);
                            }, 2200);
                        });
                    }
                });
        new Thread(task).start();
    }

    private void finishWizard() {
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).refreshHomeState();
            if (parent.getChildFragmentManager().getBackStackEntryCount() > 0) {
                parent.getChildFragmentManager().popBackStackImmediate();
            }
        } else if (getParentFragmentManager().getBackStackEntryCount() > 0) {
            getParentFragmentManager().popBackStack();
        } else if (getActivity() != null) {
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        }
    }

    // ── Step transitions ────────────────────────────────────────────────

    private void showStep(int step) {
        mStep = step;
        boolean s1 = step == STEP_VERSION, s2 = step == STEP_SUMMARY, s3 = step == STEP_INSTALL;
        crossfade(mStep1, s1);
        crossfade(mStep2, s2);
        crossfade(mStep3, s3);
        setChip(mChip1, s1 || s2 || s3);
        setChip(mChip2, s2 || s3);
        setChip(mChip3, s3);
    }

    private void crossfade(View v, boolean visible) {
        if (v == null) return;
        if (visible && v.getVisibility() != View.VISIBLE) {
            v.setAlpha(0f);
            v.setTranslationY(24f);
            v.setVisibility(View.VISIBLE);
            v.animate().alpha(1f).translationY(0f).setDuration(240)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        } else if (!visible && v.getVisibility() == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                if (v != null) v.setVisibility(View.GONE);
            }).start();
        }
    }

    private void setChip(TextView chip, boolean active) {
        if (chip == null) return;
        chip.setBackgroundResource(active ? R.drawable.bg_badge_pill : R.drawable.bg_browse_filter_btn);
        chip.setTextColor(active ? 0xFF0E0E11 : 0xFF9C9CA8);
        chip.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120)
                .withEndAction(() -> chip.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
    }

    // ── Version list adapter ────────────────────────────────────────────

    private class VersionAdapter extends RecyclerView.Adapter<VersionAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_client_version_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            String version = mVersions.get(position);
            holder.label.setText(version);
            boolean selected = version.equals(mSelectedVersion);
            holder.check.setVisibility(selected ? View.VISIBLE : View.GONE);
            holder.itemView.setAlpha(selected ? 1f : 0.92f);
            holder.itemView.setOnClickListener(v -> onVersionSelected(version));
        }

        @Override
        public int getItemCount() {
            return mVersions.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView label;
            final ImageView check;

            VH(@NonNull View itemView) {
                super(itemView);
                label = itemView.findViewById(R.id.client_ver_label);
                check = itemView.findViewById(R.id.client_ver_check);
            }
        }
    }

}
