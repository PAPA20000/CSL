package net.kdt.pojavlaunch.fragments;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.RTSpinnerAdapter;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.profiles.WizardVersionAdapter;
import net.kdt.pojavlaunch.utils.CropperUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Wizard-style profile creation fragment with 3 steps:
 * Step 1: Select Loader (Vanilla, Fabric, Forge, NeoForge, Quilt)
 * Step 2: Select Minecraft Version
 * Step 3: Profile Configuration (name, icon, RAM, game dir, java, renderer)
 */
public class ProfileWizardFragment extends Fragment implements CropperUtils.CropperListener {
    public static final String TAG = "ProfileWizardFragment";

    private static final int STEP_LOADER = 0;
    private static final int STEP_VERSION = 1;
    private static final int STEP_CONFIG = 2;
    private static final int TOTAL_STEPS = 3;

    private int mCurrentStep = STEP_LOADER;
    private String mSelectedLoader = null; // "vanilla", "fabric", "forge", "neoforge", "quilt"
    private String mSelectedVersion = null;
    private int mRamMb = 2048;

    // UI references
    private View mRoot;
    private TextView mTitle, mSubtitle;
    private View mStep1, mStep2, mStep3;
    private View mStepIndicator1, mStepIndicator2, mStepIndicator3;
    private Button mBackButton, mNextButton;

    // Step 1 - Loader selection
    private View mLoaderVanilla, mLoaderFabric, mLoaderForge, mLoaderNeoforge, mLoaderQuilt;
    private View mLastSelectedLoaderView = null;

    // Step 2 - Version selection
    private TextView mLoaderBadge;
    private ProgressBar mVersionLoading;
    private TextView mVersionLoadingText;
    private View mVersionFilters;
    private RecyclerView mVersionList;
    private TextView mVersionSelectedBar;
    private TextView mVersionSelectedName;
    private TextView mFilterRelease, mFilterSnapshot;
    private boolean mShowSnapshots = false;
    private WizardVersionAdapter mVersionAdapter;

    // Step 3 - Configuration
    private EditText mProfileName;
    private TextView mRamValue;
    private TextView mGameDir;
    private Spinner mJavaRuntime, mRenderer;
    private EditText mJvmArgs;
    private ImageView mProfileIcon;
    private List<String> mRenderNames;
    private String mProfileIconUri = null;

    // Icon cropper
    private final androidx.activity.result.ActivityResultLauncher<?> mCropperLauncher =
            CropperUtils.registerCropper(this, this);

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mBgExecutor = PojavApplication.sExecutorService;

