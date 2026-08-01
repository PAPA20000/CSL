package net.kdt.pojavlaunch.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.client.ClientFeature;
import net.kdt.pojavlaunch.utils.SkinFetchUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.yggdrasil.SkinAnalyzer;
import net.kdt.pojavlaunch.yggdrasil.SkinModelType;
import net.kdt.pojavlaunch.yggdrasil.PlayerSkin;
import net.kdt.pojavlaunch.yggdrasil.PlayerCape;
import net.kdt.pojavlaunch.yggdrasil.LocalUuidUtils;
import net.kdt.pojavlaunch.yggdrasil.LocalYggdrasilServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class SkinManagerFragment extends Fragment {

    public static final String TAG = "SKIN_MANAGER_FRAGMENT";
    private static final int REQUEST_CODE_SKIN = 1001;
    private static final int REQUEST_CODE_CAPE = 1002;
    private static final float PREVIEW_MODEL_HALF_HEIGHT = 16.0f;
    private static final float PREVIEW_FIT_MARGIN = 1.13f;
    private static final float DEFAULT_PREVIEW_ZOOM = 1.0f;
    private static final float DEFAULT_PREVIEW_YAW = 18f;
    private static final float DEFAULT_PREVIEW_PITCH = -4f;
    private static final float MIN_PREVIEW_ZOOM = 0.75f;
    private static final float MAX_PREVIEW_ZOOM = 1.60f;

    private GLSurfaceView mSkinPreviewSurface;
    private TextView mTvSkinPath;
    private TextView mTvCapePath;
    private TextView mTvSkinStatusChip;
    private TextView mTvCapeStatusChip;
    private TextView mTvServerStatusChip;
    private TextView mTvPreviewHint;
    private EditText mEtUsername;
    private Button mBtnFetch;

    private String mPendingSkinUri;
    private String mPendingCapeUri;

    private SkinRenderer mSkinRenderer;
    private ScaleGestureDetector mScaleGestureDetector;
    private GestureDetector mGestureDetector;
    private final Handler mAutoRotateHandler = new Handler(Looper.getMainLooper());
    private final Runnable mAutoRotateRunnable = new Runnable() {
        @Override
        public void run() {
            if (mSkinRenderer != null && mSkinRenderer.mAutoRotate && isAdded()) {
                mSkinPreviewSurface.requestRender();
                mAutoRotateHandler.postDelayed(this, 33);
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_skin_manager, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        MinecraftAccount activeAccount = net.kdt.pojavlaunch.PojavProfile.getCurrentProfileContent(requireContext(), null);
        if (activeAccount == null) {
            Tools.dialog(requireContext(), "Authentication Required", "Please log in or create an account first.");
            getParentFragmentManager().popBackStack();
            return;
        }

        // Unified skin/cape system is driven by CS CLIENT — gate the old
        // launcher skin service behind the Client Feature.
        if (!ClientFeature.isEnabled(requireContext())) {
            Tools.dialog(requireContext(), "CS CLIENT required",
                    "Please enable Client Feature to use custom skins and capes.");
            getParentFragmentManager().popBackStack();
            return;
        }

        mSkinPreviewSurface = view.findViewById(R.id.skin_preview_surface);
        mTvSkinPath = view.findViewById(R.id.tv_skin_path);
        mTvCapePath = view.findViewById(R.id.tv_cape_path);
        mTvSkinStatusChip = view.findViewById(R.id.tv_skin_status_chip);
        mTvCapeStatusChip = view.findViewById(R.id.tv_cape_status_chip);
        mTvServerStatusChip = view.findViewById(R.id.tv_server_status_chip);
        mTvPreviewHint = view.findViewById(R.id.tv_preview_hint);
        mEtUsername = view.findViewById(R.id.et_skin_username);
        mBtnFetch = view.findViewById(R.id.btn_fetch_skin);

        View backButton = view.findViewById(R.id.skin_back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> getParentFragmentManager().popBackStack());
        }

        mSkinPreviewSurface.setEGLContextClientVersion(2);
        mSkinRenderer = new SkinRenderer(requireContext());
        mSkinRenderer.mZoomFactor = DEFAULT_PREVIEW_ZOOM;
        mSkinRenderer.mAngleX = DEFAULT_PREVIEW_YAW;
        mSkinRenderer.mAngleY = DEFAULT_PREVIEW_PITCH;
        mSkinPreviewSurface.setRenderer(mSkinRenderer);
        mSkinPreviewSurface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setupPreviewGestures();

        File skinsDir = new File(Tools.DIR_DATA + "/skins");
        File capesDir = new File(Tools.DIR_DATA + "/capes");
        if (!skinsDir.exists()) skinsDir.mkdirs();
        if (!capesDir.exists()) capesDir.mkdirs();

        File localSkinFile = new File(skinsDir, activeAccount.username + "_skin.png");
        File localCapeFile = new File(capesDir, activeAccount.username + "_cape.png");

        mPendingSkinUri = localSkinFile.exists() ? Uri.fromFile(localSkinFile).toString() : null;
        mPendingCapeUri = localCapeFile.exists() ? Uri.fromFile(localCapeFile).toString() : null;

        updatePathText(mTvSkinPath, mPendingSkinUri, "No custom skin selected");
        updatePathText(mTvCapePath, mPendingCapeUri, "No custom cape selected");
        syncSelectionsFromClient();
        updateAccountInfo();

        view.findViewById(R.id.btn_change_skin).setOnClickListener(v -> openFilePicker(REQUEST_CODE_SKIN));
        view.findViewById(R.id.btn_remove_skin).setOnClickListener(v -> {
            mPendingSkinUri = null;
            updatePathText(mTvSkinPath, null, "No custom skin selected");
            updateAccountInfo();
            updatePreview();
        });
        view.findViewById(R.id.btn_reset_default).setOnClickListener(v -> {
            mPendingSkinUri = null;
            mPendingCapeUri = null;
            updatePathText(mTvSkinPath, null, "No custom skin selected");
            updatePathText(mTvCapePath, null, "No custom cape selected");
            updateAccountInfo();
            updatePreview();
        });
        view.findViewById(R.id.btn_change_cape).setOnClickListener(v -> openFilePicker(REQUEST_CODE_CAPE));
        view.findViewById(R.id.btn_remove_cape).setOnClickListener(v -> {
            mPendingCapeUri = null;
            updatePathText(mTvCapePath, null, "No custom cape selected");
            updateAccountInfo();
            updatePreview();
        });

        if (mBtnFetch != null) {
            mBtnFetch.setOnClickListener(v -> {
                String username = mEtUsername.getText().toString().trim();
                if (username.isEmpty()) return;
                fetchSkinFromUsername(username);
            });
        }

        view.findViewById(R.id.btn_save_skin_changes).setOnClickListener(v -> saveSkinChanges());

        resetPreviewCamera(false);
        updatePreview();
        animateEntry(view);
        applyInteractiveAnimations(view);
    }

    private void fetchSkinFromUsername(String username) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                File skinsDir = new File(Tools.DIR_DATA + "/skins");
                if (!skinsDir.exists()) skinsDir.mkdirs();
                File tempSkin = new File(skinsDir, "temp_fetch_skin.png");
                SkinFetchUtils.fetchAndSaveSkin(username, tempSkin);
                
                if (tempSkin.exists()) {
                    mAutoRotateHandler.post(() -> {
                        mPendingSkinUri = Uri.fromFile(tempSkin).toString();
                        updatePathText(mTvSkinPath, "Fetched: " + username, "No custom skin selected");
                        updatePreview();
                        updateAccountInfo();
                        Toast.makeText(requireContext(), "Skin fetched for " + username, Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                mAutoRotateHandler.post(() -> Toast.makeText(requireContext(), "Failed to fetch skin", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void saveSkinChanges() {
        MinecraftAccount acc = net.kdt.pojavlaunch.PojavProfile.getCurrentProfileContent(requireContext(), null);
        if (acc == null) return;
        try {
            if (mPendingSkinUri != null) {
                File destSkin = new File(Tools.DIR_DATA + "/skins/" + acc.username + "_skin.png");
                if (!mPendingSkinUri.equals(Uri.fromFile(destSkin).toString())) {
                    copyUriToFile(Uri.parse(mPendingSkinUri), destSkin);
                }
                File destSkinMeta = new File(Tools.DIR_DATA + "/skins/" + acc.username + "_metadata.json");
                Tools.write(destSkinMeta.getAbsolutePath(), "{\n  \"model\": \"default\"\n}");
            } else {
                new File(Tools.DIR_DATA + "/skins/" + acc.username + "_skin.png").delete();
                new File(Tools.DIR_DATA + "/skins/" + acc.username + "_metadata.json").delete();
            }
            if (mPendingCapeUri != null) {
                File destCape = new File(Tools.DIR_DATA + "/capes/" + acc.username + "_cape.png");
                if (!mPendingCapeUri.equals(Uri.fromFile(destCape).toString())) {
                    copyUriToFile(Uri.parse(mPendingCapeUri), destCape);
                }
            } else {
                new File(Tools.DIR_DATA + "/capes/" + acc.username + "_cape.png").delete();
            }
            boolean isSlimModel = false;
            String finalSkin = mPendingSkinUri != null ? new File(Tools.DIR_DATA + "/skins/" + acc.username + "_skin.png").getAbsolutePath() : null;
            String finalCape = mPendingCapeUri != null ? new File(Tools.DIR_DATA + "/capes/" + acc.username + "_cape.png").getAbsolutePath() : null;
            boolean hasCustomTextures = finalSkin != null || finalCape != null;
            String accUuid = LocalUuidUtils.generateProfileId(acc.username, SkinModelType.STEVE);
            if (hasCustomTextures && LocalYggdrasilServer.getPort() > 0) {
                LocalYggdrasilServer.registerProfile(acc.username, accUuid, finalSkin, finalCape, isSlimModel);
            } else if (!hasCustomTextures && LocalYggdrasilServer.getPort() > 0) {
                LocalYggdrasilServer.stop();
            }
            syncToClient(
                    finalSkin != null ? new File(Tools.DIR_DATA + "/skins/" + acc.username + "_skin.png") : null,
                    finalCape != null ? new File(Tools.DIR_DATA + "/capes/" + acc.username + "_cape.png") : null);
            acc.clearFaceCache();
            Toast.makeText(requireContext(), "Skin setup saved successfully!", Toast.LENGTH_SHORT).show();
            updateAccountInfo();
            if (getActivity() != null) {
                com.kdt.mcgui.mcAccountSpinner spinner = getActivity().findViewById(R.id.account_spinner);
                if (spinner != null) spinner.reloadAccounts(true, spinner.getSelectedItemPosition());
                if (getActivity() instanceof LauncherActivity) ((LauncherActivity) getActivity()).updateNavSkinIcon();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(requireContext(), "Failed to save: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Reads the selections CS CLIENT currently uses and mirrors them into the
     * launcher UI, so launcher ↔ client never drift apart.
     */
    private void syncSelectionsFromClient() {
        try {
            MinecraftProfile profile = ClientFeature.resolveClientProfile(requireContext());
            if (profile == null) return;
            File gameDir = Tools.getGameDirPath(profile);
            if (gameDir == null) return;

            String skinSel = ClientFeature.getSkinSelection(gameDir);
            if (skinSel != null) {
                File f = new File(ClientFeature.skinFolder(gameDir), skinSel);
                if (f.isFile()) {
                    mPendingSkinUri = Uri.fromFile(f).toString();
                    updatePathText(mTvSkinPath, mPendingSkinUri, "No custom skin selected");
                }
            }
            String capeSel = ClientFeature.getCapeSelection(gameDir);
            if (capeSel != null) {
                File f = new File(ClientFeature.capeFolder(gameDir), capeSel);
                if (f.isFile()) {
                    mPendingCapeUri = Uri.fromFile(f).toString();
                    updatePathText(mTvCapePath, mPendingCapeUri, "No custom cape selected");
                }
            }
            updatePreview();
        } catch (Exception e) {
            Log.w("SkinManager", "Failed to sync selections from client", e);
        }
    }

    /** Pushes the saved skin/cape into the CS CLIENT shared folders + config. */
    private void syncToClient(File skinFile, File capeFile) {
        try {
            MinecraftProfile profile = ClientFeature.resolveClientProfile(requireContext());
            if (profile == null) return;
            File gameDir = Tools.getGameDirPath(profile);
            if (gameDir == null) return;

            String skinName = null, capeName = null;
            if (skinFile != null && skinFile.isFile()) {
                File dir = ClientFeature.skinFolder(gameDir);
                dir.mkdirs();
                File dest = new File(dir, skinFile.getName());
                copyFile(skinFile, dest);
                skinName = dest.getName();
            }
            if (capeFile != null && capeFile.isFile()) {
                File dir = ClientFeature.capeFolder(gameDir);
                dir.mkdirs();
                File dest = new File(dir, capeFile.getName());
                copyFile(capeFile, dest);
                capeName = dest.getName();
            }
            ClientFeature.applySkinCape(gameDir, skinName, capeName, "wide");
            Toast.makeText(requireContext(), "Synced with CS CLIENT", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w("SkinManager", "Failed to sync skin/cape to client", e);
        }
    }

    private static void copyFile(File src, File dst) throws java.io.IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    private void animateEntry(@NonNull View root) {
        int[] ids = new int[]{R.id.skin_top_bar, R.id.skin_preview_card, R.id.skin_status_card, R.id.skin_fetch_card, R.id.skin_skin_card, R.id.skin_cape_card, R.id.skin_action_card};
        long delay = 0L;
        for (int id : ids) {
            View target = root.findViewById(id);
            if (target == null) continue;
            target.setAlpha(0f);
            target.setTranslationY(id == R.id.skin_top_bar ? -28f : 28f);
            target.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(320).start();
            delay += 70L;
        }
    }

    private void applyInteractiveAnimations(@NonNull View root) {
        int[] animatedButtons = new int[]{R.id.skin_back_button, R.id.btn_change_skin, R.id.btn_remove_skin, R.id.btn_reset_default, R.id.btn_change_cape, R.id.btn_remove_cape, R.id.btn_save_skin_changes, R.id.btn_fetch_skin};
        for (int id : animatedButtons) applyPressAnimation(root.findViewById(id));
    }

    private void applyPressAnimation(@Nullable View target) {
        if (target == null) return;
        target.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start(); break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: v.animate().scaleX(1f).scaleY(1f).setDuration(130).start(); break;
            }
            return false;
        });
    }

    private void setupPreviewGestures() {
        mScaleGestureDetector = new ScaleGestureDetector(requireContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (mSkinRenderer == null) return false;
                float nextZoom = mSkinRenderer.mZoomFactor * detector.getScaleFactor();
                mSkinRenderer.mZoomFactor = Math.max(MIN_PREVIEW_ZOOM, Math.min(MAX_PREVIEW_ZOOM, nextZoom));
                mSkinPreviewSurface.requestRender();
                return true;
            }
        });
        mGestureDetector = new GestureDetector(requireContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { resetPreviewCamera(true); return true; }
        });
        mSkinPreviewSurface.setOnTouchListener((v, event) -> {
            if (mScaleGestureDetector != null) mScaleGestureDetector.onTouchEvent(event);
            if (mGestureDetector != null) mGestureDetector.onTouchEvent(event);
            if (mSkinRenderer == null) return true;
            if (event.getPointerCount() == 1 && (mScaleGestureDetector == null || !mScaleGestureDetector.isInProgress())) {
                float x = event.getX(), y = event.getY();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: mSkinRenderer.mLastX = x; mSkinRenderer.mLastY = y; break;
                    case MotionEvent.ACTION_MOVE:
                        mSkinRenderer.mAngleX += (x - mSkinRenderer.mLastX) * 0.45f;
                        mSkinRenderer.mAngleY = Math.max(-30f, Math.min(30f, mSkinRenderer.mAngleY + (y - mSkinRenderer.mLastY) * 0.35f));
                        mSkinPreviewSurface.requestRender();
                        mSkinRenderer.mLastX = x; mSkinRenderer.mLastY = y;
                        break;
                }
            }
            return true;
        });
    }

    private void resetPreviewCamera(boolean animateSurface) {
        if (mSkinRenderer == null || mSkinPreviewSurface == null) return;
        mSkinRenderer.mAutoRotate = false;
        mSkinRenderer.mAngleX = DEFAULT_PREVIEW_YAW;
        mSkinRenderer.mAngleY = DEFAULT_PREVIEW_PITCH;
        mSkinRenderer.mZoomFactor = DEFAULT_PREVIEW_ZOOM;
        mAutoRotateHandler.removeCallbacks(mAutoRotateRunnable);
        mSkinPreviewSurface.requestRender();
        if (animateSurface) {
            mSkinPreviewSurface.animate().cancel();
            mSkinPreviewSurface.setScaleX(0.985f); mSkinPreviewSurface.setScaleY(0.985f);
            mSkinPreviewSurface.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
        }
    }

    private void updateAccountInfo() {
        boolean hasSkin = mPendingSkinUri != null, hasCape = mPendingCapeUri != null, serverWillRun = hasSkin || hasCape;
        updateStatusChip(mTvSkinStatusChip, hasSkin ? "CUSTOM SKIN" : "DEFAULT SKIN", hasSkin, null);
        updateStatusChip(mTvCapeStatusChip, hasCape ? "CUSTOM CAPE" : "NO CAPE", hasCape, null);
        updateStatusChip(mTvServerStatusChip, serverWillRun ? "SERVER AUTO ON" : "SERVER OFF", serverWillRun, null);
        if (mTvPreviewHint != null) mTvPreviewHint.setText(serverWillRun ? "Drag to rotate • Custom textures active" : "Drag to rotate • Default Minecraft look");
    }

    private void updateStatusChip(TextView view, String text, boolean active, String cd) {
        if (view == null) return;
        view.setText(text);
        view.setBackgroundResource(active ? R.drawable.bg_skin_status_chip_active : R.drawable.bg_skin_status_chip_inactive);
        view.setTextColor(active ? 0xFFEFFFFF : 0xFFBFD1E6);
    }

    private void copyUriToFile(Uri uri, File destFile) throws Exception {
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
             java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    private void openFilePicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        startActivityForResult(intent, requestCode);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            requireContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (requestCode == REQUEST_CODE_SKIN) mPendingSkinUri = uri.toString();
            else if (requestCode == REQUEST_CODE_CAPE) mPendingCapeUri = uri.toString();
            updatePathText(requestCode == REQUEST_CODE_SKIN ? mTvSkinPath : mTvCapePath, uri.toString(), "");
            updateAccountInfo(); updatePreview();
        }
    }

    private void updatePathText(TextView textView, String uriStr, String defaultText) {
        if (textView == null) return;
        if (uriStr != null) {
            Uri uri = Uri.parse(uriStr);
            textView.setText(uri.getLastPathSegment() != null ? uri.getLastPathSegment() : uriStr);
        } else textView.setText(defaultText);
    }

    private void updatePreview() {
        Bitmap skinBitmap = loadBitmapFromUri(mPendingSkinUri);
        if (skinBitmap == null) {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inScaled = false;
            skinBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.ic_steve, options);
        }
        Bitmap capeBitmap = loadBitmapFromUri(mPendingCapeUri);
        if (mSkinRenderer != null) {
            mSkinRenderer.setTexture(skinBitmap, capeBitmap);
            mSkinPreviewSurface.requestRender();
        }
    }

    private Bitmap loadBitmapFromUri(String uriStr) {
        if (uriStr == null) return null;
        try {
            Uri uri = Uri.parse(uriStr);
            try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
                if (is != null) return BitmapFactory.decodeStream(is);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static class SkinRenderer implements GLSurfaceView.Renderer {
        public float mAngleX = 0f, mAngleY = 0f, mZoomFactor = 1.0f, mLastX, mLastY;
        public boolean mAutoRotate = false, mIsSlim = false;
        private int mProgram, mPositionHandle, mTextureCoordHandle, mMVPMatrixHandle, mTextureUniformHandle;
        private final float[] mMVPMatrix = new float[16], mProjectionMatrix = new float[16], mViewMatrix = new float[16], mModelMatrix = new float[16];
        private Cuboid mHead, mHeadLayer, mTorso, mTorsoLayer, mRightArm, mRightArmLayer, mLeftArm, mLeftArmLayer, mRightLeg, mRightLegLayer, mLeftLeg, mLeftLegLayer, mCape;
        private Bitmap mPendingSkinBitmap, mPendingCapeBitmap;
        private int mSkinTextureId = 0, mCapeTextureId = 0;
        private boolean mSkinTextureNeedsUpdate = false, mCapeTextureNeedsUpdate = false;

        public SkinRenderer(Context context) {}
        public synchronized void setTexture(Bitmap skin, Bitmap cape) {
            mPendingSkinBitmap = skin; mPendingCapeBitmap = cape;
            mSkinTextureNeedsUpdate = true; mCapeTextureNeedsUpdate = true;
        }
        public void onPause() { mSkinTextureId = mCapeTextureId = 0; }
        @Override public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
            GLES20.glClearColor(0.05f, 0.06f, 0.08f, 1.0f); GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            int vs = loadShader(GLES20.GL_VERTEX_SHADER, "uniform mat4 uMVPMatrix; attribute vec4 aPosition; attribute vec2 aTextureCoord; varying vec2 vTextureCoord; void main() { gl_Position = uMVPMatrix * aPosition; vTextureCoord = aTextureCoord; }");
            int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; varying vec2 vTextureCoord; uniform sampler2D sTexture; void main() { vec4 color = texture2D(sTexture, vTextureCoord); if (color.a < 0.1) discard; gl_FragColor = color; }");
            mProgram = GLES20.glCreateProgram(); GLES20.glAttachShader(mProgram, vs); GLES20.glAttachShader(mProgram, fs); GLES20.glLinkProgram(mProgram);
            mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition"); mTextureCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
            mMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix"); mTextureUniformHandle = GLES20.glGetUniformLocation(mProgram, "sTexture");
        }
        @Override public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int w, int h) {
            GLES20.glViewport(0, 0, w, h); Matrix.orthoM(mProjectionMatrix, 0, -18f * (float)w/h, 18f * (float)w/h, -18f, 18f, 0.1f, 200f);
        }
        @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            synchronized (this) {
                if (mSkinTextureNeedsUpdate) { if (mSkinTextureId != 0) GLES20.glDeleteTextures(1, new int[]{mSkinTextureId}, 0); mSkinTextureId = loadGLTexture(mPendingSkinBitmap); mSkinTextureNeedsUpdate = false; }
                if (mCapeTextureNeedsUpdate) { if (mCapeTextureId != 0) GLES20.glDeleteTextures(1, new int[]{mCapeTextureId}, 0); mCapeTextureId = loadGLTexture(mPendingCapeBitmap); mCapeTextureNeedsUpdate = false; }
            }
            if (mSkinTextureId == 0) return;
            rebuildCuboids();
            Matrix.setLookAtM(mViewMatrix, 0, 0f, 0f, 34f, 0f, 0f, 0f, 0f, 1f, 0f); Matrix.setIdentityM(mModelMatrix, 0);
            Matrix.rotateM(mModelMatrix, 0, mAngleY, 1f, 0f, 0f); Matrix.rotateM(mModelMatrix, 0, mAngleX, 0f, 1f, 0f); Matrix.scaleM(mModelMatrix, 0, mZoomFactor, mZoomFactor, mZoomFactor);
            GLES20.glUseProgram(mProgram); GLES20.glDisable(GLES20.GL_BLEND);
            drawPart(mHead, mModelMatrix, mSkinTextureId); drawPart(mTorso, mModelMatrix, mSkinTextureId); drawPart(mRightArm, mModelMatrix, mSkinTextureId); drawPart(mLeftArm, mModelMatrix, mSkinTextureId); drawPart(mRightLeg, mModelMatrix, mSkinTextureId); drawPart(mLeftLeg, mModelMatrix, mSkinTextureId);
            if (mCape != null) { float[] cm = new float[16]; System.arraycopy(mModelMatrix, 0, cm, 0, 16); Matrix.translateM(cm, 0, 0f, 8f, -2f); Matrix.rotateM(cm, 0, 180f, 0f, 1f, 0f); Matrix.rotateM(cm, 0, -10f, 1f, 0f, 0f); drawPart(mCape, cm, mCapeTextureId); }
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            drawPart(mHeadLayer, mModelMatrix, mSkinTextureId); drawPart(mTorsoLayer, mModelMatrix, mSkinTextureId); drawPart(mRightArmLayer, mModelMatrix, mSkinTextureId); drawPart(mLeftArmLayer, mModelMatrix, mSkinTextureId); drawPart(mRightLegLayer, mModelMatrix, mSkinTextureId); drawPart(mLeftLegLayer, mModelMatrix, mSkinTextureId);
        }
        private void rebuildCuboids() {
            if (mHead != null) return;
            mHead = new Cuboid(0, 8, 0, -4, 4, 0, 8, -4, 4, 0, 0, 8, 8, 8, 64, 64, false, 0f);
            mHeadLayer = new Cuboid(0, 8, 0, -4, 4, 0, 8, -4, 4, 32, 0, 8, 8, 8, 64, 64, false, 0.5f);
            mTorso = new Cuboid(0, 8, 0, -4, 4, -12, 0, -2, 2, 16, 16, 8, 12, 4, 64, 64, false, 0f);
            mTorsoLayer = new Cuboid(0, 8, 0, -4, 4, -12, 0, -2, 2, 16, 32, 8, 12, 4, 64, 64, false, 0.25f);
            mRightArm = new Cuboid(-6, 8, 0, -2, 2, -12, 0, -2, 2, 40, 16, 4, 12, 4, 64, 64, false, 0f);
            mRightArmLayer = new Cuboid(-6, 8, 0, -2, 2, -12, 0, -2, 2, 40, 32, 4, 12, 4, 64, 64, false, 0.25f);
            mLeftArm = new Cuboid(6, 8, 0, -2, 2, -12, 0, -2, 2, 32, 48, 4, 12, 4, 64, 64, false, 0f);
            mLeftArmLayer = new Cuboid(6, 8, 0, -2, 2, -12, 0, -2, 2, 48, 48, 4, 12, 4, 64, 64, false, 0.25f);
            mRightLeg = new Cuboid(-2, -4, 0, -2, 2, -12, 0, -2, 2, 0, 16, 4, 12, 4, 64, 64, false, 0f);
            mRightLegLayer = new Cuboid(-2, -4, 0, -2, 2, -12, 0, -2, 2, 0, 32, 4, 12, 4, 64, 64, false, 0.25f);
            mLeftLeg = new Cuboid(2, -4, 0, -2, 2, -12, 0, -2, 2, 16, 48, 4, 12, 4, 64, 64, false, 0f);
            mLeftLegLayer = new Cuboid(2, -4, 0, -2, 2, -12, 0, -2, 2, 0, 48, 4, 12, 4, 64, 64, false, 0.25f);
            mCape = new Cuboid(0, 0, 0, -5, 5, -16, 0, 0, 1, 0, 0, 10, 16, 1, 64, 32, true, 0f);
        }
        private void drawPart(Cuboid c, float[] bm, int tid) {
            if (c == null || tid == 0) return; float[] mvp = new float[16], pm = new float[16], mv = new float[16];
            System.arraycopy(bm, 0, pm, 0, 16); Matrix.translateM(pm, 0, c.pX, c.pY, c.pZ);
            Matrix.multiplyMM(mv, 0, mViewMatrix, 0, pm, 0); Matrix.multiplyMM(mvp, 0, mProjectionMatrix, 0, mv, 0);
            GLES20.glEnableVertexAttribArray(mPositionHandle); GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 0, c.vertexBuffer);
            GLES20.glEnableVertexAttribArray(mTextureCoordHandle); GLES20.glVertexAttribPointer(mTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, c.uvBuffer);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tid);
            GLES20.glUniform1i(mTextureUniformHandle, 0); GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mvp, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);
        }
        private int loadShader(int t, String s) { int sh = GLES20.glCreateShader(t); GLES20.glShaderSource(sh, s); GLES20.glCompileShader(sh); return sh; }
        private int loadGLTexture(Bitmap b) { if (b == null) return 0; int[] t = new int[1]; GLES20.glGenTextures(1, t, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]); GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST); GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0); return t[0]; }
        private static class Cuboid {
            public FloatBuffer vertexBuffer, uvBuffer; public float pX, pY, pZ;
            public Cuboid(float px, float py, float pz, float x1, float x2, float y1, float y2, float z1, float z2, int us, int vs, int dx, int dy, int dz, int tw, int th, boolean m, float e) {
                pX=px; pY=py; pZ=pz; x1-=e; x2+=e; y1-=e; y2+=e; z1-=e; z2+=e;
                float[] v = new float[108], u = new float[72];
                addF(v, u, 0, 0, x1, y2, z2, x1, y1, z2, x2, y1, z2, x2, y2, z2, us+dz, vs+dz, dx, dy, tw, th, m);
                addF(v, u, 18, 12, x2, y2, z1, x2, y1, z1, x1, y1, z1, x1, y2, z1, us+dz+dx+dz, vs+dz, dx, dy, tw, th, m);
                addF(v, u, 36, 24, x1, y2, z1, x1, y1, z1, x1, y1, z2, x1, y2, z2, us, vs+dz, dz, dy, tw, th, m);
                addF(v, u, 54, 36, x2, y2, z2, x2, y1, z2, x2, y1, z1, x2, y2, z1, us+dz+dx, vs+dz, dz, dy, tw, th, m);
                addF(v, u, 72, 48, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, us+dz, vs, dx, dz, tw, th, m);
                addF(v, u, 90, 60, x1, y1, z2, x1, y1, z1, x2, y1, z1, x2, y1, z2, us+dz+dx, vs, dx, dz, tw, th, m);
                vertexBuffer = ByteBuffer.allocateDirect(432).order(ByteOrder.nativeOrder()).asFloatBuffer().put(v); vertexBuffer.position(0);
                uvBuffer = ByteBuffer.allocateDirect(288).order(ByteOrder.nativeOrder()).asFloatBuffer().put(u); uvBuffer.position(0);
            }
            private void addF(float[] v, float[] u, int vi, int ui, float xA, float yA, float zA, float xB, float yB, float zB, float xC, float yC, float zC, float xD, float yD, float zD, int us, int vs, int dx, int dy, int tw, int th, boolean m) {
                v[vi]=xA; v[vi+1]=yA; v[vi+2]=zA; v[vi+3]=xB; v[vi+4]=yB; v[vi+5]=zB; v[vi+6]=xC; v[vi+7]=yC; v[vi+8]=zC; v[vi+9]=xA; v[vi+10]=yA; v[vi+11]=zA; v[vi+12]=xC; v[vi+13]=yC; v[vi+14]=zC; v[vi+15]=xD; v[vi+16]=yD; v[vi+17]=zD;
                float u1 = (float)us/tw, v1 = (float)vs/th, u2 = (float)(us+dx)/tw, v2 = (float)(vs+dy)/th; if (m) { float t=u1; u1=u2; u2=t; }
                u[ui]=u1; u[ui+1]=v1; u[ui+2]=u1; u[ui+3]=v2; u[ui+4]=u2; u[ui+5]=v2; u[ui+6]=u1; u[ui+7]=v1; u[ui+8]=u2; u[ui+9]=v2; u[ui+10]=u2; u[ui+11]=v1;
            }
        }
    }
}
