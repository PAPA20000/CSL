package net.kdt.pojavlaunch.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
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
import net.kdt.pojavlaunch.utils.SkinFetchUtils;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.yggdrasil.SkinAnalyzer;
import net.kdt.pojavlaunch.yggdrasil.SkinModelType;
import net.kdt.pojavlaunch.yggdrasil.PlayerSkin;
import net.kdt.pojavlaunch.yggdrasil.PlayerCape;
import net.kdt.pojavlaunch.yggdrasil.LocalUuidUtils;
import net.kdt.pojavlaunch.yggdrasil.LocalYggdrasilServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
        mSkinRenderer.mAutoRotate = true;
        mAutoRotateHandler.postDelayed(mAutoRotateRunnable, 500);
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
            String finalSkin = mPendingSkinUri != null ? new File(Tools.DIR_DATA + "/skins/" + acc.username + "_skin.png").getAbsolutePath() : null;
            String finalCape = mPendingCapeUri != null ? new File(Tools.DIR_DATA + "/capes/" + acc.username + "_cape.png").getAbsolutePath() : null;
            boolean isSlimModel = finalSkin != null && detectSlimModel(finalSkin);
            if (finalSkin != null) {
                File destSkinMeta = new File(Tools.DIR_DATA + "/skins/" + acc.username + "_metadata.json");
                Tools.write(destSkinMeta.getAbsolutePath(),
                        "{\n  \"model\": \"" + (isSlimModel ? "slim" : "default") + "\"\n}");
            }
            boolean hasCustomTextures = finalSkin != null || finalCape != null;
            String accUuid = LocalUuidUtils.generateProfileId(acc.username,
                    isSlimModel ? SkinModelType.ALEX : SkinModelType.STEVE);
            if (hasCustomTextures && LocalYggdrasilServer.getPort() > 0) {
                LocalYggdrasilServer.registerProfile(acc.username, accUuid, finalSkin, finalCape, isSlimModel);
                if (mSkinRenderer != null) mSkinRenderer.mIsSlim = isSlimModel;
            } else if (!hasCustomTextures && LocalYggdrasilServer.getPort() > 0) {
                LocalYggdrasilServer.stop();
            }
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
                    case MotionEvent.ACTION_DOWN:
                        mSkinRenderer.mAutoRotate = false;
                        mAutoRotateHandler.removeCallbacks(mAutoRotateRunnable);
                        mSkinRenderer.mLastX = x;
                        mSkinRenderer.mLastY = y;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        mSkinRenderer.mAngleX += (x - mSkinRenderer.mLastX) * 0.45f;
                        mSkinRenderer.mAngleY = Math.max(-30f, Math.min(30f, mSkinRenderer.mAngleY + (y - mSkinRenderer.mLastY) * 0.35f));
                        mSkinPreviewSurface.requestRender();
                        mSkinRenderer.mLastX = x; mSkinRenderer.mLastY = y;
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mAutoRotateHandler.removeCallbacks(mAutoRotateRunnable);
                        mAutoRotateHandler.postDelayed(() -> {
                            if (mSkinRenderer != null) {
                                mSkinRenderer.mAutoRotate = true;
                                mAutoRotateHandler.postDelayed(mAutoRotateRunnable, 33);
                            }
                        }, 1800);
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

    /** Pixel-alpha based Steve/Alex detection (64x64 vs legacy 64x32 safe). */
    private boolean detectSlimModel(@NonNull String skinPath) {
        Bitmap bmp = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;
            bmp = BitmapFactory.decodeFile(skinPath, opts);
            if (bmp == null) return false;
            final Bitmap source = bmp;
            return SkinAnalyzer.detectSkinModel(source.getHeight(),
                    (x, y) -> {
                        if (x < 0 || y < 0 || x >= source.getWidth() || y >= source.getHeight()) return 0;
                        return Color.alpha(source.getPixel(x, y));
                    }) == SkinModelType.ALEX;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
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
    public void onResume() {
        super.onResume();
        if (mSkinPreviewSurface != null) {
            mSkinPreviewSurface.onResume();
            if (mSkinRenderer != null && mSkinRenderer.mAutoRotate) {
                mAutoRotateHandler.removeCallbacks(mAutoRotateRunnable);
                mAutoRotateHandler.postDelayed(mAutoRotateRunnable, 33);
            }
        }
    }

    @Override
    public void onPause() {
        mAutoRotateHandler.removeCallbacks(mAutoRotateRunnable);
        if (mSkinPreviewSurface != null) mSkinPreviewSurface.onPause();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        mAutoRotateHandler.removeCallbacks(mAutoRotateRunnable);
        if (mSkinRenderer != null) mSkinRenderer.onPause();
        if (mSkinPreviewSurface != null) mSkinPreviewSurface.onPause();
        mSkinPreviewSurface = null;
        mSkinRenderer = null;
        super.onDestroyView();
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

    /** Premium GL preview renderer: lighting, shadow, cached geometry, slim/steve arms. */
    private static class SkinRenderer implements GLSurfaceView.Renderer {
        public float mAngleX = 0f, mAngleY = 0f, mZoomFactor = 1.0f, mLastX, mLastY;
        public boolean mAutoRotate = false, mIsSlim = false;

        private int mProgram, mPositionHandle, mTextureCoordHandle, mNormalHandle;
        private int mMVPMatrixHandle, mModelMatrixHandle, mTextureUniformHandle;
        private int mColorProgram, mColorPositionHandle, mColorMvpHandle, mColorUniformHandle;

        private final float[] mMVPMatrix = new float[16];
        private final float[] mProjectionMatrix = new float[16];
        private final float[] mViewMatrix = new float[16];
        private final float[] mModelMatrix = new float[16];
        private final float[] mPartModel = new float[16];
        private final float[] mMvScratch = new float[16];
        private final float[] mMvpScratch = new float[16];
        private final float[] mShadowModel = new float[16];

        private Cuboid mHead, mHeadLayer, mTorso, mTorsoLayer;
        private Cuboid mRightArm, mRightArmLayer, mLeftArm, mLeftArmLayer;
        private Cuboid mRightLeg, mRightLegLayer, mLeftLeg, mLeftLegLayer, mCape;
        private ShadowDisc mShadowDisc;

        private Bitmap mPendingSkinBitmap, mPendingCapeBitmap;
        private int mSkinTextureId = 0, mCapeTextureId = 0;
        private boolean mSkinTextureNeedsUpdate = false, mCapeTextureNeedsUpdate = false;

        public SkinRenderer(Context context) {}

        public synchronized void setTexture(Bitmap skin, Bitmap cape) {
            boolean slim = skin != null && detectSlim(skin);
            if (slim != mIsSlim) {
                mIsSlim = slim;
                clearCuboids();
            }
            mPendingSkinBitmap = skin;
            mPendingCapeBitmap = cape;
            mSkinTextureNeedsUpdate = true;
            mCapeTextureNeedsUpdate = true;
        }

        public void onPause() {
            // GL context is being torn down by GLSurfaceView; re-upload next frame.
            mSkinTextureId = 0;
            mCapeTextureId = 0;
            mSkinTextureNeedsUpdate = true;
            mCapeTextureNeedsUpdate = true;
        }

        private static boolean detectSlim(@androidx.annotation.NonNull Bitmap skin) {
            return SkinAnalyzer.detectSkinModel(skin.getHeight(), (x, y) -> {
                if (x < 0 || y < 0 || x >= skin.getWidth() || y >= skin.getHeight()) return 0;
                return Color.alpha(skin.getPixel(x, y));
            }) == SkinModelType.ALEX;
        }

        @Override
        public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,
                                     javax.microedition.khronos.egl.EGLConfig config) {
            GLES20.glClearColor(0.035f, 0.044f, 0.060f, 1.0f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glDepthFunc(GLES20.GL_LEQUAL);

            int vs = loadShader(GLES20.GL_VERTEX_SHADER,
                    "uniform mat4 uMVPMatrix;\n" +
                    "uniform mat4 uModelMatrix;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTextureCoord;\n" +
                    "attribute vec3 aNormal;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "varying vec3 vNormal;\n" +
                    "void main() {\n" +
                    "  gl_Position = uMVPMatrix * aPosition;\n" +
                    "  vTextureCoord = aTextureCoord;\n" +
                    "  vNormal = normalize((uModelMatrix * vec4(aNormal, 0.0)).xyz);\n" +
                    "}\n");
            int fs = loadShader(GLES20.GL_FRAGMENT_SHADER,
                    "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "varying vec3 vNormal;\n" +
                    "uniform sampler2D sTexture;\n" +
                    "void main() {\n" +
                    "  vec4 color = texture2D(sTexture, vTextureCoord);\n" +
                    "  if (color.a < 0.08) discard;\n" +
                    "  vec3 lightDir = normalize(vec3(-0.38, 0.82, 0.42));\n" +
                    "  float diffuse = max(dot(normalize(vNormal), lightDir), 0.0);\n" +
                    "  float shade = 0.58 + diffuse * 0.42;\n" +
                    "  gl_FragColor = vec4(color.rgb * shade, color.a);\n" +
                    "}\n");
            mProgram = linkProgram(vs, fs);
            mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            mTextureCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
            mNormalHandle = GLES20.glGetAttribLocation(mProgram, "aNormal");
            mMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
            mModelMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uModelMatrix");
            mTextureUniformHandle = GLES20.glGetUniformLocation(mProgram, "sTexture");

            int cvs = loadShader(GLES20.GL_VERTEX_SHADER,
                    "uniform mat4 uMVPMatrix; attribute vec4 aPosition;\n" +
                    "void main() { gl_Position = uMVPMatrix * aPosition; }\n");
            int cfs = loadShader(GLES20.GL_FRAGMENT_SHADER,
                    "precision mediump float; uniform vec4 uColor;\n" +
                    "void main() { gl_FragColor = uColor; }\n");
            mColorProgram = linkProgram(cvs, cfs);
            mColorPositionHandle = GLES20.glGetAttribLocation(mColorProgram, "aPosition");
            mColorMvpHandle = GLES20.glGetUniformLocation(mColorProgram, "uMVPMatrix");
            mColorUniformHandle = GLES20.glGetUniformLocation(mColorProgram, "uColor");
        }

        @Override
        public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int w, int h) {
            GLES20.glViewport(0, 0, w, h);
            Matrix.orthoM(mProjectionMatrix, 0,
                    -18f * (float) w / h, 18f * (float) w / h,
                    -18f, 18f, 0.1f, 200f);
        }

        @Override
        public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            synchronized (this) {
                if (mSkinTextureNeedsUpdate) {
                    if (mSkinTextureId != 0) GLES20.glDeleteTextures(1, new int[]{mSkinTextureId}, 0);
                    mSkinTextureId = loadGLTexture(mPendingSkinBitmap);
                    mSkinTextureNeedsUpdate = false;
                }
                if (mCapeTextureNeedsUpdate) {
                    if (mCapeTextureId != 0) GLES20.glDeleteTextures(1, new int[]{mCapeTextureId}, 0);
                    mCapeTextureId = loadGLTexture(mPendingCapeBitmap);
                    mCapeTextureNeedsUpdate = false;
                }
            }
            if (mSkinTextureId == 0) return;

            if (mAutoRotate) mAngleX = (mAngleX + 0.34f) % 360f;
            rebuildCuboidsIfNeeded();

            Matrix.setLookAtM(mViewMatrix, 0,
                    0f, 1.2f, 34f,
                    0f, -2.0f, 0f,
                    0f, 1f, 0f);
            Matrix.setIdentityM(mModelMatrix, 0);
            Matrix.rotateM(mModelMatrix, 0, mAngleY, 1f, 0f, 0f);
            Matrix.rotateM(mModelMatrix, 0, mAngleX, 0f, 1f, 0f);
            Matrix.scaleM(mModelMatrix, 0, mZoomFactor, mZoomFactor, mZoomFactor);

            drawShadow();

            GLES20.glUseProgram(mProgram);
            GLES20.glDisable(GLES20.GL_BLEND);
            drawPart(mHead, mModelMatrix, mSkinTextureId);
            drawPart(mTorso, mModelMatrix, mSkinTextureId);
            drawPart(mRightArm, mModelMatrix, mSkinTextureId);
            drawPart(mLeftArm, mModelMatrix, mSkinTextureId);
            drawPart(mRightLeg, mModelMatrix, mSkinTextureId);
            drawPart(mLeftLeg, mModelMatrix, mSkinTextureId);

            if (mCapeTextureId != 0 && mCape != null) {
                System.arraycopy(mModelMatrix, 0, mPartModel, 0, 16);
                Matrix.translateM(mPartModel, 0, 0f, 8f, -2f);
                Matrix.rotateM(mPartModel, 0, 180f, 0f, 1f, 0f);
                Matrix.rotateM(mPartModel, 0, -10f, 1f, 0f, 0f);
                drawPart(mCape, mPartModel, mCapeTextureId);
            }

            // Overlay layers are genuinely transparent: blend + depth keep sleeves,
            // hats, jackets and capes crisp instead of z-fighting with the base.
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            drawPart(mHeadLayer, mModelMatrix, mSkinTextureId);
            drawPart(mTorsoLayer, mModelMatrix, mSkinTextureId);
            drawPart(mRightArmLayer, mModelMatrix, mSkinTextureId);
            drawPart(mLeftArmLayer, mModelMatrix, mSkinTextureId);
            drawPart(mRightLegLayer, mModelMatrix, mSkinTextureId);
            drawPart(mLeftLegLayer, mModelMatrix, mSkinTextureId);
        }

        private void rebuildCuboidsIfNeeded() {
            if (mHead != null) return;
            int armWidth = mIsSlim ? 3 : 4;
            float armHalf = armWidth / 2f;
            float armCenter = 4f + armHalf;

            mHead = new Cuboid(0, 8, 0, -4, 4, 0, 8, -4, 4, 0, 0, 8, 8, 8, 64, 64, false, 0f);
            mHeadLayer = new Cuboid(0, 8, 0, -4, 4, 0, 8, -4, 4, 32, 0, 8, 8, 8, 64, 64, false, 0.5f);
            mTorso = new Cuboid(0, 8, 0, -4, 4, -12, 0, -2, 2, 16, 16, 8, 12, 4, 64, 64, false, 0f);
            mTorsoLayer = new Cuboid(0, 8, 0, -4, 4, -12, 0, -2, 2, 16, 32, 8, 12, 4, 64, 64, false, 0.25f);
            mRightArm = new Cuboid(-armCenter, 8, 0, -armHalf, armHalf, -12, 0, -2, 2, 40, 16, armWidth, 12, 4, 64, 64, false, 0f);
            mRightArmLayer = new Cuboid(-armCenter, 8, 0, -armHalf, armHalf, -12, 0, -2, 2, 40, 32, armWidth, 12, 4, 64, 64, false, 0.25f);
            mLeftArm = new Cuboid(armCenter, 8, 0, -armHalf, armHalf, -12, 0, -2, 2, 32, 48, armWidth, 12, 4, 64, 64, false, 0f);
            mLeftArmLayer = new Cuboid(armCenter, 8, 0, -armHalf, armHalf, -12, 0, -2, 2, 48, 48, armWidth, 12, 4, 64, 64, false, 0.25f);
            mRightLeg = new Cuboid(-2, -4, 0, -2, 2, -12, 0, -2, 2, 0, 16, 4, 12, 4, 64, 64, false, 0f);
            mRightLegLayer = new Cuboid(-2, -4, 0, -2, 2, -12, 0, -2, 2, 0, 32, 4, 12, 4, 64, 64, false, 0.25f);
            mLeftLeg = new Cuboid(2, -4, 0, -2, 2, -12, 0, -2, 2, 16, 48, 4, 12, 4, 64, 64, false, 0f);
            mLeftLegLayer = new Cuboid(2, -4, 0, -2, 2, -12, 0, -2, 2, 0, 48, 4, 12, 4, 64, 64, false, 0.25f);
            // Req-13: mirror=false — the visible outer cape face must read like
            // it does in-game (lettered/artwork capes used to render flipped).
            mCape = new Cuboid(0, 0, 0, -5, 5, -16, 0, 0, 1, 0, 0, 10, 16, 1, 64, 32, false, 0f);
        }

        private void clearCuboids() {
            mHead = mHeadLayer = mTorso = mTorsoLayer = null;
            mRightArm = mRightArmLayer = mLeftArm = mLeftArmLayer = null;
            mRightLeg = mRightLegLayer = mLeftLeg = mLeftLegLayer = null;
            mCape = null;
        }

        private void drawPart(Cuboid c, float[] baseModel, int textureId) {
            if (c == null || textureId == 0) return;
            System.arraycopy(baseModel, 0, mPartModel, 0, 16);
            Matrix.translateM(mPartModel, 0, c.pX, c.pY, c.pZ);
            Matrix.multiplyMM(mMvScratch, 0, mViewMatrix, 0, mPartModel, 0);
            Matrix.multiplyMM(mMvpScratch, 0, mProjectionMatrix, 0, mMvScratch, 0);

            GLES20.glUseProgram(mProgram);
            GLES20.glEnableVertexAttribArray(mPositionHandle);
            GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 0, c.vertexBuffer);
            GLES20.glEnableVertexAttribArray(mTextureCoordHandle);
            GLES20.glVertexAttribPointer(mTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, c.uvBuffer);
            GLES20.glEnableVertexAttribArray(mNormalHandle);
            GLES20.glVertexAttribPointer(mNormalHandle, 3, GLES20.GL_FLOAT, false, 0, c.normalBuffer);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glUniform1i(mTextureUniformHandle, 0);
            GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mMvpScratch, 0);
            GLES20.glUniformMatrix4fv(mModelMatrixHandle, 1, false, mPartModel, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);
        }

        private void drawShadow() {
            if (mShadowDisc == null) mShadowDisc = new ShadowDisc();
            Matrix.setIdentityM(mShadowModel, 0);
            Matrix.translateM(mShadowModel, 0, 0f, -16.18f, 0f);
            float shadowScale = 8.2f * mZoomFactor;
            Matrix.scaleM(mShadowModel, 0, shadowScale, 1f, shadowScale * 0.64f);
            Matrix.multiplyMM(mMvScratch, 0, mViewMatrix, 0, mShadowModel, 0);
            Matrix.multiplyMM(mMvpScratch, 0, mProjectionMatrix, 0, mMvScratch, 0);

            GLES20.glUseProgram(mColorProgram);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glDepthMask(false);
            GLES20.glEnableVertexAttribArray(mColorPositionHandle);
            GLES20.glVertexAttribPointer(mColorPositionHandle, 3, GLES20.GL_FLOAT, false, 0, mShadowDisc.vertexBuffer);
            GLES20.glUniformMatrix4fv(mColorMvpHandle, 1, false, mMvpScratch, 0);
            GLES20.glUniform4f(mColorUniformHandle, 0f, 0f, 0f, 0.34f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, mShadowDisc.vertexCount);
            GLES20.glDepthMask(true);
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        private static int linkProgram(int vertexShader, int fragmentShader) {
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            return program;
        }

        private int loadShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            return shader;
        }

        private int loadGLTexture(Bitmap bitmap) {
            if (bitmap == null) return 0;
            // Req-13: legacy 64x32 (2:1) Steve-era skins lack the whole bottom
            // half of the modern grid — left limbs and every v>0.5 region used
            // to sample random torso/leg bands ("broken textures"). Expand to
            // the square grid first, mirroring right limbs into the left limb
            // boxes exactly like the 1.8+ client does.
            if (bitmap.getHeight() * 2 == bitmap.getWidth()) {
                Bitmap expanded = expandLegacySkin(bitmap);
                if (expanded != null) bitmap = expanded;
            }
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            return textures[0];
        }

        /** 64x32 (Wx(W/2)) legacy grid → square grid with mirrored left limbs. */
        private static Bitmap expandLegacySkin(@NonNull Bitmap src) {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w != h * 2) return null;
            int s = w / 64;
            if (s < 1) return null;

            Bitmap out = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888);
            int[] buf = new int[w * h];
            src.getPixels(buf, 0, w, 0, 0, w, h);
            out.setPixels(buf, 0, w, 0, 0, w, h);

            // Right-arm box [40,16] → left-arm box [32,48];
            // right-leg box [0,16]  → left-leg box [16,48]  (64-unit grid).
            mirrorLimbBox(buf, out, w, 40, 16, 32, 48, s);
            mirrorLimbBox(buf, out, w,  0, 16, 16, 48, s);
            return out;
        }

        /** Horizontally flip one 16x16-unit limb unwrap box into its left-side slot. */
        private static void mirrorLimbBox(int[] src, Bitmap out, int w,
                                          int us, int vs, int du, int dv, int s) {
            int bw = 16 * s;
            int bh = 16 * s;
            int[] tmp = new int[bw * bh];
            int srcX0 = us * s, srcY0 = vs * s;
            for (int y = 0; y < bh; y++) {
                int srcRow = (srcY0 + y) * w;
                for (int x = 0; x < bw; x++) {
                    tmp[y * bw + x] = src[srcRow + srcX0 + (bw - 1 - x)];
                }
            }
            out.setPixels(tmp, 0, bw, du * s, dv * s, bw, bh);
        }

        private static class Cuboid {
            public final FloatBuffer vertexBuffer;
            public final FloatBuffer uvBuffer;
            public final FloatBuffer normalBuffer;
            public final float pX, pY, pZ;

            public Cuboid(float px, float py, float pz,
                          float x1, float x2, float y1, float y2, float z1, float z2,
                          int us, int vs, int dx, int dy, int dz, int tw, int th,
                          boolean mirror, float expand) {
                pX = px; pY = py; pZ = pz;
                x1 -= expand; x2 += expand;
                y1 -= expand; y2 += expand;
                z1 -= expand; z2 += expand;

                float[] vertices = new float[108];
                float[] uvs = new float[72];
                float[] normals = new float[108];
                addFace(vertices, uvs, 0, 0,
                        x1, y2, z2, x1, y1, z2, x2, y1, z2, x2, y2, z2,
                        us + dz, vs + dz, dx, dy, tw, th, mirror);
                addFace(vertices, uvs, 18, 12,
                        x2, y2, z1, x2, y1, z1, x1, y1, z1, x1, y2, z1,
                        us + dz + dx + dz, vs + dz, dx, dy, tw, th, mirror);
                addFace(vertices, uvs, 36, 24,
                        x1, y2, z1, x1, y1, z1, x1, y1, z2, x1, y2, z2,
                        us, vs + dz, dz, dy, tw, th, mirror);
                addFace(vertices, uvs, 54, 36,
                        x2, y2, z2, x2, y1, z2, x2, y1, z1, x2, y2, z1,
                        us + dz + dx, vs + dz, dz, dy, tw, th, mirror);
                addFace(vertices, uvs, 72, 48,
                        x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1,
                        us + dz, vs, dx, dz, tw, th, mirror);
                addFace(vertices, uvs, 90, 60,
                        x1, y1, z2, x1, y1, z1, x2, y1, z1, x2, y1, z2,
                        us + dz + dx, vs, dx, dz, tw, th, mirror);

                putNormal(normals, 0, 0f, 0f, 1f);
                putNormal(normals, 18, 0f, 0f, -1f);
                putNormal(normals, 36, -1f, 0f, 0f);
                putNormal(normals, 54, 1f, 0f, 0f);
                putNormal(normals, 72, 0f, 1f, 0f);
                putNormal(normals, 90, 0f, -1f, 0f);

                vertexBuffer = ByteBuffer.allocateDirect(108 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices);
                uvBuffer = ByteBuffer.allocateDirect(72 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(uvs);
                normalBuffer = ByteBuffer.allocateDirect(108 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(normals);
                vertexBuffer.position(0);
                uvBuffer.position(0);
                normalBuffer.position(0);
            }

            private static void putNormal(float[] normals, int offset, float x, float y, float z) {
                for (int i = 0; i < 6; i++) {
                    normals[offset + i * 3] = x;
                    normals[offset + i * 3 + 1] = y;
                    normals[offset + i * 3 + 2] = z;
                }
            }

            private static void addFace(float[] vertices, float[] uvs, int vi, int ui,
                                        float xa, float ya, float za,
                                        float xb, float yb, float zb,
                                        float xc, float yc, float zc,
                                        float xd, float yd, float zd,
                                        int us, int vs, int dx, int dy, int tw, int th, boolean mirror) {
                vertices[vi] = xa; vertices[vi + 1] = ya; vertices[vi + 2] = za;
                vertices[vi + 3] = xb; vertices[vi + 4] = yb; vertices[vi + 5] = zb;
                vertices[vi + 6] = xc; vertices[vi + 7] = yc; vertices[vi + 8] = zc;
                vertices[vi + 9] = xa; vertices[vi + 10] = ya; vertices[vi + 11] = za;
                vertices[vi + 12] = xc; vertices[vi + 13] = yc; vertices[vi + 14] = zc;
                vertices[vi + 15] = xd; vertices[vi + 16] = yd; vertices[vi + 17] = zd;

                float u1 = (float) us / tw, v1 = (float) vs / th;
                float u2 = (float) (us + dx) / tw, v2 = (float) (vs + dy) / th;
                if (mirror) { float tmp = u1; u1 = u2; u2 = tmp; }
                uvs[ui] = u1; uvs[ui + 1] = v1;
                uvs[ui + 2] = u1; uvs[ui + 3] = v2;
                uvs[ui + 4] = u2; uvs[ui + 5] = v2;
                uvs[ui + 6] = u1; uvs[ui + 7] = v1;
                uvs[ui + 8] = u2; uvs[ui + 9] = v2;
                uvs[ui + 10] = u2; uvs[ui + 11] = v1;
            }
        }

        /** Ground-contact soft ellipse under the player model. */
        private static class ShadowDisc {
            final FloatBuffer vertexBuffer;
            final int vertexCount;

            ShadowDisc() {
                int segments = 32;
                float[] vertices = new float[(segments + 2) * 3];
                vertices[0] = 0f; vertices[1] = 0f; vertices[2] = 0f;
                for (int i = 0; i <= segments; i++) {
                    double angle = Math.PI * 2.0 * i / segments;
                    int idx = (i + 1) * 3;
                    vertices[idx] = (float) Math.cos(angle);
                    vertices[idx + 1] = 0f;
                    vertices[idx + 2] = (float) Math.sin(angle);
                }
                vertexCount = segments + 2;
                vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices);
                vertexBuffer.position(0);
            }
        }
    }
}
