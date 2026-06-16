package net.kdt.pojavlaunch.modpack;

import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;
import static net.kdt.pojavlaunch.Tools.hasOnlineProfile;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.util.Log;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.*;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import org.json.JSONObject;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

public class ModpackBuilderFragment extends Fragment {

    public static final String TAG = "ModpackBuilderFragment";

    private static final int TOTAL_STEPS = 6;
    private static final String STATE_KEY = "modpack_builder_state";

    /* ─── UI references ─── */
    private LinearLayout mStepIndicatorContainer;
    private FrameLayout mStepContentContainer;
    private MaterialButton mBtnBack, mBtnNext;

    /* ─── State ─── */
    private BuilderState mState;
    private final List<StepIndicatorDot> mStepDots = new ArrayList<>();

    /* ─── Cached data ─── */
    private List<String> mInstalledVersions;
    private String mSelectedVersion;

    public ModpackBuilderFragment() {
        super(R.layout.fragment_modpack_builder);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mState = (BuilderState) savedInstanceState.getSerializable(STATE_KEY);
        }
        if (mState == null) {
            mState = new BuilderState();
        }
        scanInstalledVersions();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        gatherCurrentStepState();
        outState.putSerializable(STATE_KEY, mState);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mStepIndicatorContainer = view.findViewById(R.id.step_indicator_container);
        mStepContentContainer = view.findViewById(R.id.step_content_container);
        mBtnBack = view.findViewById(R.id.btn_back);
        mBtnNext = view.findViewById(R.id.btn_next);

        buildStepIndicator();
        mBtnBack.setOnClickListener(v -> onBack());
        mBtnNext.setOnClickListener(v -> onNext());

