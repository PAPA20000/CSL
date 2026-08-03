package net.kdt.pojavlaunch.fragments;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.animation.LayoutTransition;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ImageButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.modloaders.InstalledModAdapter;
import net.kdt.pojavlaunch.modloaders.LocalPackAdapter;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.RTSpinnerAdapter;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.profiles.VersionSelectorDialog;
import net.kdt.pojavlaunch.utils.CropperUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import android.net.Uri;
import android.widget.Toast;
import androidx.activity.result.contract.ActivityResultContracts;
import org.apache.commons.io.IOUtils;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.File;

public class ProfileEditorFragment extends Fragment implements CropperUtils.CropperListener{
    public static final String TAG = "ProfileEditorFragment";
    public static final String DELETED_PROFILE = "deleted_profile";

    private static final String TAG_ASYNC = "ProfileEditorAsync";

    private String mProfileKey;
    private MinecraftProfile mTempProfile = null;
    private String mValueToConsume = "";
    private Button mSaveButton, mDeleteButton, mControlSelectButton, mGameDirButton, mVersionSelectButton;
    private ImageButton mModsImport, mResourcePacksFolder, mShaderPacksFolder, mResourcePacksImport, mShaderPacksImport;
    private RecyclerView mModsRecycler, mResourcePacksRecycler, mShaderPacksRecycler;
    private TextView mModsHeader, mModsEmpty, mResourcePacksEmpty, mShaderPacksEmpty;
    private Spinner mDefaultRuntime, mDefaultRenderer;
    private EditText mDefaultName, mDefaultJvmArgument;
    private TextView mDefaultPath, mDefaultVersion, mDefaultControl;
    private ImageView mProfileIcon;
    private ImageView mProfileBackground;

    // ── CS Premium Studio additions ──
    private Button mCancelButton;
    private TextView mHdrName, mHdrVersion, mHdrLoader, mHdrRuntime, mHdrLastPlayed, mHdrSize;
    private View mScrollView;
    private SeekBar mRamSeekbar;
    private TextView mRamValue, mRamTotal;
    private boolean mDeleteArmed = false;
    private final Runnable mDeleteDisarm = () -> disarmDelete();
    private final int[] mNavIds = {
            R.id.vprof_nav_general, R.id.vprof_nav_java, R.id.vprof_nav_memory,
            R.id.vprof_nav_graphics, R.id.vprof_nav_mods, R.id.vprof_nav_rpacks,
            R.id.vprof_nav_shaders, R.id.vprof_nav_skin, R.id.vprof_nav_cape,
            R.id.vprof_nav_runtime, R.id.vprof_nav_advanced };
    private final int[] mSectionIds = {
            R.id.vprof_section_general, R.id.vprof_section_java, R.id.vprof_section_memory,
            R.id.vprof_section_graphics, R.id.vprof_section_mods, R.id.vprof_section_rpacks,
            R.id.vprof_section_shaders, R.id.vprof_section_skin, R.id.vprof_section_cape,
            R.id.vprof_section_runtime, R.id.vprof_section_advanced };
    private final CropperUtils.CropperListener mBackgroundCropperListener = new CropperUtils.CropperListener() {
        @Override
        public void onCropped(Bitmap contentBitmap) {
            if (mProfileBackground != null) mProfileBackground.setImageBitmap(contentBitmap);
            mBgExecutor.execute(() -> {
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                try (Base64OutputStream base64OutputStream = new Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP)) {
                    contentBitmap.compress(
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ?
                            Bitmap.CompressFormat.WEBP : Bitmap.CompressFormat.WEBP_LOSSY,
                        60, base64OutputStream);
                    base64OutputStream.flush();
                    byteArrayOutputStream.flush();
                } catch (IOException e) {
                    mMainHandler.post(() -> Tools.showErrorRemote(e));
                    return;
                }
                String iconLine = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
                String dataUri = "data:image/webp;base64," + iconLine;
                mEncodedBackgroundUri = dataUri;
                mMainHandler.post(() -> {
                    if (mTempProfile != null) mTempProfile.background = dataUri;
                });
            });
        }