    public ProfileWizardFragment() {
        super(R.layout.fragment_profile_wizard);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile_wizard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mRoot = view;

        // Header
        mTitle = view.findViewById(R.id.wizard_title);
        mSubtitle = view.findViewById(R.id.wizard_subtitle);

        // Step indicators
        mStepIndicator1 = view.findViewById(R.id.step_indicator_1);
        mStepIndicator2 = view.findViewById(R.id.step_indicator_2);
        mStepIndicator3 = view.findViewById(R.id.step_indicator_3);

        // Step views (inflated into the root layout directly)
        mStep1 = LayoutInflater.from(getContext()).inflate(R.layout.fragment_wizard_step_loader, null);
        mStep2 = LayoutInflater.from(getContext()).inflate(R.layout.fragment_wizard_step_version, null);
        mStep3 = LayoutInflater.from(getContext()).inflate(R.layout.fragment_wizard_step_config, null);

        // Container for wizard steps
        android.widget.FrameLayout flipContainer = view.findViewById(R.id.wizard_viewpager);

        // Add all steps to container (hidden initially)
        mStep1.setVisibility(View.GONE);
        mStep2.setVisibility(View.GONE);
        mStep3.setVisibility(View.GONE);
        flipContainer.addView(mStep1);
        flipContainer.addView(mStep2);
        flipContainer.addView(mStep3);

        // Navigation buttons
        mBackButton = view.findViewById(R.id.wizard_back_button);
        mNextButton = view.findViewById(R.id.wizard_next_button);

        mBackButton.setOnClickListener(v -> goToStep(mCurrentStep - 1));
        mNextButton.setOnClickListener(v -> onNextClicked());

        // Setup Step 1 - Loader selection
        setupStep1();

        // Setup Step 2 - Version selection
        setupStep2();

        // Setup Step 3 - Configuration
        setupStep3();

        // Show step 1
        showStep(STEP_LOADER);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STEP NAVIGATION
    // ══════════════════════════════════════════════════════════════════════════

    private void showStep(int step) {
        mCurrentStep = step;
        mStep1.setVisibility(step == STEP_LOADER ? View.VISIBLE : View.GONE);
        mStep2.setVisibility(step == STEP_VERSION ? View.VISIBLE : View.GONE);
        mStep3.setVisibility(step == STEP_CONFIG ? View.VISIBLE : View.GONE);

        // Update header
        switch (step) {
            case STEP_LOADER:
                mTitle.setText("Create Profile");
                mSubtitle.setText("Step 1 of 3 — Select Loader");
                mBackButton.setVisibility(View.GONE);
                mNextButton.setText("Next");
                mNextButton.setEnabled(mSelectedLoader != null);
                break;
            case STEP_VERSION:
                mTitle.setText("Create Profile");
                mSubtitle.setText("Step 2 of 3 — Select Version");
                mBackButton.setVisibility(View.VISIBLE);
                mNextButton.setText("Next");
                mNextButton.setEnabled(mSelectedVersion != null);
                mLoaderBadge.setText(formatLoaderName(mSelectedLoader));
                loadVersionsForLoader();
                break;
            case STEP_CONFIG:
                mTitle.setText("Create Profile");
                mSubtitle.setText("Step 3 of 3 — Configure Profile");
                mBackButton.setVisibility(View.VISIBLE);
                mNextButton.setText("Create Profile");
                mNextButton.setEnabled(true);
                prefillConfig();
                break;
        }

        // Update step indicators
        mStepIndicator1.setBackgroundResource(step > STEP_LOADER ? R.drawable.bg_step_completed :
                (step == STEP_LOADER ? R.drawable.bg_step_active : R.drawable.bg_step_inactive));
        mStepIndicator2.setBackgroundResource(step > STEP_VERSION ? R.drawable.bg_step_completed :
                (step == STEP_VERSION ? R.drawable.bg_step_active : R.drawable.bg_step_inactive));
        mStepIndicator3.setBackgroundResource(step >= STEP_CONFIG ?
                R.drawable.bg_step_active : R.drawable.bg_step_inactive);
    }

    private void goToStep(int step) {
        if (step < STEP_LOADER || step > STEP_CONFIG) return;
        showStep(step);
    }

    private void onNextClicked() {
        if (mCurrentStep == STEP_CONFIG) {
            createProfile();
        } else {
            goToStep(mCurrentStep + 1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STEP 1: LOADER SELECTION
    // ══════════════════════════════════════════════════════════════════════════

    private void setupStep1() {
        mLoaderVanilla = mStep1.findViewById(R.id.loader_vanilla);
        mLoaderFabric = mStep1.findViewById(R.id.loader_fabric);
        mLoaderForge = mStep1.findViewById(R.id.loader_forge);
        mLoaderNeoforge = mStep1.findViewById(R.id.loader_neoforge);
        mLoaderQuilt = mStep1.findViewById(R.id.loader_quilt);

        View.OnClickListener loaderClick = v -> selectLoader(v, getLoaderForView(v));
        mLoaderVanilla.setOnClickListener(loaderClick);
        mLoaderFabric.setOnClickListener(loaderClick);
        mLoaderForge.setOnClickListener(loaderClick);
        mLoaderNeoforge.setOnClickListener(loaderClick);
        mLoaderQuilt.setOnClickListener(loaderClick);

        // Touch animations
        setupTouchAnimation(mLoaderVanilla);
        setupTouchAnimation(mLoaderFabric);
        setupTouchAnimation(mLoaderForge);
        setupTouchAnimation(mLoaderNeoforge);
        setupTouchAnimation(mLoaderQuilt);
    }

    private String getLoaderForView(View v) {
        if (v == mLoaderVanilla) return "vanilla";
        if (v == mLoaderFabric) return "fabric";
        if (v == mLoaderForge) return "forge";
        if (v == mLoaderNeoforge) return "neoforge";
        if (v == mLoaderQuilt) return "quilt";
        return "vanilla";
    }

    private void selectLoader(View view, String loader) {
        if (mLastSelectedLoaderView != null) {
            mLastSelectedLoaderView.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
        }
        mSelectedLoader = loader;
        mLastSelectedLoaderView = view;
        view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100).start();
        mNextButton.setEnabled(true);
    }

    private String formatLoaderName(String loader) {
        if (loader == null) return "Vanilla";
        switch (loader) {
            case "fabric": return "Fabric";
            case "forge": return "Forge";
            case "neoforge": return "NeoForge";
            case "quilt": return "Quilt";
            default: return "Vanilla";
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STEP 2: VERSION SELECTION
    // ══════════════════════════════════════════════════════════════════════════

    private void setupStep2() {
        mLoaderBadge = mStep2.findViewById(R.id.wizard_selected_loader_label);
        mVersionLoading = mStep2.findViewById(R.id.wizard_version_loading);
        mVersionLoadingText = mStep2.findViewById(R.id.wizard_version_loading_text);
        mVersionFilters = mStep2.findViewById(R.id.wizard_version_filters);
        mVersionList = mStep2.findViewById(R.id.wizard_version_list);
        mVersionSelectedBar = mStep2.findViewById(R.id.wizard_version_selected_bar);
        mVersionSelectedName = mStep2.findViewById(R.id.wizard_version_selected_name);
        mFilterRelease = mStep2.findViewById(R.id.wizard_filter_release);
        mFilterSnapshot = mStep2.findViewById(R.id.wizard_filter_snapshot);

        mFilterRelease.setOnClickListener(v -> {
            mShowSnapshots = false;
            mFilterRelease.setTextColor(0xFFFFFFFF);
            mFilterSnapshot.setTextColor(0xFFAAAAAA);
            if (mVersionAdapter != null) mVersionAdapter.setShowSnapshots(false);
        });
        mFilterSnapshot.setOnClickListener(v -> {
            mShowSnapshots = true;
            mFilterSnapshot.setTextColor(0xFFFFFFFF);
            mFilterRelease.setTextColor(0xFFAAAAAA);
            if (mVersionAdapter != null) mVersionAdapter.setShowSnapshots(true);
        });
    }

    private void loadVersionsForLoader() {
        mVersionLoading.setVisibility(View.VISIBLE);
        mVersionLoadingText.setVisibility(View.VISIBLE);
        mVersionFilters.setVisibility(View.GONE);
        mVersionList.setVisibility(View.GONE);
        mVersionSelectedBar.setVisibility(View.GONE);
        mNextButton.setEnabled(false);

        mBgExecutor.execute(() -> {
            try {
                JMinecraftVersionList versionList = (JMinecraftVersionList)
                        ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
                JMinecraftVersionList.Version[] versions;
                if (versionList != null && versionList.versions != null) {
                    versions = versionList.versions;
                } else {
                    versions = new JMinecraftVersionList.Version[0];
                }

                mMainHandler.post(() -> {
                    if (!isAdded()) return;
                    mVersionLoading.setVisibility(View.GONE);
                    mVersionLoadingText.setVisibility(View.GONE);
                    mVersionFilters.setVisibility(View.VISIBLE);
                    mVersionList.setVisibility(View.VISIBLE);

                    mVersionAdapter = new WizardVersionAdapter(versions);
                    mVersionList.setLayoutManager(new LinearLayoutManager(requireContext()));
                    mVersionList.setAdapter(mVersionAdapter);

                    mVersionAdapter.setOnVersionSelectedListener((version, isSnapshot) -> {
                        mSelectedVersion = version;
                        mVersionSelectedBar.setVisibility(View.VISIBLE);
                        mVersionSelectedName.setText(version);
                        mNextButton.setEnabled(true);
                    });
                });
            } catch (Exception e) {
                mMainHandler.post(() -> {
                    if (!isAdded()) return;
                    mVersionLoading.setVisibility(View.GONE);
                    mVersionLoadingText.setText("Failed to load versions");
                    mNextButton.setEnabled(false);
                });
            }
        });
    }


    // ══════════════════════════════════════════════════════════════════════════
    // STEP 3: PROFILE CONFIGURATION
    // ══════════════════════════════════════════════════════════════════════════

    private void setupStep3() {
        mProfileName = mStep3.findViewById(R.id.wizard_profile_name);
        mRamValue = mStep3.findViewById(R.id.wizard_ram_value);
        mGameDir = mStep3.findViewById(R.id.wizard_game_dir);
        mJavaRuntime = mStep3.findViewById(R.id.wizard_java_runtime);
        mRenderer = mStep3.findViewById(R.id.wizard_renderer);
        mJvmArgs = mStep3.findViewById(R.id.wizard_jvm_args);
        mProfileIcon = mStep3.findViewById(R.id.wizard_profile_icon);

        // RAM controls
        ImageButton ramDecrease = mStep3.findViewById(R.id.wizard_ram_decrease);
        ImageButton ramIncrease = mStep3.findViewById(R.id.wizard_ram_increase);
        ramDecrease.setOnClickListener(v -> adjustRam(-512));
        ramIncrease.setOnClickListener(v -> adjustRam(512));

        // Profile icon
        mStep3.findViewById(R.id.wizard_profile_icon_edit).setOnClickListener(v -> {
            mCropperLauncher.launch(null);
        });

        // Java runtime spinner
        ArrayList<Runtime> runtimes = new ArrayList<>(MultiRTUtils.getInstalledRuntimes());
        RTSpinnerAdapter rtAdapter = new RTSpinnerAdapter(runtimes, requireContext());
        mJavaRuntime.setAdapter(rtAdapter);

        // Renderer spinner
        Tools.RenderersList renderersList = Tools.getCompatibleRenderers(requireContext());
        mRenderNames = new ArrayList<>(renderersList.rendererIds);
        String[] displayNames = renderersList.rendererDisplayNames;
        mRenderNames.add("<Default>");
        
        // Create display name array with "<Default>" appended
        String[] allDisplayNames = new String[displayNames.length + 1];
        System.arraycopy(displayNames, 0, allDisplayNames, 0, displayNames.length);
        allDisplayNames[displayNames.length] = "<Default>";
        
        ArrayAdapter<String> rendererAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, allDisplayNames);
        rendererAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mRenderer.setAdapter(rendererAdapter);
        mRenderer.setSelection(mRenderNames.size() - 1); // Default
    }

    private void prefillConfig() {
        // Auto-suggest profile name
        if (mProfileName.getText().toString().isEmpty()) {
            String loaderName = formatLoaderName(mSelectedLoader);
            String suggestedName = loaderName + " " + (mSelectedVersion != null ? mSelectedVersion : "");
            mProfileName.setText(suggestedName.trim());
        }

        // Show game directory path
        String profileName = mProfileName.getText().toString().trim();
        if (profileName.isEmpty()) profileName = "New Profile";
        mGameDir.setText("custom_instances/" + profileName.replaceAll("[^a-zA-Z0-9_-]", "_"));

        // Browse Resources button
        View browseResourcesBtn = mStep3.findViewById(R.id.wizard_browse_resources);
        if (browseResourcesBtn != null) {
            browseResourcesBtn.setOnClickListener(v -> {
                // First create the profile, then open resource browser
                createProfile();
            });
        }

        // Listen for name changes to update game dir
        mProfileName.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                String name = s.toString().trim();
                if (name.isEmpty()) name = "New Profile";
                mGameDir.setText("custom_instances/" + name.replaceAll("[^a-zA-Z0-9_-]", "_"));
            }
        });
    }

    private void adjustRam(int delta) {
        mRamMb = Math.max(512, Math.min(8192, mRamMb + delta));
        mRamValue.setText(String.format(Locale.US, "%d MB", mRamMb));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PROFILE CREATION
    // ══════════════════════════════════════════════════════════════════════════

    private void createProfile() {
        String profileName = mProfileName.getText().toString().trim();
        if (profileName.isEmpty()) {
            profileName = formatLoaderName(mSelectedLoader) + " " +
                    (mSelectedVersion != null ? mSelectedVersion : "Profile");
        }

        // Create MinecraftProfile
        MinecraftProfile profile = MinecraftProfile.createTemplate();
        profile.name = profileName;
        profile.lastVersionId = mSelectedVersion;
        profile.type = "custom";

        // Set game directory for per-profile isolation
        String gameDirRelative = "custom_instances/" +
                profileName.replaceAll("[^a-zA-Z0-9_-]", "_");
        profile.gameDir = gameDirRelative;

        // Create the instance directory
        File instanceDir = new File(Tools.DIR_GAME_HOME, gameDirRelative);
        if (!instanceDir.exists()) {
            instanceDir.mkdirs();
            // Create standard subdirectories
            new File(instanceDir, "mods").mkdirs();
            new File(instanceDir, "resourcepacks").mkdirs();
            new File(instanceDir, "shaderpacks").mkdirs();
            new File(instanceDir, "saves").mkdirs();
            new File(instanceDir, "screenshots").mkdirs();
            new File(instanceDir, "config").mkdirs();
            new File(instanceDir, "logs").mkdirs();
        }

        // Set RAM allocation via JVM args
        String jvmArgs = mJvmArgs.getText().toString().trim();
        if (jvmArgs.isEmpty()) {
            profile.javaArgs = "-Xmx" + mRamMb + "M";
        } else {
            profile.javaArgs = jvmArgs;
        }

        // Set icon if changed
        if (mProfileIconUri != null) {
            profile.icon = mProfileIconUri;
        }

        // Set renderer
        int rendererPos = mRenderer.getSelectedItemPosition();
        if (rendererPos < mRenderNames.size() - 1) {
            profile.pojavRendererName = mRenderNames.get(rendererPos);
        }

        // Set Java runtime
        if (mJavaRuntime.getSelectedItem() instanceof Runtime) {
            Runtime selectedRuntime = (Runtime) mJavaRuntime.getSelectedItem();
            if (!selectedRuntime.name.equals("<Default>") && selectedRuntime.versionString != null) {
                profile.javaDir = Tools.LAUNCHERPROFILES_RTPREFIX + selectedRuntime.name;
            }
        }

        // Save profile
        LauncherProfiles.load();
        String profileKey = LauncherProfiles.getFreeProfileKey();
        LauncherProfiles.insertMinecraftProfile(profile);
        LauncherProfiles.write();

        // Set as current profile
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                .apply();

        Toast.makeText(getContext(), "Profile created: " + profileName, Toast.LENGTH_SHORT).show();

        // Navigate to resource browser
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ResourceBrowserFragment resourceFragment = ResourceBrowserFragment.newInstance(profileKey);
            ((MainMenuFragment) parent).openChildPane(
                    ResourceBrowserFragment.class, ResourceBrowserFragment.TAG, null);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ICON CROPPER
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void onCropped(Bitmap contentBitmap) {
        mProfileIcon.setImageBitmap(contentBitmap);
        mBgExecutor.execute(() -> {
            java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream();
            try (android.util.Base64OutputStream base64OutputStream =
                         new android.util.Base64OutputStream(byteArrayOutputStream, android.util.Base64.NO_WRAP)) {
                contentBitmap.compress(
                        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ?
                                android.graphics.Bitmap.CompressFormat.WEBP :
                                android.graphics.Bitmap.CompressFormat.WEBP_LOSSY,
                        60, base64OutputStream);
                base64OutputStream.flush();
                byteArrayOutputStream.flush();
            } catch (java.io.IOException e) {
                mMainHandler.post(() -> Tools.showErrorRemote(e));
                return;
            }
            String iconLine = new String(byteArrayOutputStream.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            String dataUri = "data:image/webp;base64," + iconLine;
            mMainHandler.post(() -> {
                // Store icon URI for later use during profile creation
                mJvmArgs.setTag(dataUri);
            });
        });
    }

    @Override
    public void onFailed(Exception exception) {
        Tools.showErrorRemote(exception);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ══════════════════════════════════════════════════════════════════════════

    private void setupTouchAnimation(View button) {
        button.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.96f).scaleY(0.96f)
                            .setDuration(100).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(150)
                            .setInterpolator(new OvershootInterpolator(2f))
                            .start();
                    break;
            }
            return false;
        });
    }
}