        showStep(mState.currentStep);
    }

    /* ══════════════════════════════════════════
     *  Step navigation
     * ══════════════════════════════════════════ */

    private void showStep(int step) {
        gatherCurrentStepState();

        mState.currentStep = step;
        mStepContentContainer.removeAllViews();

        LayoutInflater inflater = LayoutInflater.from(getContext());
        View stepView;

        switch (step) {
            case 0: stepView = inflater.inflate(R.layout.fragment_modpack_builder_step_version,         mStepContentContainer, false); break;
            case 1: stepView = inflater.inflate(R.layout.fragment_modpack_builder_step_loader,          mStepContentContainer, false); break;
            case 2: stepView = inflater.inflate(R.layout.fragment_modpack_builder_step_mods,            mStepContentContainer, false); break;
            case 3: stepView = inflater.inflate(R.layout.fragment_modpack_builder_step_resourcepacks,   mStepContentContainer, false); break;
            case 4: stepView = inflater.inflate(R.layout.fragment_modpack_builder_step_shaders,         mStepContentContainer, false); break;
            case 5: stepView = inflater.inflate(R.layout.fragment_modpack_builder_step_review,          mStepContentContainer, false); break;
            default: return;
        }

        mStepContentContainer.addView(stepView);
        populateStep(step, stepView);
        updateNavButtons();
        updateStepIndicator();
    }

    private void onBack() {
        if (mState.currentStep > 0) {
            showStep(mState.currentStep - 1);
        }
    }

    private void onNext() {
        if (!validateCurrentStep()) return;

        if (mState.currentStep < TOTAL_STEPS - 1) {
            showStep(mState.currentStep + 1);
        } else {
            generateModpack();
        }
    }

    private void gatherCurrentStepState() {
        if (mStepContentContainer.getChildCount() == 0) return;
        // Per-step gathering is handled inline in populateStep callbacks.
    }

    private boolean validateCurrentStep() {
        switch (mState.currentStep) {
            case 0: return validateVersionStep();
            case 1: return mState.selectedLoader != null;
            case 2:
            case 3:
            case 4: return true; // optional selections
            case 5: return validateReviewStep();
            default: return true;
        }
    }

    private void updateNavButtons() {
        mBtnBack.setVisibility(mState.currentStep > 0 ? View.VISIBLE : View.GONE);

        if (mState.currentStep < TOTAL_STEPS - 1) {
            mBtnNext.setText(R.string.global_next);
            mBtnNext.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_chevron_right));
        } else {
            mBtnNext.setText(R.string.mp_generate);
            mBtnNext.setIcon(null);
        }
    }

    /* ══════════════════════════════════════════
     *  Step indicator
     * ══════════════════════════════════════════ */

    private static class StepIndicatorDot {
        final View dot;
        final TextView label;
        StepIndicatorDot(View dot, TextView label) { this.dot = dot; this.label = label; }
    }

    private void buildStepIndicator() {
        mStepIndicatorContainer.removeAllViews();
        mStepDots.clear();

        String[] subLabels = {
            getString(R.string.mp_step1_short),
            getString(R.string.mp_step2_short),
            getString(R.string.mp_step3_short),
            getString(R.string.mp_step4_short),
            getString(R.string.mp_step5_short),
            getString(R.string.mp_step6_short),
        };

        for (int i = 0; i < TOTAL_STEPS; i++) {
            LinearLayout item = new LinearLayout(requireContext());
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(8, 4, 8, 4);

            View dot = new View(requireContext());
            int dotSize = getResources().getDimensionPixelSize(R.dimen._12sdp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dotSize, dotSize);
            lp.bottomMargin = 4;
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.bg_circle);

            TextView label = new TextView(requireContext());
            label.setText(subLabels[i]);
            label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
            label.setMaxLines(1);

            item.addView(dot);
            item.addView(label);
            mStepIndicatorContainer.addView(item);
            mStepDots.add(new StepIndicatorDot(dot, label));
        }

        // Add connector lines between dots
        for (int i = 0; i < TOTAL_STEPS; i++) {
            View connector = new View(requireContext());
            int height = getResources().getDimensionPixelSize(R.dimen._2sdp);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    getResources().getDimensionPixelSize(R.dimen._24sdp), height);
            clp.gravity = Gravity.CENTER_VERTICAL;
            connector.setLayoutParams(clp);
            connector.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.m3_ref_palette_primary80));
            connector.setAlpha(0.3f);
            // Insert after each step dot except the last
            mStepIndicatorContainer.addView(connector,
                    mStepIndicatorContainer.indexOfChild(
                            mStepIndicatorContainer.getChildAt(mStepIndicatorContainer.getChildCount() - 1)));
        }

        updateStepIndicator();
    }

    private void updateStepIndicator() {
        int active = mState.currentStep;
        int primaryColor = ContextCompat.getColor(requireContext(), R.color.m3_ref_palette_primary80);
        int inactiveColor = (primaryColor & 0x00FFFFFF) | (0x30 << 24);

        for (int i = 0; i < mStepDots.size(); i++) {
            StepIndicatorDot sd = mStepDots.get(i);
            if (i <= active) {
                sd.dot.setBackgroundTintList(ColorStateList.valueOf(primaryColor));
                sd.label.setTextColor(primaryColor);
                sd.label.setTypeface(null, Typeface.BOLD);
            } else {
                sd.dot.setBackgroundTintList(ColorStateList.valueOf(inactiveColor));
                sd.label.setTextColor(inactiveColor);
                sd.label.setTypeface(null, Typeface.NORMAL);
            }
        }
    }

    /* ══════════════════════════════════════════
     *  Step 0: Minecraft Version
     * ══════════════════════════════════════════ */

    private boolean validateVersionStep() {
        if (mSelectedVersion == null || mSelectedVersion.isEmpty()) {
            Toast.makeText(getContext(), R.string.mp_select_version_first, Toast.LENGTH_SHORT).show();
            return false;
        }
        mState.selectedVersionId = mSelectedVersion;
        return true;
    }

    private void populateVersionStep(View view) {
        EditText searchInput = view.findViewById(R.id.version_search_input);
        RecyclerView list = view.findViewById(R.id.version_list);
        list.setLayoutManager(new LinearLayoutManager(getContext()));

        mSelectedVersion = mState.selectedVersionId;

        VersionAdapter adapter = new VersionAdapter(mInstalledVersions, mSelectedVersion, ver -> {
            mSelectedVersion = ver;
        });
        list.setAdapter(adapter);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { adapter.filter(s.toString()); }
        });

        if (mInstalledVersions == null || mInstalledVersions.isEmpty()) {
            Toast.makeText(getContext(), R.string.mp_no_versions_found, Toast.LENGTH_SHORT).show();
        }
    }

    /* ══════════════════════════════════════════
     *  Step 1: Loader
     * ══════════════════════════════════════════ */

    private void populateLoaderStep(View view) {
        GridView grid = view.findViewById(R.id.loader_grid);

        final String[][] loaders = {
            {"vanilla",   "Vanilla",      getString(R.string.mp_loader_vanilla_desc)},
            {"fabric",    "Fabric",       getString(R.string.mp_loader_fabric_desc)},
            {"forge",     "Forge",        getString(R.string.mp_loader_forge_desc)},
            {"neoforge",  "NeoForge",     getString(R.string.mp_loader_neoforge_desc)},
            {"quilt",     "Quilt",        getString(R.string.mp_loader_quilt_desc)},
        };

        // Ensure default loader is set
        if (mState.selectedLoader == null) mState.selectedLoader = "vanilla";
        final String currentLoader = mState.selectedLoader;

        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return loaders.length; }
            @Override public Object getItem(int i) { return loaders[i]; }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_builder_loader, parent, false);
                }

                MaterialCardView card = (MaterialCardView) convertView;
                TextView name = convertView.findViewById(R.id.loader_name);
                TextView desc = convertView.findViewById(R.id.loader_desc);
                ImageView icon = convertView.findViewById(R.id.loader_icon);

                String loaderKey = loaders[i][0];
                name.setText(loaders[i][1]);
                desc.setText(loaders[i][2]);
                icon.setImageResource(getLoaderIcon(loaderKey));

                boolean isSelected = loaderKey.equals(mState.selectedLoader);
                card.setChecked(isSelected);
                card.setCardBackgroundColor(isSelected
                        ? ContextCompat.getColor(getContext(), R.color.m3_ref_palette_primary80)
                        : ContextCompat.getColor(getContext(), android.R.color.transparent));

                final int idx = i;
                card.setOnClickListener(v -> {
                    mState.selectedLoader = loaders[idx][0];
                    notifyDataSetChanged();
                });

                return convertView;
            }
        });
    }

    private int getLoaderIcon(String loaderKey) {
        switch (loaderKey) {
            case "fabric":   return R.drawable.ic_fabric;
            case "forge":    return R.drawable.ic_forge;
            case "neoforge": return R.drawable.ic_forge;
            case "quilt":    return R.drawable.ic_quilt;
            default:         return R.drawable.bg_hero_minecraft;
        }
    }

    /* ══════════════════════════════════════════
     *  Step 2: Mods
     * ══════════════════════════════════════════ */

    private void populateModsStep(View view) {
        RecyclerView list = view.findViewById(R.id.mods_list);
        list.setLayoutManager(new LinearLayoutManager(getContext()));

        TextView compatWarnings = view.findViewById(R.id.compat_warnings);
        TextView selectedCount = view.findViewById(R.id.selected_count);

        List<BuilderState.ModEntry> mods;
        if (!mState.selectedMods.isEmpty()) {
            mods = mState.selectedMods;
        } else {
            mods = SmartRecommender.getRecommendedMods(
                    mState.selectedLoader, mState.selectedVersionId);
            mState.selectedMods.clear();
            mState.selectedMods.addAll(mods);
        }

        ModCheckableAdapter adapter = new ModCheckableAdapter(mods, (checkedMods, warnings) -> {
            compatWarnings.setVisibility(warnings.isEmpty() ? View.GONE : View.VISIBLE);
            compatWarnings.setText(TextUtils.join("\n", warnings));
            selectedCount.setText(getString(R.string.mp_mods_selected_count, checkedMods));
        });
        list.setAdapter(adapter);
        adapter.refreshWarnings();
    }

    /* ══════════════════════════════════════════
     *  Step 3: Resource Packs
     * ══════════════════════════════════════════ */

    private void populateResourcePacksStep(View view) {
        RecyclerView list = view.findViewById(R.id.resourcepacks_list);
        list.setLayoutManager(new LinearLayoutManager(getContext()));
        TextView selectedCount = view.findViewById(R.id.rp_selected_count);

        List<BuilderState.ModEntry> rps;
        if (!mState.selectedResourcePacks.isEmpty()) {
            rps = mState.selectedResourcePacks;
        } else {
            rps = SmartRecommender.getRecommendedResourcePacks();
            mState.selectedResourcePacks.clear();
            mState.selectedResourcePacks.addAll(rps);
        }

        ResourcePackAdapter adapter = new ResourcePackAdapter(rps, count ->
            selectedCount.setText(getString(R.string.mp_rp_selected_count, count)));
        list.setAdapter(adapter);
    }

    /* ══════════════════════════════════════════
     *  Step 4: Shaders
     * ══════════════════════════════════════════ */

    private void populateShadersStep(View view) {
        RecyclerView list = view.findViewById(R.id.shaders_list);
        list.setLayoutManager(new LinearLayoutManager(getContext()));
        TextView selectedCount = view.findViewById(R.id.shader_selected_count);

        List<BuilderState.ModEntry> shaders;
        if (!mState.selectedShaders.isEmpty()) {
            shaders = mState.selectedShaders;
        } else {
            shaders = SmartRecommender.getRecommendedShaders();
            mState.selectedShaders.clear();
            mState.selectedShaders.addAll(shaders);
        }

        ShaderAdapter adapter = new ShaderAdapter(shaders, count ->
            selectedCount.setText(getString(R.string.mp_shader_selected_count, count)));
        list.setAdapter(adapter);
    }

    /* ══════════════════════════════════════════
     *  Step 5: Review & Generate
     * ══════════════════════════════════════════ */

    private void populateReviewStep(View view) {
        TextInputEditText nameInput = view.findViewById(R.id.modpack_name_input);
        TextInputEditText descInput = view.findViewById(R.id.modpack_desc_input);

        if (mState.modpackName != null) nameInput.setText(mState.modpackName);
        if (mState.modpackDescription != null) descInput.setText(mState.modpackDescription);

        nameInput.addTextChangedListener(new SimpleTextWatcher(s -> mState.modpackName = s));
        descInput.addTextChangedListener(new SimpleTextWatcher(s -> mState.modpackDescription = s));

        ((TextView) view.findViewById(R.id.review_version)).setText(
                getString(R.string.mp_review_version, mState.selectedVersionId));
        ((TextView) view.findViewById(R.id.review_loader)).setText(
                getString(R.string.mp_review_loader, capitalize(mState.selectedLoader)));

        int modCount = 0, rpCount = 0, shaderCount = 0;
        for (BuilderState.ModEntry e : mState.selectedMods) if (e.enabled) modCount++;
        for (BuilderState.ModEntry e : mState.selectedResourcePacks) if (e.enabled) rpCount++;
        for (BuilderState.ModEntry e : mState.selectedShaders) if (e.enabled) shaderCount++;

        ((TextView) view.findViewById(R.id.review_mods_count)).setText(
                getString(R.string.mp_review_mods, modCount));
        ((TextView) view.findViewById(R.id.review_rp_count)).setText(
                getString(R.string.mp_review_rp, rpCount));
        ((TextView) view.findViewById(R.id.review_shader_count)).setText(
                getString(R.string.mp_review_shader, shaderCount));

        // Compatibility warnings
        MaterialCardView compatCard = view.findViewById(R.id.review_compat_card);
        TextView compatText = view.findViewById(R.id.review_compat_text);

        List<String> enabledNames = new ArrayList<>();
        for (BuilderState.ModEntry e : mState.selectedMods) if (e.enabled) enabledNames.add(e.name);
        List<String> warnings = CompatibilityEngine.checkAll(enabledNames);

        if (warnings.isEmpty()) {
            compatCard.setVisibility(View.GONE);
        } else {
            compatCard.setVisibility(View.VISIBLE);
            compatText.setText(TextUtils.join("\n", warnings));
        }

        view.findViewById(R.id.btn_generate).setOnClickListener(v -> generateModpack());
    }

    private boolean validateReviewStep() {
        if (mState.modpackName == null || mState.modpackName.trim().isEmpty()) {
            Toast.makeText(getContext(), R.string.mp_name_required, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    /* ══════════════════════════════════════════
     *  Generation
     * ══════════════════════════════════════════ */

    private void generateModpack() {
        if (!hasOnlineProfile()) {
            hasNoOnlineProfileDialog(requireActivity());
            return;
        }

        if (mState.generated) {
            Toast.makeText(getContext(), R.string.mp_already_generated, Toast.LENGTH_SHORT).show();
            return;
        }

        mBtnNext.setEnabled(false);
        mBtnNext.setText(R.string.mp_generating);

        PojavApplication.sExecutorService.execute(() -> {
            try {
                ModpackGenerator generator = new ModpackGenerator(requireContext(), mState);
                String profileKey = generator.generate();
                mState.generated = true;

                // Build JSON manifest for share code export
                JSONObject manifest = ModpackGenerator.toManifest(mState);

                Tools.runOnUiThread(() -> {
                    Toast.makeText(getContext(),
                            getString(R.string.mp_generated_success, mState.modpackName),
                            Toast.LENGTH_LONG).show();

                    Bundle args = new Bundle();
                    args.putString("manifest_json", manifest.toString());
                    args.putString("modpack_name", mState.modpackName);
                    Tools.swapFragment(requireActivity(),
                            ShareCodeExportFragment.class, ShareCodeExportFragment.TAG, args);
                });

            } catch (Exception e) {
                Log.e(TAG, "Generation failed", e);
                Tools.runOnUiThread(() -> {
                    Toast.makeText(getContext(),
                            getString(R.string.mp_generate_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                    mBtnNext.setEnabled(true);
                    mBtnNext.setText(R.string.mp_generate);
                });
            }
        });
    }

    /* ══════════════════════════════════════════
     *  Background data loading
     * ══════════════════════════════════════════ */

    private void scanInstalledVersions() {
        mInstalledVersions = new ArrayList<>();
        try {
            File versionsDir = new File(Tools.DIR_GAME_NEW + "/versions");
            if (versionsDir.exists() && versionsDir.isDirectory()) {
                File[] dirs = versionsDir.listFiles(File::isDirectory);
                if (dirs != null) {
                    for (File dir : dirs) {
                        mInstalledVersions.add(dir.getName());
                    }
                }
            }
        } catch (Exception ignored) {}
        Collections.sort(mInstalledVersions, Collections.reverseOrder());
    }

    /* ══════════════════════════════════════════
     *  Adapter: Version list
     * ══════════════════════════════════════════ */

    private class VersionAdapter extends RecyclerView.Adapter<VersionAdapter.VH> {
        private List<String> mOriginal;
        private List<String> mFiltered;
        private String mSelected;
        private final VersionClickListener mListener;

        VersionAdapter(List<String> versions, String selected, VersionClickListener listener) {
            mOriginal = versions != null ? versions : new ArrayList<>();
            mFiltered = new ArrayList<>(mOriginal);
            mSelected = selected;
            mListener = listener;
        }

        void filter(String query) {
            mFiltered.clear();
            if (query.isEmpty()) {
                mFiltered.addAll(mOriginal);
            } else {
                for (String v : mOriginal) {
                    if (v.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                        mFiltered.add(v);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_builder_version, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            String ver = mFiltered.get(i);
            h.radio.setChecked(ver.equals(mSelected));
            h.name.setText(ver);

            String lower = ver.toLowerCase(Locale.ROOT);
            String badge = "";
            if (lower.contains("fabric")) badge = "Fabric";
            else if (lower.contains("forge")) badge = "Forge";
            else if (lower.contains("neoforge")) badge = "NeoForge";
            else if (lower.contains("quilt")) badge = "Quilt";
            else if (lower.contains("optifine")) badge = "OptiFine";
            else if (lower.matches("\\d+\\.\\d+(\\.\\d+)?")) badge = "Vanilla";
            else badge = "Custom";

            h.badge.setText(badge);
            h.badge.setVisibility(badge.isEmpty() ? View.GONE : View.VISIBLE);

            h.itemView.setOnClickListener(v -> {
                mSelected = ver;
                mListener.onVersionSelected(ver);
                notifyDataSetChanged();
            });
        }

        @Override public int getItemCount() { return mFiltered.size(); }

        class VH extends RecyclerView.ViewHolder {
            final RadioButton radio;
            final TextView name, badge;
            VH(View v) { super(v);
                radio = v.findViewById(R.id.version_radio);
                name = v.findViewById(R.id.version_name);
                badge = v.findViewById(R.id.version_type_badge);
            }
        }
    }

    /* ══════════════════════════════════════════
     *  Adapter: Mods (checkable + compat warnings)
     * ══════════════════════════════════════════ */

    private class ModCheckableAdapter extends RecyclerView.Adapter<ModCheckableAdapter.VH> {
        private final List<BuilderState.ModEntry> mMods;
        private final ModCountCallback mCallback;

        ModCheckableAdapter(List<BuilderState.ModEntry> mods, ModCountCallback cb) {
            mMods = mods; mCallback = cb;
        }

        void refreshWarnings() {
            if (mCallback == null) return;
            int count = 0;
            List<String> names = new ArrayList<>();
            for (BuilderState.ModEntry e : mMods) {
                if (e.enabled) { count++; names.add(e.name); }
            }
            mCallback.onChanged(count, CompatibilityEngine.checkAll(names));
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_builder_mod, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            BuilderState.ModEntry mod = mMods.get(i);
            h.checkbox.setChecked(mod.enabled);
            h.name.setText(mod.name);
            h.desc.setText(mod.description);

            h.badge.setVisibility(mod.recommended ? View.VISIBLE : View.GONE);
            h.badge.setText(R.string.mp_recommended_badge);

            h.itemView.setOnClickListener(v -> {
                mod.enabled = !mod.enabled;
                notifyItemChanged(i);
                refreshWarnings();
            });
            h.checkbox.setOnClickListener(v -> {
                mod.enabled = h.checkbox.isChecked();
                refreshWarnings();
            });
        }

        @Override public int getItemCount() { return mMods.size(); }

        class VH extends RecyclerView.ViewHolder {
            final MaterialCheckBox checkbox;
            final TextView name, desc, badge;
            VH(View v) { super(v);
                checkbox = v.findViewById(R.id.mod_checkbox);
                name = v.findViewById(R.id.mod_name);
                desc = v.findViewById(R.id.mod_description);
                badge = v.findViewById(R.id.mod_badge);
            }
        }
    }

    /* ══════════════════════════════════════════
     *  Adapter: Resource packs
     * ══════════════════════════════════════════ */

    private class ResourcePackAdapter extends RecyclerView.Adapter<ResourcePackAdapter.VH> {
        private final List<BuilderState.ModEntry> mItems;
        private final CountCallback mCb;

        ResourcePackAdapter(List<BuilderState.ModEntry> items, CountCallback cb) {
            mItems = items; mCb = cb; notifyCount();
        }

        private void notifyCount() {
            int c = 0;
            for (BuilderState.ModEntry e : mItems) if (e.enabled) c++;
            if (mCb != null) mCb.onChanged(c);
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_builder_resourcepack, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            BuilderState.ModEntry e = mItems.get(i);
            h.checkbox.setChecked(e.enabled);
            h.name.setText(e.name);
            h.desc.setText(e.description);
            h.itemView.setOnClickListener(v -> { e.enabled = !e.enabled; notifyItemChanged(i); notifyCount(); });
            h.checkbox.setOnClickListener(v -> { e.enabled = h.checkbox.isChecked(); notifyCount(); });
        }

        @Override public int getItemCount() { return mItems.size(); }

        class VH extends RecyclerView.ViewHolder {
            final MaterialCheckBox checkbox; final TextView name, desc;
            VH(View v) { super(v);
                checkbox = v.findViewById(R.id.rp_checkbox);
                name = v.findViewById(R.id.rp_name);
                desc = v.findViewById(R.id.rp_description);
            }
        }
    }

    /* ══════════════════════════════════════════
     *  Adapter: Shaders
     * ══════════════════════════════════════════ */

    private class ShaderAdapter extends RecyclerView.Adapter<ShaderAdapter.VH> {
        private final List<BuilderState.ModEntry> mItems;
        private final CountCallback mCb;

        ShaderAdapter(List<BuilderState.ModEntry> items, CountCallback cb) {
            mItems = items; mCb = cb; notifyCount();
        }

        private void notifyCount() {
            int c = 0;
            for (BuilderState.ModEntry e : mItems) if (e.enabled) c++;
            if (mCb != null) mCb.onChanged(c);
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_builder_shader, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            BuilderState.ModEntry e = mItems.get(i);
            h.checkbox.setChecked(e.enabled);
            h.name.setText(e.name);
            h.desc.setText(e.description);
            h.itemView.setOnClickListener(v -> { e.enabled = !e.enabled; notifyItemChanged(i); notifyCount(); });
            h.checkbox.setOnClickListener(v -> { e.enabled = h.checkbox.isChecked(); notifyCount(); });
        }

        @Override public int getItemCount() { return mItems.size(); }

        class VH extends RecyclerView.ViewHolder {
            final MaterialCheckBox checkbox; final TextView name, desc;
            VH(View v) { super(v);
                checkbox = v.findViewById(R.id.shader_checkbox);
                name = v.findViewById(R.id.shader_name);
                desc = v.findViewById(R.id.shader_description);
            }
        }
    }

    /* ─── Interfaces moved outside inner classes to avoid "Illegal static declaration" ─── */

    public interface VersionClickListener { void onVersionSelected(String version); }
    public interface ModCountCallback { void onChanged(int enabledCount, List<String> warnings); }
    public interface CountCallback { void onChanged(int count); }

    /* ══════════════════════════════════════════
     *  Populate step (dispatcher)
     * ══════════════════════════════════════════ */

    private void populateStep(int step, View view) {
        switch (step) {
            case 0: populateVersionStep(view); break;
            case 1: populateLoaderStep(view); break;
            case 2: populateModsStep(view); break;
            case 3: populateResourcePacksStep(view); break;
            case 4: populateShadersStep(view); break;
            case 5: populateReviewStep(view); break;
        }
    }

    /* ══════════════════════════════════════════
     *  Misc helpers
     * ══════════════════════════════════════════ */

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static class SimpleTextWatcher implements TextWatcher {
        private final Consumer<String> onChanged;
        SimpleTextWatcher(Consumer<String> onChanged) { this.onChanged = onChanged; }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) { onChanged.accept(s.toString()); }
    }
}