        @Override
        public void onFailed(Exception exception) {
            Tools.showErrorRemote(exception);
        }
    };
    private final ActivityResultLauncher<?> mCropperLauncher = CropperUtils.registerCropper(this, this);
    private final ActivityResultLauncher<?> mBackgroundCropperLauncher = CropperUtils.registerCropper(this, mBackgroundCropperListener);

    private final ActivityResultLauncher<String> mModPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> handleImport(uri, "mods")
    );

    private final ActivityResultLauncher<String> mResourcePackPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> handleImport(uri, "resourcepacks")
    );

    private final ActivityResultLauncher<String> mShaderPackPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> handleImport(uri, "shaderpacks")
    );

    private List<String> mRenderNames;
    private volatile String mEncodedBackgroundUri = null;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mBgExecutor = PojavApplication.sExecutorService;
    private boolean mAsyncLoadComplete = false;

    public ProfileEditorFragment(){
        super(R.layout.fragment_profile_editor);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Paths, which can be changed
        String value = (String) ExtraCore.consumeValue(ExtraConstants.FILE_SELECTOR);
        if(value != null){
            if(mValueToConsume.equals(FileSelectorFragment.BUNDLE_SELECT_FOLDER)){
                mTempProfile.gameDir = value;
            }else{
                mTempProfile.controlFile = value;
            }
        }
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        bindViews(view);

        // Smooth 60/90 FPS layout animations
        View rootLayout = view.findViewById(R.id.fragment_profile_editor_root);
        if (rootLayout instanceof ViewGroup) {
            LayoutTransition transition = new LayoutTransition();
            transition.enableTransitionType(LayoutTransition.CHANGING);
            transition.setDuration(250);
            transition.setInterpolator(LayoutTransition.CHANGE_APPEARING,
                    new DecelerateInterpolator());
            ((ViewGroup) rootLayout).setLayoutTransition(transition);
        }

        // Hardware acceleration
        if (getActivity() != null) {
            getActivity().getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        }

        // Hardware layer for the entire content block → smooth 60fps animations
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Apply 200ms scale-up reveal with DecelerateInterpolator
        view.setAlpha(0f);
        view.setScaleX(0.96f);
        view.setScaleY(0.96f);
        view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (getView() != null) {
                        getView().setLayerType(View.LAYER_TYPE_NONE, null);
                    }
                })
                .start();

        // Renderer spinner setup (synchronous, fast — just string list)
        Tools.RenderersList renderersList = Tools.getCompatibleRenderers(view.getContext());
        mRenderNames = renderersList.rendererIds;
        List<String> renderList = new ArrayList<>(renderersList.rendererDisplayNames.length + 1);
        renderList.addAll(Arrays.asList(renderersList.rendererDisplayNames));
        renderList.add(view.getContext().getString(R.string.global_default));
        mDefaultRenderer.setAdapter(new ArrayAdapter<>(getContext(), R.layout.item_simple_list_1, renderList));

        // Set up behaviors
        mSaveButton.setOnClickListener(v -> {
            if (mTempProfile == null) return;
            // 1) Read inputs on the main thread (touching UI state)
            readInputsFromUi();
            // Apply pending background image from async encoding (race condition fix)
            String pendingBg = mEncodedBackgroundUri;
            if (pendingBg != null && mTempProfile != null) {
                mTempProfile.background = pendingBg;
                mEncodedBackgroundUri = null;
            }
            ProfileIconCache.dropIcon(mProfileKey);
            // 2) Disable button immediately to prevent double-tap
            mSaveButton.setEnabled(false);
            // 3) JSON write on background thread (expensive)
            mBgExecutor.execute(() -> {
                LauncherProfiles.mainProfileJson.profiles.put(mProfileKey, mTempProfile);
                LauncherProfiles.write();
                ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, mProfileKey);
                mMainHandler.post(() -> {
                    if (!isAdded()) return;
                    Fragment parentFrag = getParentFragment();
                    if (parentFrag instanceof MainMenuFragment) {
                        MainMenuFragment mmf = (MainMenuFragment) parentFrag;
                        mmf.clearRightPane();
                        mmf.reloadSpinner();
                    } else {
                        Tools.backToMainMenu(requireActivity());
                    }
                });
            });
        });

        mDeleteButton.setOnClickListener(v -> onDeleteClicked());


        View.OnClickListener gameDirListener = getGameDirListener();
        mGameDirButton.setOnClickListener(gameDirListener);
        mDefaultPath.setOnClickListener(gameDirListener);

        View.OnClickListener controlSelectListener = getControlSelectListener();
        mControlSelectButton.setOnClickListener(controlSelectListener);
        mDefaultControl.setOnClickListener(controlSelectListener);

        // Setup the expendable list behavior
        View.OnClickListener versionSelectListener = getVersionSelectListener();
        mVersionSelectButton.setOnClickListener(versionSelectListener);
        mDefaultVersion.setOnClickListener(versionSelectListener);

        // Set up the icon change click listener
        mProfileIcon.setOnClickListener(v -> CropperUtils.startCropper(mCropperLauncher));

        mResourcePacksFolder.setOnClickListener(v -> {
            File gameDir = Tools.getGameDirPath(mTempProfile);
            Tools.openPath(v.getContext(), new File(gameDir, "resourcepacks"), false);
        });

        mShaderPacksFolder.setOnClickListener(v -> {
            File gameDir = Tools.getGameDirPath(mTempProfile);
            Tools.openPath(v.getContext(), new File(gameDir, "shaderpacks"), false);
        });

        mModsImport = view.findViewById(R.id.vprof_editor_mods_import);
        mResourcePacksImport = view.findViewById(R.id.vprof_editor_resource_packs_import);
        mShaderPacksImport = view.findViewById(R.id.vprof_editor_shader_packs_import);

        mModsImport.setOnClickListener(v -> mModPicker.launch("*/*"));
        mResourcePacksImport.setOnClickListener(v -> mResourcePackPicker.launch("*/*"));
        mShaderPacksImport.setOnClickListener(v -> mShaderPackPicker.launch("*/*"));

        loadValuesAsync(LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, ""), view.getContext());
    }

    private void handleImport(Uri uri, String subDir) {
        if (uri == null) return;
        mBgExecutor.execute(() -> {
            try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
                File gameDir = Tools.getGameDirPath(mTempProfile);
                File destDir = new File(gameDir, subDir);
                if (!destDir.exists()) destDir.mkdirs();

                String fileName = Tools.getFileName(requireContext(), uri);
                if (fileName == null) fileName = "imported_" + System.currentTimeMillis() + ".zip";

                File destFile = new File(destDir, fileName);
                try (FileOutputStream os = new FileOutputStream(destFile)) {
                    IOUtils.copy(is, os);
                }
                mMainHandler.post(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(getContext(), "Imported successfully!", Toast.LENGTH_SHORT).show();
                    setupPacksListsAsync();
                });
            } catch (Exception e) {
                mMainHandler.post(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(getContext(), "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** Navigate to a fragment — stays inside the right pane when running as a child fragment. */
    private void navigateToFragment(Class<? extends Fragment> fragmentClass, String tag, Bundle args) {
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).openChildPane(fragmentClass, tag, args);
        } else {
            Tools.swapFragment(requireActivity(), fragmentClass, tag, args);
        }
    }

    private View.OnClickListener getGameDirListener() {
        return v -> {
            Bundle bundle = new Bundle(2);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SELECT_FOLDER, true);
            bundle.putString(FileSelectorFragment.BUNDLE_ROOT_PATH, Tools.DIR_GAME_HOME);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SHOW_FILE, false);
            mValueToConsume = FileSelectorFragment.BUNDLE_SELECT_FOLDER;

            navigateToFragment(FileSelectorFragment.class, FileSelectorFragment.TAG, bundle);
        };
    }

    private View.OnClickListener getControlSelectListener() {
        return v -> {
            Bundle bundle = new Bundle(3);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SELECT_FOLDER, false);
            bundle.putString(FileSelectorFragment.BUNDLE_ROOT_PATH, Tools.CTRLMAP_PATH);
            mValueToConsume = FileSelectorFragment.BUNDLE_SELECT_FILE;

            navigateToFragment(FileSelectorFragment.class, FileSelectorFragment.TAG, bundle);
        };
    }

    private View.OnClickListener getVersionSelectListener() {
        return v -> VersionSelectorDialog.open(v.getContext(), false, (id, snapshot)-> {
            mTempProfile.lastVersionId = id;
            mDefaultVersion.setText(id);
        });
    }

    /**
     * Loads profile values on a background thread to prevent main-thread jank
     * (file I/O, JSON parsing, runtime enumeration).
     */
    private void loadValuesAsync(@NonNull String profile, @NonNull Context context) {
        if (mTempProfile == null) {
            mTempProfile = getProfile(profile);
        }

        // Static UI population (cheap, on UI thread) — only string setters
        mProfileIcon.setImageDrawable(
                ProfileIconCache.fetchIcon(getResources(), mProfileKey, mTempProfile.icon)
        );
        mDefaultVersion.setText(mTempProfile.lastVersionId);
        mDefaultJvmArgument.setText(mTempProfile.javaArgs == null ? "" : mTempProfile.javaArgs);
        mDefaultName.setText(mTempProfile.name);
        mDefaultPath.setText(mTempProfile.gameDir == null ? "" : mTempProfile.gameDir);
        mDefaultControl.setText(mTempProfile.controlFile == null ? "" : mTempProfile.controlFile);

        // Per-profile background banner preview
        if (mTempProfile.background != null && mTempProfile.background.startsWith("data:")) {
            Drawable bg = ProfileIconCache.fetchBackground(getResources(), mProfileKey, mTempProfile.background);
            if (bg != null) mProfileBackground.setImageDrawable(bg);
        }

        // TODO: Remove this jank when it's not relevant anymore
        if ("vulkan_zink".equals(mTempProfile.pojavRendererName)) {
            mTempProfile.pojavRendererName = "opengles3_desktopgl_zink_kopper";
        }

        // All expensive work goes to background
        mBgExecutor.execute(() -> {
            // Runtime enumeration (file I/O over runtime dir)
            List<Runtime> runtimes = MultiRTUtils.getInstalledRuntimes();
            int jvmIdx = runtimes.indexOf(new Runtime("<Default>"));
            if (mTempProfile.javaDir != null) {
                String selectedRuntime = mTempProfile.javaDir.substring(Tools.LAUNCHERPROFILES_RTPREFIX.length());
                int nindex = runtimes.indexOf(new Runtime(selectedRuntime));
                if (nindex != -1) jvmIdx = nindex;
            }
            if (jvmIdx == -1) jvmIdx = runtimes.size() - 1;
            final int finalJvmIndex = jvmIdx;

            // Directory listings for mods / resourcepacks / shaderpacks
            File gameDir = Tools.getGameDirPath(mTempProfile);
            final File modsDir = new File(gameDir, "mods");
            final File resourcePacksDir = new File(gameDir, "resourcepacks");
            final File shaderPacksDir = new File(gameDir, "shaderpacks");

            mMainHandler.post(() -> {
                if (!isAdded()) return;
                mDefaultRuntime.setAdapter(new RTSpinnerAdapter(context, runtimes));
                mDefaultRuntime.setSelection(Math.max(0, finalJvmIndex));

                int rendererIdx = mDefaultRenderer.getAdapter().getCount() - 1;
                if (mTempProfile.pojavRendererName != null) {
                    int nindex = mRenderNames.indexOf(mTempProfile.pojavRendererName);
                    if (nindex != -1) rendererIdx = nindex;
                }
                final int finalRendererIndex = rendererIdx;

                mDefaultRenderer.setSelection(finalRendererIndex);

                bindPacksAdapters(modsDir, resourcePacksDir, shaderPacksDir);
                populateHeaderMeta();

                // Hide Mods section for Vanilla & OptiFine profiles only if no mod files exist.
                if (mTempProfile != null) {
                    boolean hideMods = (mTempProfile.isOptiFine() || mTempProfile.isVanilla()) && mTempProfile.getInstalledModsCount() == 0;
                    int visibility = hideMods ? View.GONE : View.VISIBLE;
                    if (mModsHeader != null) mModsHeader.setVisibility(visibility);
                    if (mModsImport != null) mModsImport.setVisibility(visibility);
                    if (mModsRecycler != null) mModsRecycler.setVisibility(visibility);
                    if (mModsEmpty != null) mModsEmpty.setVisibility(visibility);
                }
                mAsyncLoadComplete = true;
            });
        });
    }

    /**
     * Adapter binding only (directory listings were already collected on the
     * background thread — this avoids touching the filesystem from the UI thread).
     */
    private void bindPacksAdapters(File modsDir, File resourcePacksDir, File shaderPacksDir) {
        mModsRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        mModsRecycler.setItemAnimator(null);
        mModsRecycler.setAdapter(new InstalledModAdapter(modsDir, isEmpty ->
                mModsEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE)));

        mResourcePacksRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        mResourcePacksRecycler.setItemAnimator(null);
        mResourcePacksRecycler.setAdapter(new LocalPackAdapter(resourcePacksDir, isEmpty ->
                mResourcePacksEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE)));

        mShaderPacksRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        mShaderPacksRecycler.setItemAnimator(null);
        mShaderPacksRecycler.setAdapter(new LocalPackAdapter(shaderPacksDir, isEmpty ->
                mShaderPacksEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE)));
    }

    private void setupPacksListsAsync() {
        if (!mAsyncLoadComplete) return;
        mBgExecutor.execute(() -> {
            if (mTempProfile == null) return;
            File gameDir = Tools.getGameDirPath(mTempProfile);
            File modsDir = new File(gameDir, "mods");
            File resourcePacksDir = new File(gameDir, "resourcepacks");
            File shaderPacksDir = new File(gameDir, "shaderpacks");

            mMainHandler.post(() -> {
                if (!isAdded()) return;
                bindPacksAdapters(modsDir, resourcePacksDir, shaderPacksDir);
            });
        });
    }

    private MinecraftProfile getProfile(@NonNull String profile){
        MinecraftProfile minecraftProfile;
        if(getArguments() == null) {
            LauncherProfiles.load();
            MinecraftProfile originalProfile = LauncherProfiles.mainProfileJson.profiles.get(profile);
            if(originalProfile != null) minecraftProfile = new MinecraftProfile(originalProfile);
            else minecraftProfile = MinecraftProfile.createTemplate();
            mProfileKey = profile;
        }else{
            minecraftProfile = MinecraftProfile.createTemplate();
            mProfileKey = LauncherProfiles.getFreeProfileKey();
        }
        return minecraftProfile;
    }


    private void bindViews(@NonNull View view){
        mDefaultControl = view.findViewById(R.id.vprof_editor_ctrl_spinner);
        mDefaultRuntime = view.findViewById(R.id.vprof_editor_spinner_runtime);
        mDefaultRenderer = view.findViewById(R.id.vprof_editor_profile_renderer);
        mDefaultVersion = view.findViewById(R.id.vprof_editor_version_spinner);

        mDefaultPath = view.findViewById(R.id.vprof_editor_path);
        mDefaultName = view.findViewById(R.id.vprof_editor_profile_name);
        mDefaultJvmArgument = view.findViewById(R.id.vprof_editor_jre_args);

        mSaveButton = view.findViewById(R.id.vprof_editor_save_button);
        mDeleteButton = view.findViewById(R.id.vprof_editor_delete_button);
        mControlSelectButton = view.findViewById(R.id.vprof_editor_ctrl_button);
        mVersionSelectButton = view.findViewById(R.id.vprof_editor_version_button);
        mGameDirButton = view.findViewById(R.id.vprof_editor_path_button);
        mProfileIcon = view.findViewById(R.id.vprof_editor_profile_icon);
        mProfileBackground = view.findViewById(R.id.vprof_editor_background_preview);

        mModsHeader = view.findViewById(R.id.vprof_editor_mods_header);
        mResourcePacksFolder = view.findViewById(R.id.vprof_editor_resource_packs_folder);
        mShaderPacksFolder = view.findViewById(R.id.vprof_editor_shader_packs_folder);
        mModsRecycler = view.findViewById(R.id.vprof_editor_mods_recycler);
        mResourcePacksRecycler = view.findViewById(R.id.vprof_editor_resource_packs_recycler);
        mShaderPacksRecycler = view.findViewById(R.id.vprof_editor_shader_packs_recycler);
        mModsEmpty = view.findViewById(R.id.vprof_editor_mods_empty);
        mResourcePacksEmpty = view.findViewById(R.id.vprof_editor_resource_packs_empty);
        mShaderPacksEmpty = view.findViewById(R.id.vprof_editor_shader_packs_empty);
        mResourcePacksImport = view.findViewById(R.id.vprof_editor_resource_packs_import);
        mShaderPacksImport = view.findViewById(R.id.vprof_editor_shader_packs_import);

        // ── CS Premium Studio binds (null-safe for layout variants) ──
        mCancelButton = view.findViewById(R.id.vprof_editor_cancel_button);
        mHdrName = view.findViewById(R.id.vprof_hdr_name);
        mHdrVersion = view.findViewById(R.id.vprof_hdr_version_text);
        mHdrLoader = view.findViewById(R.id.vprof_hdr_loader_text);
        mHdrRuntime = view.findViewById(R.id.vprof_hdr_runtime_text);
        mHdrLastPlayed = view.findViewById(R.id.vprof_hdr_lastplayed_text);
        mHdrSize = view.findViewById(R.id.vprof_hdr_size_text);
        mScrollView = view.findViewById(R.id.vprof_studio_scroll);
        mRamSeekbar = view.findViewById(R.id.vprof_ram_seekbar);
        mRamValue = view.findViewById(R.id.vprof_ram_value);
        mRamTotal = view.findViewById(R.id.vprof_ram_total);

        // Background image change (separate from the profile icon) — GIF-aware:
        // animated GIFs bypass the cropper so their frames survive.
        view.findViewById(R.id.vprof_editor_background_button)
                .setOnClickListener(v -> mBackgroundImagePicker.launch(new String[]{"image/*"}));

        setupStudioNav(view);
        setupStudioButtons(view);
        setupRamSlider();
        setupHeaderWatchers();
        revealStudioCards(view);
    }

    /** GIF-aware background picker: GIFs stay animated, everything else is cropped. */
    private final ActivityResultLauncher<String[]> mBackgroundImagePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                android.content.Context context = getContext();
                if (context == null) return;
                if (uri == null) {
                    Toast.makeText(context, R.string.cropper_select_cancelled, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (isGifDocument(context, uri)) {
                    applyAnimatedBackground(context, uri);
                } else {
                    CropperUtils.openCropperDialog(context, uri, mBackgroundCropperListener);
                }
            });

    private static boolean isGifDocument(android.content.Context context, android.net.Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            byte[] head = new byte[3];
            if (in == null || in.read(head) < 3) return false;
            return head[0] == 'G' && head[1] == 'I' && head[2] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    /** Store the raw GIF as a data URI (no re-encode) and preview it animating. */
    private void applyAnimatedBackground(android.content.Context context, android.net.Uri uri) {
        mBgExecutor.execute(() -> {
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int read;
                long total = 0;
                while ((read = in.read(buf)) != -1) {
                    raw.write(buf, 0, read);
                    total += read;
                    if (total > 15L * 1024 * 1024) throw new java.io.IOException("GIF too large");
                }
                byte[] gifBytes = raw.toByteArray();
                String dataUri = "data:image/gif;base64,"
                        + android.util.Base64.encodeToString(gifBytes, android.util.Base64.NO_WRAP);
                android.graphics.drawable.Drawable preview =
                        net.kdt.pojavlaunch.profiles.ProfileGifSupport.buildGifDrawable(gifBytes);
                mEncodedBackgroundUri = dataUri;
                mMainHandler.post(() -> {
                    if (mTempProfile != null) mTempProfile.background = dataUri;
                    if (preview != null && mProfileBackground != null) {
                        net.kdt.pojavlaunch.profiles.ProfileGifSupport.stopDrawable(mProfileBackground.getDrawable());
                        mProfileBackground.setImageDrawable(preview);
                    }
                });
            } catch (Exception e) {
                mMainHandler.post(() -> Tools.showErrorRemote(e));
            }
        });
    }

    private void readInputsFromUi() {
        if (mTempProfile == null) return;
        mTempProfile.lastVersionId = mDefaultVersion.getText().toString();
        mTempProfile.controlFile = mDefaultControl.getText().toString();
        mTempProfile.name = mDefaultName.getText().toString();
        mTempProfile.javaArgs = mDefaultJvmArgument.getText().toString()
                .replaceAll("[\r\n]+", " ")
                .trim();
        mTempProfile.gameDir = mDefaultPath.getText().toString();

        if(mTempProfile.controlFile != null && mTempProfile.controlFile.isEmpty()) mTempProfile.controlFile = null;
        if(mTempProfile.javaArgs != null && mTempProfile.javaArgs.isEmpty()) mTempProfile.javaArgs = null;
        if(mTempProfile.gameDir != null && mTempProfile.gameDir.isEmpty()) mTempProfile.gameDir = null;

        if (mDefaultRuntime.getSelectedItem() instanceof Runtime) {
            Runtime selectedRuntime = (Runtime) mDefaultRuntime.getSelectedItem();
            mTempProfile.javaDir = (selectedRuntime.name.equals("<Default>") || selectedRuntime.versionString == null)
                    ? null : Tools.LAUNCHERPROFILES_RTPREFIX + selectedRuntime.name;
        }

        if(mDefaultRenderer.getSelectedItemPosition() == mRenderNames.size()) mTempProfile.pojavRendererName = null;
        else mTempProfile.pojavRendererName = mRenderNames.get(mDefaultRenderer.getSelectedItemPosition());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mProfileKey != null) {
            ProfileIconCache.dropIcon(mProfileKey);
            Fragment parent = getParentFragment();
            if (parent instanceof MainMenuFragment) {
                ((MainMenuFragment) parent).reloadSpinner();
            }
        }
    }

    @Override
    public void onCropped(Bitmap contentBitmap) {
        mProfileIcon.setImageBitmap(contentBitmap);
        Log.i(TAG_ASYNC, "w="+contentBitmap.getWidth() +" h="+contentBitmap.getHeight());
        mBgExecutor.execute(() -> {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try (Base64OutputStream base64OutputStream = new Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP)) {
                contentBitmap.compress(
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.R ?
                        Bitmap.CompressFormat.WEBP:
                        Bitmap.CompressFormat.WEBP_LOSSY,
                    60,
                    base64OutputStream
                );
                base64OutputStream.flush();
                byteArrayOutputStream.flush();
            }catch (IOException e) {
                mMainHandler.post(() -> Tools.showErrorRemote(e));
                return;
            }
            String iconLine = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
            String dataUri = "data:image/webp;base64," + iconLine;
            mMainHandler.post(() -> {
                if (mTempProfile != null) mTempProfile.icon = dataUri;
            });
        });
    }

    @Override
    public void onFailed(Exception exception) {
        Tools.showErrorRemote(exception);
    }

    // ════════════════════════════════════════════════════════════════
    // CS PREMIUM STUDIO — wiring for the redesigned header / nav rail /
    // bottom action bar / memory card.
    // ════════════════════════════════════════════════════════════════

    /** Two-step premium delete: first tap arms the button (solid danger +
        wiggle + label change), second tap actually deletes. Auto-disarms. */
    private void onDeleteClicked() {
        if (!mDeleteArmed) {
            armDelete();
            return;
        }
        disarmDelete();
        runDeleteProfile();
    }

    private void armDelete() {
        if (mDeleteButton == null) return;
        mDeleteArmed = true;
        mDeleteButton.setText(R.string.cs_confirm_delete);
        mDeleteButton.setBackgroundResource(R.drawable.bg_cs_danger_solid_button);
        mDeleteButton.animate().cancel();
        mDeleteButton.setScaleX(0.94f);
        mDeleteButton.setScaleY(0.94f);
        mDeleteButton.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new OvershootInterpolator(2.4f)).start();
        mMainHandler.removeCallbacks(mDeleteDisarm);
        mMainHandler.postDelayed(mDeleteDisarm, 3500);
    }

    private void disarmDelete() {
        if (mDeleteButton == null || !mDeleteArmed) return;
        mDeleteArmed = false;
        mDeleteButton.setText(R.string.global_delete);
        mDeleteButton.setBackgroundResource(R.drawable.bg_cs_danger_button);
    }

    /** Original Pojav delete flow, unchanged aside from the confirm gate. */
    private void runDeleteProfile() {
        if(LauncherProfiles.mainProfileJson.profiles.size() > 1){
            ProfileIconCache.dropIcon(mProfileKey);
            mDeleteButton.setEnabled(false);
            mBgExecutor.execute(() -> {
                LauncherProfiles.mainProfileJson.profiles.remove(mProfileKey);
                LauncherProfiles.write();
                try {
                    net.kdt.pojavlaunch.shortcuts.ProfileShortcutHelper
                            .removeShortcutsForProfile(requireContext(), mProfileKey);
                } catch (Exception ignored) {}
                ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, ProfileEditorFragment.DELETED_PROFILE);
                mMainHandler.post(() -> {
                    if (!isAdded()) return;
                    Fragment parentFrag = getParentFragment();
                    if (parentFrag instanceof MainMenuFragment) {
                        MainMenuFragment mmf = (MainMenuFragment) parentFrag;
                        mmf.clearRightPane();
                        mmf.reloadSpinner();
                    } else {
                        Tools.removeCurrentFragment(requireActivity());
                    }
                });
            });
        } else {
            Fragment parentFrag = getParentFragment();
            if (parentFrag instanceof MainMenuFragment) {
                ((MainMenuFragment) parentFrag).clearRightPane();
            } else {
                Tools.removeCurrentFragment(requireActivity());
            }
        }
    }

    /** Cancel = leave the Studio without persisting (mirrors back navigation). */
    private void onCancelClicked() {
        Fragment parentFrag = getParentFragment();
        if (parentFrag instanceof MainMenuFragment) {
            ((MainMenuFragment) parentFrag).clearRightPane();
        } else {
            Tools.removeCurrentFragment(requireActivity());
        }
    }

    private void setupStudioButtons(@NonNull View root) {
        if (mCancelButton != null) mCancelButton.setOnClickListener(v -> onCancelClicked());
        net.kdt.pojavlaunch.UiMotion.pressFeedback(
                mCancelButton, mSaveButton, mDeleteButton);
        View skins = root.findViewById(R.id.vprof_btn_open_skins);
        if (skins != null) skins.setOnClickListener(v ->
                navigateToFragment(SkinManagerFragment.class, SkinManagerFragment.TAG, null));
        View capes = root.findViewById(R.id.vprof_btn_open_capes);
        if (capes != null) capes.setOnClickListener(v ->
                navigateToFragment(SkinManagerFragment.class, SkinManagerFragment.TAG, null));
    }

    /** Section rail: tap → animated selection + smooth scroll to the card. */
    private void setupStudioNav(@NonNull View root) {
        for (int i = 0; i < mNavIds.length; i++) {
            final int idx = i;
            View nav = root.findViewById(mNavIds[i]);
            if (nav == null) continue;
            nav.setOnClickListener(v -> {
                selectNav(root, idx);
                View section = root.findViewById(mSectionIds[idx]);
                if (section != null
                        && mScrollView instanceof androidx.core.widget.NestedScrollView) {
                    ((androidx.core.widget.NestedScrollView) mScrollView)
                            .smoothScrollTo(0, Math.max(0, section.getTop() - 8));
                }
            });
        }
        selectNav(root, 0);
    }

    private void selectNav(@NonNull View root, int selected) {
        for (int i = 0; i < mNavIds.length; i++) {
            View nav = root.findViewById(mNavIds[i]);
            if (nav == null) continue;
            boolean active = i == selected;
            if (nav.isSelected() != active) {
                nav.setSelected(active);
            }
            if (active) {
                nav.animate().cancel();
                nav.setScaleX(0.92f); nav.setScaleY(0.92f);
                nav.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(new OvershootInterpolator(1.8f)).start();
            }
        }
    }

    /** Global RAM slider: wired to LauncherPreferences "allocation" (MB). */
    private void setupRamSlider() {
        if (mRamSeekbar == null || getContext() == null) return;
        long totalMb = 4096;
        try {
            ActivityManager am = (ActivityManager) requireContext().getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            totalMb = mi.totalMem / (1024 * 1024);
        } catch (Exception ignored) {}
        int maxMb = (int) Math.min(totalMb, 16384);
        maxMb = Math.max(1024, (maxMb / 128) * 128);
        mRamSeekbar.setMax(maxMb);

        int current = LauncherPreferences.PREF_RAM_ALLOCATION;
        if (current <= 0) current = 2048;
        mRamSeekbar.setProgress(Math.min(current, maxMb));
        if (mRamValue != null) mRamValue.setText(mRamSeekbar.getProgress() + " MB");
        if (mRamTotal != null) mRamTotal.setText("Device: " + (totalMb / 1024f >= 1f
                ? String.format(java.util.Locale.US, "%.0f", totalMb / 1024f) : String.valueOf(totalMb)) + " GB");

        mRamSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int snapped = Math.max(256, (progress / 128) * 128);
                if (fromUser && snapped != progress) s.setProgress(snapped);
                if (mRamValue != null) mRamValue.setText(snapped + " MB");
                LauncherPreferences.PREF_RAM_ALLOCATION = snapped;
                LauncherPreferences.DEFAULT_PREF.edit().putInt("allocation", snapped).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    /** Header chips track the editor fields live. */
    private void setupHeaderWatchers() {
        if (mDefaultName != null) {
            mDefaultName.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    if (mHdrName != null) mHdrName.setText(s);
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
        if (mDefaultVersion != null) {
            mDefaultVersion.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    if (mHdrVersion != null) mHdrVersion.setText(s);
                    if (mHdrLoader != null) mHdrLoader.setText(resolveLoaderName(s));
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
        if (mDefaultRuntime != null) {
            mDefaultRuntime.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                    if (mHdrRuntime == null) return;
                    Object item = p.getItemAtPosition(pos);
                    if (item instanceof net.kdt.pojavlaunch.multirt.Runtime) {
                        String version = ((net.kdt.pojavlaunch.multirt.Runtime) item).versionString;
                        mHdrRuntime.setText(version != null && !version.isEmpty()
                                ? "Java " + version.replaceAll("[^0-9.].*$", "")
                                : ((net.kdt.pojavlaunch.multirt.Runtime) item).name);
                    } else {
                        mHdrRuntime.setText(R.string.global_default);
                    }
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
            });
        }
    }

    private static String resolveLoaderName(CharSequence versionId) {
        if (versionId == null) return "Vanilla";
        String v = versionId.toString().toLowerCase(java.util.Locale.US);
        if (v.contains("neoforge")) return "NeoForge";
        if (v.contains("forge")) return "Forge";
        if (v.contains("fabric")) return "Fabric";
        if (v.contains("quilt")) return "Quilt";
        if (v.contains("liteloader")) return "LiteLoader";
        if (v.contains("optifine")) return "OptiFine";
        return "Vanilla";
    }

    /** Fill the hero header after async profile load (chips + dir size). */
    private void populateHeaderMeta() {
        if (mTempProfile == null) return;
        if (mHdrName != null) mHdrName.setText(mTempProfile.name);
        if (mHdrVersion != null) mHdrVersion.setText(mTempProfile.lastVersionId);
        if (mHdrLoader != null) mHdrLoader.setText(resolveLoaderName(mTempProfile.lastVersionId));

        if (mHdrLastPlayed != null) {
            String rel = formatRelativeTime(mTempProfile.lastUsed);
            if (rel != null) mHdrLastPlayed.setText("Played " + rel);
            else mHdrLastPlayed.setVisibility(View.GONE);
        }

        if (mHdrSize != null) {
            mHdrSize.setText("…");
            mBgExecutor.execute(() -> {
                long bytes = 0;
                try {
                    File dir = Tools.getGameDirPath(mTempProfile);
                    bytes = folderSizeCapped(dir, 0);
                } catch (Throwable ignored) {}
                final long total = bytes;
                mMainHandler.post(() -> {
                    if (mHdrSize == null || !isAdded()) return;
                    mHdrSize.setText(total <= 0 ? "Empty" : formatBytes(total));
                });
            });
        }
    }

    private static long folderSizeCapped(File dir, int depth) {
        if (dir == null || depth > 6) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long sum = 0;
        for (File f : files) {
            if (f == null) continue;
            if (f.isFile()) sum += f.length();
            else if (f.isDirectory()) sum += folderSizeCapped(f, depth + 1);
            if (sum > 60L * 1024 * 1024 * 1024) return sum; // cap runaway walks
        }
        return sum;
    }

    private static String formatBytes(long bytes) {
        double gb = bytes / (1024.0 * 1024 * 1024);
        if (gb >= 1) return String.format(java.util.Locale.US, "%.1f GB", gb);
        double mb = bytes / (1024.0 * 1024);
        return String.format(java.util.Locale.US, "%.0f MB", Math.max(1, mb));
    }

    private static String formatRelativeTime(String isoDate) {
        if (isoDate == null || isoDate.isEmpty()) return null;
        try {
            // launcher_profiles uses ISO-8601 (e.g. 2026-07-30T12:04:11.000Z)
            java.time.Instant then = java.time.Instant.parse(isoDate);
            long secs = java.time.Instant.now().getEpochSecond() - then.getEpochSecond();
            if (secs < 0) secs = 0;
            long days = secs / 86400;
            if (days >= 1) return days + "d ago";
            long hours = secs / 3600;
            if (hours >= 1) return hours + "h ago";
            long mins = secs / 60;
            return mins >= 1 ? mins + "m ago" : "just now";
        } catch (Throwable t) {
            return null;
        }
    }

    /** Staggered premium entrance for all section cards (60fps, one-shot). */
    private void revealStudioCards(@NonNull View root) {
        int delay = 0;
        for (int id : mSectionIds) {
            View card = root.findViewById(id);
            if (card == null) continue;
            card.setAlpha(0f);
            card.setTranslationY(28f);
            card.animate().alpha(1f).translationY(0f)
                    .setStartDelay(90 + delay)
                    .setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .start();
            delay += 45;
        }
    }
}