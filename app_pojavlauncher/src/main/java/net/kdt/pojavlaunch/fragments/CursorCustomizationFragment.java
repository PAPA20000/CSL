package net.kdt.pojavlaunch.fragments;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.*;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.cursor.CursorAppearance;
import net.kdt.pojavlaunch.cursor.CursorEngine;
import net.kdt.pojavlaunch.cursor.CursorRules;
import net.kdt.pojavlaunch.cursor.CursorState;
import net.kdt.pojavlaunch.customcontrols.mouse.CursorManager;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class CursorCustomizationFragment extends Fragment {

    public static final String TAG = "CursorCustomizationFragment";
    private ImageView mPreviewImage;
    private View mUploadZone;
    private Uri mSelectedImageUri;
    private Bitmap mCurrentCursorBitmap;

    private int mHotspotX = 0;
    private int mHotspotY = 0;
    private int mGlowRadius = 0;
    private int mSizeScale = 100;
    private int mOpacity = 100;
    
    // New variables for style and color
    private int mSelectedCursorStyleRes = R.drawable.ic_mouse_pointer;
    private boolean mUseCustomBitmap = false;
    private int mGlowColor = android.graphics.Color.parseColor("#A6FF3D"); // Default neon green

    // ── CS Premium Cursor Studio — pack browser state ──
    private android.content.SharedPreferences mFavPrefs;
    private final java.util.List<CursorPack> mPacks = new java.util.ArrayList<>();
    private final java.util.List<View> mPackCards = new java.util.ArrayList<>();
    private CursorPack mCurrentPack;
    private View mSelectedPackCard;
    private String mCurrentCategory = "All";
    private android.widget.GridLayout mPackGrid;
    private LinearLayout mCatChipsBar;
    private TextView mPackName, mPackCreator, mPackCategory;
    private ImageView mFavButton;
    private AnimatorSet mPulseAnim;
    private int mAnimSpeedPercent = 50;

    // Activity result launcher for file picker
    private final ActivityResultLauncher<String> mFilePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImageSelected);

    // ── Cursor Engine Studio (launcher-side cursors) ──
    private CursorState mEditingState = CursorState.ARROW;
    private CursorAppearance mEditingAppearance;
    private boolean mEditorBinding;               // suppresses slider callbacks during rebind
    private static final int[] SWATCH_COLORS = {
            0x00000000, 0xFFFFFFFF, 0xFFE4E4EA, 0xFF22C55E,
            0xFFFF4444, 0xFFFFD60A, 0xFF4FC3F7, 0xFF9B59E8
    };
    private final ActivityResultLauncher<String> mStateFilePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onStateImageSelected);

    public CursorCustomizationFragment() {
        super(R.layout.fragment_cursor_customization);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize views
        mPreviewImage = view.findViewById(R.id.cursor_preview_image);
        mUploadZone = view.findViewById(R.id.upload_zone);
        View importButton = view.findViewById(R.id.btn_import_png);
        View exportButton = view.findViewById(R.id.btn_export_cursor);
        View saveButton = view.findViewById(R.id.btn_save_cursor);
        View resetButton = view.findViewById(R.id.btn_reset_cursor);
        View backButton = view.findViewById(R.id.cursor_back_button);

        // Setup seekbars
        SeekBar scaleSeek = view.findViewById(R.id.seek_cursor_size);
        SeekBar glowSeek = view.findViewById(R.id.seek_glow_strength);
        SeekBar opacitySeek = view.findViewById(R.id.seek_cursor_opacity);

        TextView scaleText = view.findViewById(R.id.scale_value_text);
        TextView glowText = view.findViewById(R.id.glow_value_text);
        TextView opacityText = view.findViewById(R.id.opacity_value_text);

        // Style selector cards
        View cardClassic = view.findViewById(R.id.style_classic);
        View cardGamepad = view.findViewById(R.id.style_gamepad);
        View cardCustom = view.findViewById(R.id.style_custom);

        // Color preset ImageViews
        ImageView imgGreen = view.findViewById(R.id.color_green);
        ImageView imgCyan = view.findViewById(R.id.color_cyan);
        ImageView imgPurple = view.findViewById(R.id.color_purple);
        ImageView imgRed = view.findViewById(R.id.color_red);
        ImageView imgYellow = view.findViewById(R.id.color_yellow);
        ImageView imgWhite = view.findViewById(R.id.color_white);

        // Load existing preferences
        mGlowRadius = LauncherPreferences.DEFAULT_PREF.getInt("custom_cursor_glow_radius", 0);
        mHotspotX = LauncherPreferences.DEFAULT_PREF.getInt("custom_cursor_hotspot_x", 0);
        mHotspotY = LauncherPreferences.DEFAULT_PREF.getInt("custom_cursor_hotspot_y", 0);
        mSizeScale = (int) LauncherPreferences.DEFAULT_PREF.getFloat("custom_cursor_scale", 100f);
        mOpacity = LauncherPreferences.DEFAULT_PREF.getInt("custom_cursor_opacity", 100);
        mGlowColor = LauncherPreferences.DEFAULT_PREF.getInt("custom_cursor_glow_color", android.graphics.Color.parseColor("#A6FF3D"));

        // Set seekbars initial progress
        scaleSeek.setProgress(mSizeScale);
        scaleText.setText(mSizeScale + "%");

        glowSeek.setProgress(mGlowRadius);
        glowText.setText(mGlowRadius + "%");

        opacitySeek.setProgress(mOpacity);
        opacityText.setText(mOpacity + "%");

        // Load and preview current cursor if it exists
        boolean enabled = LauncherPreferences.PREF_CUSTOM_CURSOR_ENABLED;
        String path = LauncherPreferences.PREF_CUSTOM_CURSOR_PATH;

        if (enabled && path != null) {
            File file = new File(path);
            if (file.exists()) {
                if (file.getName().contains("gamepad")) {
                    mSelectedCursorStyleRes = R.drawable.ic_gamepad_pointer;
                    mUseCustomBitmap = false;
                    
                    applyStyleSelection(cardClassic, cardGamepad, cardCustom, 1);
                } else {
                    try {
                        mCurrentCursorBitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                        if (mCurrentCursorBitmap != null) {
                            mUseCustomBitmap = true;
                            applyStyleSelection(cardClassic, cardGamepad, cardCustom, 2);
                            
                            mHotspotX = mCurrentCursorBitmap.getWidth() / 2;
                            mHotspotY = mCurrentCursorBitmap.getHeight() / 2;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } else {
            mSelectedCursorStyleRes = R.drawable.ic_mouse_pointer;
            mUseCustomBitmap = false;
            applyStyleSelection(cardClassic, cardGamepad, cardCustom, 0);
        }

        // Initialize glow color circles selection state
        initColorSelection(mGlowColor, imgGreen, imgCyan, imgPurple, imgRed, imgYellow, imgWhite);

        // Update live preview initial state
        updateLivePreview();

        // Entrance animation
        animateEntry(view);

        // Upload zone & button clicks
        mUploadZone.setOnClickListener(v -> openFilePicker());
        importButton.setOnClickListener(v -> openFilePicker());
        exportButton.setOnClickListener(v -> exportCursor());

        // Style Selection Listeners
        cardClassic.setOnClickListener(v -> {
            mUseCustomBitmap = false;
            mSelectedCursorStyleRes = R.drawable.ic_mouse_pointer;
            mHotspotX = 0;
            mHotspotY = 0;
            applyStyleSelection(cardClassic, cardGamepad, cardCustom, 0);
            updateLivePreview();
        });

        cardGamepad.setOnClickListener(v -> {
            mUseCustomBitmap = false;
            mSelectedCursorStyleRes = R.drawable.ic_gamepad_pointer;
            mHotspotX = 0;
            mHotspotY = 0;
            applyStyleSelection(cardClassic, cardGamepad, cardCustom, 1);
            updateLivePreview();
        });

        cardCustom.setOnClickListener(v -> {
            if (mCurrentCursorBitmap == null) {
                openFilePicker();
            } else {
                mUseCustomBitmap = true;
                applyStyleSelection(cardClassic, cardGamepad, cardCustom, 2);
                updateLivePreview();
            }
        });

        // Color circle preset click listeners
        imgGreen.setOnClickListener(v -> selectGlowColor(0xFFA6FF3D, imgGreen));
        imgCyan.setOnClickListener(v -> selectGlowColor(0xFFC9CBD6, imgCyan));
        imgPurple.setOnClickListener(v -> selectGlowColor(0xFFD500F9, imgPurple));
        imgRed.setOnClickListener(v -> selectGlowColor(0xFFFF3D00, imgRed));
        imgYellow.setOnClickListener(v -> selectGlowColor(0xFFFFEA00, imgYellow));
        imgWhite.setOnClickListener(v -> selectGlowColor(0xFFFFFFFF, imgWhite));

        // SeekBar listeners
        scaleSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 25) {
                    progress = 25;
                    if (fromUser) seekBar.setProgress(25);
                }
                mSizeScale = progress;
                scaleText.setText(progress + "%");
                updateLivePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        glowSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mGlowRadius = progress;
                glowText.setText(progress + "%");
                updateLivePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });


        opacitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mOpacity = progress;
                opacityText.setText(progress + "%");
                updateLivePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Save & Reset actions
        saveButton.setOnClickListener(v -> saveCursor());
        resetButton.setOnClickListener(v -> resetToDefaultInstant());

        // Back button
        backButton.setOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().getSupportFragmentManager().popBackStack();
            }
        });

        // Apply press animations to buttons
        applyPressAnimation(backButton);
        applyPressAnimation(importButton);
        applyPressAnimation(exportButton);
        applyPressAnimation(saveButton);
        applyPressAnimation(resetButton);

        // ── CS Premium pack browser / pulse preview / favorites ──
        initPacks(view);
        setupAnimSpeedPanel(view);
        setupClickTest(view);

        // ── Cursor Engine: states studio + rule editor ──
        initCursorEngineStudio(view);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  CURSOR ENGINE STUDIO — per-state customization + rule editor
    //  (launcher-UI cursors; the in-game virtual cursor above is untouched)
    // ═════════════════════════════════════════════════════════════════════

    private void initCursorEngineStudio(@NonNull View view) {
        androidx.appcompat.widget.SwitchCompat master = view.findViewById(R.id.cse_master_switch);
        if (master == null) return; // defensive: layout variant without the engine block
        TextView unsupported = view.findViewById(R.id.cse_engine_unsupported);

        boolean supported = CursorEngine.isSupported();
        if (unsupported != null) unsupported.setVisibility(supported ? View.GONE : View.VISIBLE);
        master.setEnabled(supported);
        master.setChecked(CursorEngine.isEnabled());
        master.setOnCheckedChangeListener((button, checked) ->
                CursorEngine.setEnabled(requireContext(), checked));

        buildStatesList(view);
        setupStateEditor(view);
        refreshRulesSection(view);

        // Rule-creation controls
        Spinner matcherSpinner = view.findViewById(R.id.cse_add_rule_matcher);
        Spinner stateSpinner = view.findViewById(R.id.cse_add_rule_state);
        if (matcherSpinner != null) {
            List<String> names = new ArrayList<>();
            for (int m : CursorRules.ALL_MATCHERS) names.add(CursorRules.matcherName(m));
            matcherSpinner.setAdapter(new android.widget.ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_dropdown_item, names));
        }
        if (stateSpinner != null) {
            stateSpinner.setAdapter(new android.widget.ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_dropdown_item, stateNames()));
        }
        View addBtn = view.findViewById(R.id.cse_btn_add_rule);
        if (addBtn != null) addBtn.setOnClickListener(v -> {
            int matcher = CursorRules.ALL_MATCHERS[
                    Math.max(0, matcherSpinner == null ? 0 : matcherSpinner.getSelectedItemPosition())];
            CursorState state = CursorState.values()[
                    Math.max(0, stateSpinner == null ? 0 : stateSpinner.getSelectedItemPosition())];
            CursorRules.add(new CursorRules.Rule(matcher, state));
            CursorEngine.invalidateAll();
            refreshRulesSection(view);
        });
        View resetRules = view.findViewById(R.id.cse_btn_reset_rules);
        if (resetRules != null) resetRules.setOnClickListener(v -> {
            CursorRules.resetDefaults();
            CursorEngine.invalidateAll();
            refreshRulesSection(view);
        });
    }

    // ---------------------------------------------------------------- states list

    private void buildStatesList(@NonNull View view) {
        LinearLayout container = view.findViewById(R.id.cse_states_list);
        if (container == null || getContext() == null) return;
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (CursorState state : CursorState.values()) {
            View row = inflater.inflate(R.layout.item_cursor_state, container, false);
            TextView name = row.findViewById(R.id.state_name);
            TextView badge = row.findViewById(R.id.state_badge);
            View dot = row.findViewById(R.id.state_dot);
            if (name != null) name.setText(state.labelRes);
            boolean custom = CursorAppearance.load(state).customBitmap;
            if (badge != null) badge.setText(custom ? R.string.cse_badge_custom : R.string.cse_badge_system);
            if (dot != null) dot.setBackground(makeDot(custom ? 0xFF9B59E8 : 0xFF22C55E));
            row.setOnClickListener(v -> openStateEditor(view, state));
            container.addView(row);
            row.setAlpha(0f);
            row.setTranslationY(12f * getResources().getDisplayMetrics().density);
            row.animate().alpha(1f).translationY(0f).setDuration(240)
                    .setStartDelay(container.indexOfChild(row) * 45L).start();
        }
    }

    private android.graphics.drawable.GradientDrawable makeDot(int color) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    // ---------------------------------------------------------------- state editor

    private void openStateEditor(@NonNull View root, @NonNull CursorState state) {
        mEditingState = state;
        mEditingAppearance = CursorAppearance.load(state);
        View editor = root.findViewById(R.id.cse_state_editor);
        if (editor == null) return;
        boolean wasHidden = editor.getVisibility() != View.VISIBLE;
        editor.setVisibility(View.VISIBLE);
        if (wasHidden) {
            editor.setAlpha(0f);
            editor.setTranslationY(16f * getResources().getDisplayMetrics().density);
            editor.animate().alpha(1f).translationY(0f).setDuration(220).start();
        }
        bindStateEditor(root);
    }

    private void setupStateEditor(@NonNull View view) {
        View useSystem = view.findViewById(R.id.cse_btn_use_system);
        if (useSystem != null) useSystem.setOnClickListener(v -> {
            if (mEditingAppearance == null) return;
            mEditingAppearance.customBitmap = false;
            mEditingAppearance.bitmapPath = null;
            persistAppearance();
        });
        View importPng = view.findViewById(R.id.cse_btn_import_png);
        if (importPng != null) importPng.setOnClickListener(v -> mStateFilePickerLauncher.launch("image/*"));
        View reset = view.findViewById(R.id.cse_btn_reset_state);
        if (reset != null) reset.setOnClickListener(v -> {
            mEditingAppearance = new CursorAppearance();
            mEditingAppearance.reset(mEditingState);
            CursorEngine.invalidate(mEditingState);
            bindStateEditor(view);
            buildStatesList(view);
            toast(R.string.cse_reset_done);
        });

        bindSeek(view, R.id.cse_seek_scale, R.id.cse_val_scale,
                0, v -> mEditingAppearance.scalePercent = v, "%");
        bindSeek(view, R.id.cse_seek_rotation, R.id.cse_val_rotation,
                -180, v -> mEditingAppearance.rotationDeg = v, "°");
        bindSeek(view, R.id.cse_seek_opacity, R.id.cse_val_opacity,
                0, v -> mEditingAppearance.opacityPercent = v, "%");
        bindSeek(view, R.id.cse_seek_hotspot_x, R.id.cse_val_hotspot_x,
                -1, v -> mEditingAppearance.hotspotX = v, "px");
        bindSeek(view, R.id.cse_seek_hotspot_y, R.id.cse_val_hotspot_y,
                -1, v -> mEditingAppearance.hotspotY = v, "px");
        bindSeek(view, R.id.cse_seek_glow, R.id.cse_val_glow,
                0, v -> mEditingAppearance.glowRadius = v, "dp");
        bindSeek(view, R.id.cse_seek_border, R.id.cse_val_border,
                0, v -> mEditingAppearance.borderWidth = v, "dp");
        bindSeek(view, R.id.cse_seek_shadow, R.id.cse_val_shadow,
                0, v -> mEditingAppearance.shadowRadius = v, "dp");

        setupSwatchRow(view, R.id.cse_glow_colors,
                () -> mEditingAppearance.glowColor, c -> mEditingAppearance.glowColor = c);
        setupSwatchRow(view, R.id.cse_tint_colors,
                () -> mEditingAppearance.tintColor, c -> mEditingAppearance.tintColor = c);
        setupSwatchRow(view, R.id.cse_border_colors,
                () -> mEditingAppearance.borderColor, c -> mEditingAppearance.borderColor = c);

        // Tap-to-place hotspot on the preview pad.
        View pad = view.findViewById(R.id.cse_preview_pad);
        ImageView icon = view.findViewById(R.id.cse_editor_preview_icon);
        if (pad != null && icon != null) {
            pad.setOnTouchListener((v, event) -> {
                if (mEditingAppearance == null) return false;
                Bitmap src = CursorEngine.loadSourceBitmap(mEditingState);
                if (src == null) return false; // hotspot only meaningful with a custom bitmap
                int[] padPos = new int[2];
                int[] iconPos = new int[2];
                pad.getLocationOnScreen(padPos);
                icon.getLocationOnScreen(iconPos);
                float relX = event.getRawX() - iconPos[0];
                float relY = event.getRawY() - iconPos[1];
                int iw = icon.getWidth(), ih = icon.getHeight();
                if (iw <= 0 || ih <= 0) return false;
                // ImageView is fitCenter: compute the drawn rect.
                float scale = Math.min(iw / (float) src.getWidth(), ih / (float) src.getHeight());
                float drawnW = src.getWidth() * scale, drawnH = src.getHeight() * scale;
                float offX = (iw - drawnW) / 2f, offY = (ih - drawnH) / 2f;
                int sx = Math.round((relX - offX) / scale);
                int sy = Math.round((relY - offY) / scale);
                sx = Math.max(0, Math.min(src.getWidth() - 1, sx));
                sy = Math.max(0, Math.min(src.getHeight() - 1, sy));
                mEditingAppearance.hotspotX = sx;
                mEditingAppearance.hotspotY = sy;
                persistAppearance();
                refreshHotspotUi(view);
                return true;
            });
        }
    }

    private interface IntSetter { void set(int v); }
    private interface ColorGetter { int get(); }
    private interface ColorSetter { void set(int c); }

    private void bindSeek(@NonNull View view, int seekId, int valId,
                          int offset, IntSetter setter, String suffix) {
        SeekBar seek = view.findViewById(seekId);
        TextView val = view.findViewById(valId);
        if (seek == null || val == null) return;
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (mEditorBinding || mEditingAppearance == null) return;
                int value = progress + offset;
                if (seekId == R.id.cse_seek_scale) value = Math.max(25, value);
                setter.set(value);
                persistAppearanceLight();
                if (seekId == R.id.cse_seek_hotspot_x || seekId == R.id.cse_seek_hotspot_y) {
                    refreshHotspotUi(view);
                } else {
                    val.setText(value + suffix);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) { persistAppearance(); }
        });
    }

    private void setupSwatchRow(@NonNull View view, int rowId, ColorGetter getter, ColorSetter setter) {
        LinearLayout row = view.findViewById(rowId);
        if (row == null || getContext() == null) return;
        row.removeAllViews();
        int size = (int) (30 * getResources().getDisplayMetrics().density);
        int margin = (int) (8 * getResources().getDisplayMetrics().density);
        for (int color : SWATCH_COLORS) {
            ImageView swatch = new ImageView(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(margin);
            swatch.setLayoutParams(lp);
            row.addView(swatch);
            swatch.setOnClickListener(v -> {
                if (mEditingAppearance == null) return;
                setter.set(color);
                persistAppearance();
                refreshSwatches(row, getter.get());
            });
        }
        refreshSwatches(row, getter.get());
    }

    private void refreshSwatches(@NonNull LinearLayout row, int selected) {
        for (int i = 0; i < row.getChildCount() && i < SWATCH_COLORS.length; i++) {
            ImageView swatch = (ImageView) row.getChildAt(i);
            int color = SWATCH_COLORS[i];
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            d.setColor(color == 0 ? 0xFF2A2A30 : color);
            if (color == 0) {
                // "None" swatch: dark ring with a strike hint
                d.setStroke(3, 0xFF55555E);
            }
            boolean isSelected = selected == color;
            d.setStroke(isSelected ? 4 : 2, isSelected ? 0xFFE4E4EA : (color == 0 ? 0xFF55555E : 0x00000000));
            swatch.setImageDrawable(d);
            if (color == 0 && isSelected) { /* keep visible ring */ }
            swatch.setAlpha(color == 0 && !isSelected ? 0.55f : 1f);
        }
    }

    private void bindStateEditor(@NonNull View view) {
        if (mEditingAppearance == null) mEditingAppearance = CursorAppearance.load(mEditingState);
        mEditorBinding = true;
        TextView name = view.findViewById(R.id.cse_editor_state_name);
        TextView badge = view.findViewById(R.id.cse_editor_badge);
        if (name != null) name.setText(mEditingState.labelRes);
        if (badge != null) badge.setText(mEditingAppearance.customBitmap
                ? R.string.cse_badge_custom : R.string.cse_badge_system);

        setSeek(view, R.id.cse_seek_scale, R.id.cse_val_scale, mEditingAppearance.scalePercent, (mEditingAppearance.scalePercent) + "%");
        setSeek(view, R.id.cse_seek_rotation, R.id.cse_val_rotation, mEditingAppearance.rotationDeg + 180, mEditingAppearance.rotationDeg + "°");
        setSeek(view, R.id.cse_seek_opacity, R.id.cse_val_opacity, mEditingAppearance.opacityPercent, mEditingAppearance.opacityPercent + "%");
        setSeek(view, R.id.cse_seek_glow, R.id.cse_val_glow, mEditingAppearance.glowRadius, mEditingAppearance.glowRadius + "dp");
        setSeek(view, R.id.cse_seek_border, R.id.cse_val_border, mEditingAppearance.borderWidth, mEditingAppearance.borderWidth + "dp");
        setSeek(view, R.id.cse_seek_shadow, R.id.cse_val_shadow, mEditingAppearance.shadowRadius, mEditingAppearance.shadowRadius + "dp");
        refreshHotspotUi(view);
        refreshEditorPreview(view);
        LinearLayout glowRow = view.findViewById(R.id.cse_glow_colors);
        LinearLayout tintRow = view.findViewById(R.id.cse_tint_colors);
        LinearLayout borderRow = view.findViewById(R.id.cse_border_colors);
        if (glowRow != null) refreshSwatches(glowRow, mEditingAppearance.glowColor);
        if (tintRow != null) refreshSwatches(tintRow, mEditingAppearance.tintColor);
        if (borderRow != null) refreshSwatches(borderRow, mEditingAppearance.borderColor);
        mEditorBinding = false;
    }

    private void setSeek(@NonNull View view, int seekId, int valId, int progress, String text) {
        SeekBar seek = view.findViewById(seekId);
        TextView val = view.findViewById(valId);
        if (seek != null) seek.setProgress(progress);
        if (val != null) val.setText(text);
    }

    private void refreshHotspotUi(@NonNull View view) {
        if (!isAdded()) return;
        setSeek(view, R.id.cse_seek_hotspot_x, R.id.cse_val_hotspot_x,
                mEditingAppearance.hotspotX + 1,
                mEditingAppearance.hotspotX < 0 ? getString(R.string.cse_auto) : (mEditingAppearance.hotspotX + "px"));
        setSeek(view, R.id.cse_seek_hotspot_y, R.id.cse_val_hotspot_y,
                mEditingAppearance.hotspotY + 1,
                mEditingAppearance.hotspotY < 0 ? getString(R.string.cse_auto) : (mEditingAppearance.hotspotY + "px"));
        positionHotspotDot(view);
    }

    private void refreshEditorPreview(@NonNull View view) {
        ImageView icon = view.findViewById(R.id.cse_editor_preview_icon);
        if (icon != null) {
            int base = (int) (70 * getResources().getDisplayMetrics().density);
            Bitmap preview = CursorEngine.renderPreview(requireContext(), mEditingState, base);
            icon.setImageBitmap(preview);
        }
        // The whole pad shows the real PointerIcon on hover: instant live feedback.
        View pad = view.findViewById(R.id.cse_preview_pad);
        if (pad != null && CursorEngine.isSupported()) {
            pad.setPointerIcon(CursorEngine.getIcon(requireContext(), mEditingState));
        }
        positionHotspotDot(view);
    }

    private void positionHotspotDot(@NonNull View view) {
        View dot = view.findViewById(R.id.cse_hotspot_dot);
        ImageView icon = view.findViewById(R.id.cse_editor_preview_icon);
        if (dot == null || icon == null) return;
        Bitmap src = CursorEngine.loadSourceBitmap(mEditingState);
        if (src == null || mEditingAppearance == null
                || mEditingAppearance.hotspotX < 0 || mEditingAppearance.hotspotY < 0) {
            dot.setVisibility(View.GONE);
            return;
        }
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(0xFFFF4444);
        d.setStroke(2, 0xFFFFFFFF);
        dot.setBackground(d);
        dot.setVisibility(View.VISIBLE);
        icon.post(() -> {
            int iw = icon.getWidth(), ih = icon.getHeight();
            if (iw <= 0 || ih <= 0) return;
            float scale = Math.min(iw / (float) src.getWidth(), ih / (float) src.getHeight());
            float drawnW = src.getWidth() * scale, drawnH = src.getHeight() * scale;
            float offX = (iw - drawnW) / 2f, offY = (ih - drawnH) / 2f;
            float cx = offX + mEditingAppearance.hotspotX * scale;
            float cy = offY + mEditingAppearance.hotspotY * scale;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dot.getLayoutParams();
            lp.gravity = Gravity.TOP | Gravity.START;
            int[] iconPos = new int[2];
            int[] padPos = new int[2];
            icon.getLocationOnScreen(iconPos);
            View pad = view.findViewById(R.id.cse_preview_pad);
            if (pad == null) return;
            pad.getLocationOnScreen(padPos);
            lp.leftMargin = Math.round(iconPos[0] - padPos[0] + cx - dot.getWidth() / 2f);
            lp.topMargin = Math.round(iconPos[1] - padPos[1] + cy - dot.getHeight() / 2f);
            dot.setLayoutParams(lp);
        });
    }

    private void persistAppearanceLight() {
        if (mEditingAppearance == null) return;
        mEditingAppearance.save(mEditingState);
        // Light path for slider drags: icon/preview refresh happens on tracking-stop.
    }

    private void persistAppearance() {
        if (mEditingAppearance == null) return;
        mEditingAppearance.save(mEditingState);
        CursorEngine.invalidate(mEditingState);
        View root = getView();
        if (root != null) {
            refreshEditorPreview(root);
            buildStatesList(root);
        }
    }

    private void onStateImageSelected(Uri uri) {
        if (uri == null) return;
        try {
            if (isGif(uri)) {
                Toast.makeText(getContext(), "GIF not supported for cursor states", Toast.LENGTH_SHORT).show();
                return;
            }
            File file = copyUriToFile(uri, "cse_" + mEditingState.name().toLowerCase(java.util.Locale.ROOT) + ".png");
            if (mEditingAppearance == null) mEditingAppearance = CursorAppearance.load(mEditingState);
            mEditingAppearance.customBitmap = true;
            mEditingAppearance.bitmapPath = file.getAbsolutePath();
            mEditingAppearance.hotspotX = -1;
            mEditingAppearance.hotspotY = -1;
            persistAppearance();
            View root = getView();
            if (root != null) bindStateEditor(root);
            toast(R.string.cse_state_saved);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Failed to import: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void toast(int res) {
        if (getContext() != null) Toast.makeText(getContext(), res, Toast.LENGTH_SHORT).show();
    }

    private List<String> stateNames() {
        List<String> names = new ArrayList<>();
        for (CursorState s : CursorState.values()) names.add(getString(s.labelRes));
        return names;
    }

    // ---------------------------------------------------------------- rules list

    private void refreshRulesSection(@NonNull View view) {
        LinearLayout container = view.findViewById(R.id.cse_rules_list);
        if (container == null || getContext() == null) return;
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        List<CursorRules.Rule> rules = CursorRules.get();
        for (int i = 0; i < rules.size(); i++) {
            final int index = i;
            final CursorRules.Rule rule = rules.get(i);
            View row = inflater.inflate(R.layout.item_cursor_rule, container, false);
            TextView desc = row.findViewById(R.id.rule_desc);
            Spinner spinner = row.findViewById(R.id.rule_state_spinner);
            androidx.appcompat.widget.SwitchCompat enabled = row.findViewById(R.id.rule_enabled);
            View up = row.findViewById(R.id.rule_up);
            View down = row.findViewById(R.id.rule_down);
            View delete = row.findViewById(R.id.rule_delete);

            if (desc != null) desc.setText(CursorRules.matcherName(rule.matcher));
            if (spinner != null) {
                spinner.setAdapter(new android.widget.ArrayAdapter<>(requireContext(),
                        android.R.layout.simple_spinner_dropdown_item, stateNames()));
                spinner.setSelection(rule.state.ordinal(), false);
                spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                        CursorState newState = CursorState.values()[position];
                        if (newState != rule.state) {
                            rule.state = newState;
                            CursorRules.save();
                            CursorEngine.invalidateAll();
                        }
                    }
                    @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
            }
            if (enabled != null) {
                enabled.setChecked(rule.enabled);
                enabled.setOnCheckedChangeListener((button, checked) -> {
                    rule.enabled = checked;
                    CursorRules.save();
                    CursorEngine.invalidateAll();
                });
            }
            if (up != null) {
                up.setVisibility(index > 0 ? View.VISIBLE : View.INVISIBLE);
                up.setOnClickListener(v -> { CursorRules.move(index, -1); CursorEngine.invalidateAll(); refreshRulesSection(view); });
            }
            if (down != null) {
                down.setVisibility(index < rules.size() - 1 ? View.VISIBLE : View.INVISIBLE);
                down.setOnClickListener(v -> { CursorRules.move(index, 1); CursorEngine.invalidateAll(); refreshRulesSection(view); });
            }
            if (delete != null) {
                boolean isFallback = rule.matcher == CursorRules.MATCH_ANY;
                delete.setVisibility(isFallback ? View.INVISIBLE : View.VISIBLE);
                delete.setOnClickListener(v -> {
                    CursorRules.remove(index);
                    CursorEngine.invalidateAll();
                    refreshRulesSection(view);
                    toast(R.string.cse_rule_deleted);
                });
            }
            container.addView(row);
        }
    }


    private void updateLivePreview() {
        if (mPreviewImage == null) return;

        // 1. Get the base bitmap based on the selected style
        Bitmap baseBmp = null;
        try {
            if (mUseCustomBitmap && mCurrentCursorBitmap != null) {
                baseBmp = mCurrentCursorBitmap;
            } else {
                baseBmp = BitmapFactory.decodeResource(getResources(), mSelectedCursorStyleRes);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (baseBmp == null) return;

        // 2. Apply the glow effect based on seekbar progress
        Bitmap processedBmp = baseBmp;
        if (mGlowRadius > 0) {
            try {
                processedBmp = CursorManager.applyGlow(baseBmp, mGlowRadius, mGlowColor);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 3. Set bitmap to the preview image view
        mPreviewImage.setImageBitmap(processedBmp);

        // For default pointer, if padding is needed, set 0 if glow is applied or 6dp if classic
        if (!mUseCustomBitmap && mGlowRadius == 0) {
            int padding = (int) (6 * getResources().getDisplayMetrics().density);
            mPreviewImage.setPadding(padding, padding, padding, padding);
        } else {
            mPreviewImage.setPadding(0, 0, 0, 0);
        }

        // 4. Update scales and alphas
        mPreviewImage.setScaleX(mSizeScale / 100f);
        mPreviewImage.setScaleY(mSizeScale / 100f);
        mPreviewImage.setAlpha(mOpacity / 100f);

        // 5. Update status labels
        View view = getView();
        if (view != null) {
            updatePreviewStatusText(view);

            TextView label = view.findViewById(R.id.cursor_preview_label);
            if (label != null) {
                if (mCurrentPack != null) {
                    label.setText(mCurrentPack.name.toUpperCase(java.util.Locale.US));
                } else if (mUseCustomBitmap) {
                    label.setText("CUSTOM");
                } else if (mSelectedCursorStyleRes == R.drawable.ic_gamepad_pointer) {
                    label.setText("GAMEPAD");
                } else {
                    label.setText("DEFAULT");
                }
            }
        }
    }

    private void updatePreviewStatusText(View root) {
        TextView statusText = root.findViewById(R.id.cursor_preview_status);
        if (statusText != null) {
            statusText.setText("Scale: " + mSizeScale + "% | Opacity: " + mOpacity + "%");
        }
    }

    private void initColorSelection(int color, ImageView... views) {
        ImageView selectView = views[0]; // Default green
        if (color == 0xFFC9CBD6) selectView = views[1];
        else if (color == 0xFFD500F9) selectView = views[2];
        else if (color == 0xFFFF3D00) selectView = views[3];
        else if (color == 0xFFFFEA00) selectView = views[4];
        else if (color == 0xFFFFFFFF) selectView = views[5];

        selectGlowColor(color, selectView);
    }

    private void selectGlowColor(int color, ImageView selectedView) {
        mGlowColor = color;
        
        View root = getView();
        if (root == null) return;

        ImageView imgGreen = root.findViewById(R.id.color_green);
        ImageView imgCyan = root.findViewById(R.id.color_cyan);
        ImageView imgPurple = root.findViewById(R.id.color_purple);
        ImageView imgRed = root.findViewById(R.id.color_red);
        ImageView imgYellow = root.findViewById(R.id.color_yellow);
        ImageView imgWhite = root.findViewById(R.id.color_white);

        ImageView[] colorViews = {imgGreen, imgCyan, imgPurple, imgRed, imgYellow, imgWhite};
        for (ImageView v : colorViews) {
            if (v != null) {
                v.setImageDrawable(null);
            }
        }

        if (selectedView != null) {
            selectedView.setImageResource(R.drawable.ic_check_circle);
            selectedView.setPadding(2, 2, 2, 2);
            selectedView.setColorFilter(0xFF000000);
        }

        updateLivePreview();
    }

    private void resetToDefaultInstant() {
        mSizeScale = 100;
        mHotspotX = 0;
        mHotspotY = 0;
        mOpacity = 100;
        mGlowRadius = 0;
        mGlowColor = android.graphics.Color.parseColor("#A6FF3D");
        mUseCustomBitmap = false;
        mSelectedCursorStyleRes = R.drawable.ic_mouse_pointer;

        View root = getView();
        if (root == null) return;

        SeekBar scaleSeek = root.findViewById(R.id.seek_cursor_size);
        SeekBar glowSeek = root.findViewById(R.id.seek_glow_strength);
        SeekBar opacitySeek = root.findViewById(R.id.seek_cursor_opacity);

        TextView scaleText = root.findViewById(R.id.scale_value_text);
        TextView glowText = root.findViewById(R.id.glow_value_text);
        TextView opacityText = root.findViewById(R.id.opacity_value_text);

        if (scaleSeek != null) scaleSeek.setProgress(100);
        if (glowSeek != null) glowSeek.setProgress(0);
        if (opacitySeek != null) opacitySeek.setProgress(100);

        if (scaleText != null) scaleText.setText("100%");
        if (glowText != null) glowText.setText("0%");
        if (opacityText != null) opacityText.setText("100%");

        View cardClassic = root.findViewById(R.id.style_classic);
        View cardGamepad = root.findViewById(R.id.style_gamepad);
        View cardCustom = root.findViewById(R.id.style_custom);

        applyStyleSelection(cardClassic, cardGamepad, cardCustom, 0);

        ImageView imgGreen = root.findViewById(R.id.color_green);
        selectGlowColor(mGlowColor, imgGreen);

        mCurrentPack = null;
        CursorPack classic = findPackById("classic");
        if (classic != null && mPacks != null && !mPacks.isEmpty()) {
            selectPack(classic, true);
        }

        updateLivePreview();

        Toast.makeText(getContext(), "Cursor reset to default instantly!", Toast.LENGTH_SHORT).show();
    }

    private void animateEntry(View root) {
        View topBar = root.findViewById(R.id.cursor_top_bar);
        View previewContainer = root.findViewById(R.id.cursor_preview_container);

        // Top bar slides down
        topBar.setTranslationY(-80f);
        topBar.setAlpha(0f);
        topBar.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(300)
            .setInterpolator(new DecelerateInterpolator(1.2f))
            .start();

        // Animate preview container
        if (previewContainer != null) {
            previewContainer.setAlpha(0f);
            previewContainer.setTranslationY(20f);
            previewContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(450)
                .setStartDelay(100)
                .setInterpolator(new DecelerateInterpolator(1.2f))
                .start();
        }

        // Animate upload zone
        if (mUploadZone != null) {
            mUploadZone.setAlpha(0f);
            mUploadZone.setTranslationY(15f);
            mUploadZone.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(350)
                .setStartDelay(200)
                .setInterpolator(new DecelerateInterpolator(1.2f))
                .start();
        }
    }

    private void openFilePicker() {
        mFilePickerLauncher.launch("image/*");
    }

    private boolean isGif(Uri uri) {
        if (uri == null) return false;
        try {
            String mimeType = requireContext().getContentResolver().getType(uri);
            if (mimeType != null && mimeType.toLowerCase().contains("gif")) {
                return true;
            }
            String path = uri.getPath();
            if (path != null && path.toLowerCase().endsWith(".gif")) {
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private File copyUriToFile(Uri uri, String destName) throws Exception {
        File dir = new File(net.kdt.pojavlaunch.Tools.DIR_CURSORS);
        if (!dir.exists()) dir.mkdirs();
        File destFile = new File(dir, destName);
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return destFile;
    }

    private File saveResourceToFile(int resId, String destName) throws Exception {
        Bitmap bmp = BitmapFactory.decodeResource(getResources(), resId);
        File dir = new File(net.kdt.pojavlaunch.Tools.DIR_CURSORS);
        if (!dir.exists()) dir.mkdirs();
        File destFile = new File(dir, destName);
        try (FileOutputStream out = new FileOutputStream(destFile)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return destFile;
    }

    private File saveBitmapToFile(Bitmap bitmap, String destName) throws Exception {
        File dir = new File(net.kdt.pojavlaunch.Tools.DIR_CURSORS);
        if (!dir.exists()) dir.mkdirs();
        File destFile = new File(dir, destName);
        try (FileOutputStream out = new FileOutputStream(destFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return destFile;
    }

    private void onImageSelected(Uri uri) {
        if (uri == null) return;
        if (isGif(uri)) {
            Toast.makeText(getContext(), "GIF cursor is no longer supported. Please choose a normal image.", Toast.LENGTH_SHORT).show();
            return;
        }
        mSelectedImageUri = uri;

        try {
            // Load the image
            InputStream inputStream = requireContext().getContentResolver().openInputStream(uri);
            if (inputStream == null) return;

            // Decode bitmap with size limits to avoid OOM
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(inputStream, null, options);
            inputStream.close();

            // Calculate sample size (max 128px for cursor)
            int maxSize = 128;
            int sampleSize = 1;
            while (options.outWidth / sampleSize > maxSize || options.outHeight / sampleSize > maxSize) {
                sampleSize *= 2;
            }

            // Load the scaled bitmap
            InputStream inputStream2 = requireContext().getContentResolver().openInputStream(uri);
            BitmapFactory.Options options2 = new BitmapFactory.Options();
            options2.inSampleSize = sampleSize;
            mCurrentCursorBitmap = BitmapFactory.decodeStream(inputStream2, null, options2);
            inputStream2.close();

            if (mCurrentCursorBitmap != null) {
                mUseCustomBitmap = true;

                View root = getView();
                if (root != null) {
                    View cardClassic = root.findViewById(R.id.style_classic);
                    View cardGamepad = root.findViewById(R.id.style_gamepad);
                    View cardCustom = root.findViewById(R.id.style_custom);

                    applyStyleSelection(cardClassic, cardGamepad, cardCustom, 2);

                    // Auto-center custom cursor hotspot for a simpler setup
                    mHotspotX = mCurrentCursorBitmap.getWidth() / 2;
                    mHotspotY = mCurrentCursorBitmap.getHeight() / 2;
                }

                // Update live preview
                updateLivePreview();

                Toast.makeText(getContext(), "Custom cursor loaded successfully!", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Failed to load image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveCursor() {
        try {
            boolean enabled = true;
            String path = null;

            if (mUseCustomBitmap) {
                if (mCurrentCursorBitmap == null) {
                    path = LauncherPreferences.PREF_CUSTOM_CURSOR_PATH;
                    if (path == null) {
                        Toast.makeText(getContext(), "Please select an image first!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                } else {
                    String name = "custom_cursor_" + System.currentTimeMillis() + ".png";
                    File savedFile = saveBitmapToFile(mCurrentCursorBitmap, name);
                    path = savedFile.getAbsolutePath();
                }
            } else if (mSelectedCursorStyleRes == R.drawable.ic_gamepad_pointer) {
                File savedFile = saveResourceToFile(R.drawable.ic_gamepad_pointer, "gamepad_cursor.png");
                path = savedFile.getAbsolutePath();
            } else {
                // Classic pointer
                enabled = false;
            }

            // Save preferences
            LauncherPreferences.DEFAULT_PREF.edit()
                .putString("custom_cursor_path", path)
                .putBoolean("custom_cursor_enabled", enabled)
                .putInt("custom_cursor_hotspot_x", mHotspotX)
                .putInt("custom_cursor_hotspot_y", mHotspotY)
                .putFloat("custom_cursor_scale", (float) mSizeScale)
                .putInt("custom_cursor_glow_radius", mGlowRadius)
                .putInt("custom_cursor_glow_color", mGlowColor)
                .putInt("custom_cursor_opacity", mOpacity)
                .apply();

            // Load variables in memory
            LauncherPreferences.PREF_CUSTOM_CURSOR_PATH = path;
            LauncherPreferences.PREF_CUSTOM_CURSOR_ENABLED = enabled;
            LauncherPreferences.PREF_CUSTOM_CURSOR_GLOW_RADIUS = mGlowRadius;
            LauncherPreferences.PREF_CUSTOM_CURSOR_GLOW_COLOR = mGlowColor;
            LauncherPreferences.PREF_CUSTOM_CURSOR_SCALE = (float) mSizeScale;
            LauncherPreferences.PREF_CUSTOM_CURSOR_OPACITY = mOpacity / 100f;

            // Reapply renderer changes
            net.kdt.pojavlaunch.extra.ExtraCore.setValue(net.kdt.pojavlaunch.extra.ExtraConstants.REFRESH_CURSOR, null);
            net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.reset();
            net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.updateCursorFrame();

            Toast.makeText(getContext(), "Cursor changes saved successfully!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void exportCursor() {
        String currentPath = LauncherPreferences.PREF_CUSTOM_CURSOR_PATH;
        if (currentPath == null || !(new File(currentPath).exists())) {
            Toast.makeText(getContext(), "No customized cursor file found to export!", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File srcFile = new File(currentPath);
            File exportDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (!exportDir.exists()) exportDir.mkdirs();
            File destFile = new File(exportDir, srcFile.getName());

            try (java.io.FileInputStream in = new java.io.FileInputStream(srcFile);
                 FileOutputStream out = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            Toast.makeText(getContext(), "Cursor exported to Downloads: " + destFile.getName(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void applyPressAnimation(View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate()
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .setDuration(80)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .setInterpolator(new OvershootInterpolator(1.8f))
                        .start();
                    break;
            }
            return false;
        });
    }

    /**
     * Apply the Control Center selected/unselected chrome to style cards.
     * @param selectedIndex 0 = classic, 1 = gamepad, 2 = custom
     */
    private void applyStyleSelection(View classic, View gamepad, View custom, int selectedIndex) {
        if (classic != null) {
            classic.setBackgroundResource(selectedIndex == 0
                    ? R.drawable.bg_cs_pack_card_selected
                    : R.drawable.bg_cs_pack_card);
        }
        if (gamepad != null) {
            gamepad.setBackgroundResource(selectedIndex == 1
                    ? R.drawable.bg_cs_pack_card_selected
                    : R.drawable.bg_cs_pack_card);
        }
        if (custom != null) {
            custom.setBackgroundResource(selectedIndex == 2
                    ? R.drawable.bg_cs_pack_card_selected
                    : R.drawable.bg_cs_pack_card);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // CS PREMIUM CURSOR STUDIO — pack browser / categories / favorites /
    // animated live preview. Everything renders through the existing
    // save pipeline (custom-bitmap path), so packs really work in-game.
    // ════════════════════════════════════════════════════════════════

    /** Built-in pack model. tint==null → use drawable's own colors. */
    private static class CursorPack {
        final String id, name, creator, category;
        final int resId;
        final Integer tint;
        final boolean isClassic, isGamepad, centerHotspot;

        CursorPack(String id, String name, String category, int resId,
                   Integer tint, boolean isClassic, boolean isGamepad, boolean centerHotspot) {
            this.id = id; this.name = name; this.creator = "CS Studio"; this.category = category;
            this.resId = resId; this.tint = tint;
            this.isClassic = isClassic; this.isGamepad = isGamepad; this.centerHotspot = centerHotspot;
        }
    }

    private void initPacks(@NonNull View view) {
        mFavPrefs = requireContext().getApplicationContext()
                .getSharedPreferences("liked_cursors", android.content.Context.MODE_PRIVATE);
        mPackGrid = view.findViewById(R.id.cursor_pack_grid);
        mCatChipsBar = view.findViewById(R.id.cursor_cat_chips);
        mPackName = view.findViewById(R.id.cursor_pack_name);
        mPackCreator = view.findViewById(R.id.cursor_pack_creator);
        mPackCategory = view.findViewById(R.id.cursor_pack_category);
        mFavButton = view.findViewById(R.id.btn_favorite_cursor);

        mPacks.clear();
        mPacks.add(new CursorPack("classic", "Classic Arrow", "Classic", R.drawable.ic_mouse_pointer, null, true, false, false));
        mPacks.add(new CursorPack("beam", "Precision Beam", "Classic", R.drawable.ic_cursor_beam, null, false, false, true));
        mPacks.add(new CursorPack("gamepad", "Gamepad Pointer", "Gaming", R.drawable.ic_gamepad_pointer, null, false, true, false));
        mPacks.add(new CursorPack("crosshair", "Crosshair", "Gaming", R.drawable.ic_cursor_crosshair, null, false, false, true));
        mPacks.add(new CursorPack("sniper", "Sniper Dot", "Gaming", R.drawable.ic_cursor_dot, 0xFFFF4D67, false, false, true));
        mPacks.add(new CursorPack("dot", "Focus Dot", "Minimal", R.drawable.ic_cursor_dot, null, false, false, true));
        mPacks.add(new CursorPack("ring", "Pulse Ring", "Minimal", R.drawable.ic_cursor_ring, null, false, false, true));
        mPacks.add(new CursorPack("void", "Void", "Dark", R.drawable.ic_mouse_pointer, 0xFF1B1B24, false, false, false));
        mPacks.add(new CursorPack("midnight", "Midnight", "Dark", R.drawable.ic_mouse_pointer, 0xFF3A4160, false, false, false));
        mPacks.add(new CursorPack("amethyst", "Amethyst", "RGB", R.drawable.ic_mouse_pointer, 0xFF7C5CFF, false, false, false));
        mPacks.add(new CursorPack("volt", "Volt", "RGB", R.drawable.ic_mouse_pointer, 0xFFA6FF3D, false, false, false));
        mPacks.add(new CursorPack("azure", "Azure Ring", "RGB", R.drawable.ic_cursor_ring, 0xFF3DC2FF, false, false, true));
        mPacks.add(new CursorPack("ember", "Ember", "RGB", R.drawable.ic_cursor_crosshair, 0xFFFF4D67, false, false, true));

        buildCategoryChips();
        buildPackGrid();

        // Reflect whatever the loaded state says (classic/gamepad/custom bitmap)
        CursorPack initial = mPacks.get(0);
        if (mUseCustomBitmap) {
            initial = findPackById(mFavPrefs.getString("last_pack_id", "classic"));
        } else if (mSelectedCursorStyleRes == R.drawable.ic_gamepad_pointer) {
            initial = findPackById("gamepad");
        }
        if (initial == null) initial = mPacks.get(0);
        selectPack(initial, false);

        if (mFavButton != null) {
            mFavButton.setOnClickListener(v -> toggleFavorite());
            refreshFavoriteButton();
        }
    }

    private CursorPack findPackById(String id) {
        if (id == null) return null;
        for (CursorPack p : mPacks) if (p.id.equals(id)) return p;
        return null;
    }

    private void buildCategoryChips() {
        if (mCatChipsBar == null || getContext() == null) return;
        mCatChipsBar.removeAllViews();
        final String[] cats = {"All", "Classic", "Gaming", "Minimal", "Dark", "RGB"};
        float d = getResources().getDisplayMetrics().density;
        for (String cat : cats) {
            TextView chip = new TextView(requireContext());
            chip.setText(cat);
            chip.setTextSize(11f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding((int) (d * 16), 0, (int) (d * 16), 0);
            chip.setMinHeight((int) (d * 32));
            chip.setTypeface(null, android.graphics.Typeface.BOLD);
            chip.setTag(cat);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (d * 7);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                mCurrentCategory = (String) v.getTag();
                refreshChipStyles();
                filterPackGrid();
            });
            mCatChipsBar.addView(chip);
        }
        refreshChipStyles();
    }

    private void refreshChipStyles() {
        if (mCatChipsBar == null) return;
        for (int i = 0; i < mCatChipsBar.getChildCount(); i++) {
            View c = mCatChipsBar.getChildAt(i);
            boolean active = mCurrentCategory.equals(c.getTag());
            c.setBackgroundResource(active ? R.drawable.bg_cs_tab_pill_active : R.drawable.bg_cs_tab_pill_idle);
            ((TextView) c).setTextColor(android.graphics.Color.parseColor(active ? "#FFFFFF" : "#8B8FA3"));
        }
    }

    private void buildPackGrid() {
        if (mPackGrid == null || getContext() == null) return;
        mPackGrid.removeAllViews();
        mPackCards.clear();
        float d = getResources().getDisplayMetrics().density;
        int idx = 0;
        for (CursorPack p : mPacks) {
            boolean fav = mFavPrefs.getBoolean(p.id, false);

            android.widget.FrameLayout card = new android.widget.FrameLayout(requireContext());
            card.setBackgroundResource(R.drawable.bg_cs_pack_card);
            card.setTag(p);

            LinearLayout inner = new LinearLayout(requireContext());
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = (int) (d * 10);
            inner.setPadding(pad, pad, pad, pad);

            ImageView preview = new ImageView(requireContext());
            Bitmap b = renderPackBitmap(p);
            if (b != null) preview.setImageBitmap(b);
            LinearLayout.LayoutParams pv = new LinearLayout.LayoutParams((int) (d * 42), (int) (d * 42));
            preview.setLayoutParams(pv);
            inner.addView(preview);

            TextView name = new TextView(requireContext());
            name.setText(p.name);
            name.setTextSize(10.5f);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setTextColor(0xFFFFFFFF);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            np.topMargin = (int) (d * 7);
            name.setLayoutParams(np);
            inner.addView(name);

            TextView cat = new TextView(requireContext());
            cat.setText(p.category);
            cat.setTextSize(8.5f);
            cat.setTextColor(0xFF666B7E);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = (int) (d * 1);
            cat.setLayoutParams(cp);
            inner.addView(cat);

            card.addView(inner);

            ImageView heart = new ImageView(requireContext());
            heart.setImageResource(fav ? R.drawable.ic_heart_filled : R.drawable.ic_heart_outline);
            heart.setColorFilter(fav ? 0xFFFF2D55 : 0xFF666B7E);
            android.widget.FrameLayout.LayoutParams hp = new android.widget.FrameLayout.LayoutParams(
                    (int) (d * 14), (int) (d * 14), Gravity.TOP | Gravity.END);
            hp.topMargin = (int) (d * 6);
            hp.rightMargin = (int) (d * 6);
            heart.setLayoutParams(hp);
            heart.setTag("fav_" + p.id);
            card.addView(heart);

            card.setOnClickListener(v -> selectPack(p, true));

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            int m = (int) (d * 4);
            lp.setMargins(m, m, m, m);
            card.setLayoutParams(lp);

            // Staggered entrance
            card.setAlpha(0f);
            card.setTranslationY((float) (d * 14));
            card.animate().alpha(1f).translationY(0f)
                    .setStartDelay(60 + (idx++ % 6) * 40L)
                    .setDuration(260)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            mPackGrid.addView(card);
            mPackCards.add(card);
        }
    }

    private void filterPackGrid() {
        for (View card : mPackCards) {
            CursorPack p = (CursorPack) card.getTag();
            boolean show = "All".equals(mCurrentCategory) || mCurrentCategory.equals(p.category);
            card.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /** Apply a pack: routes through the existing classic/gamepad/custom paths. */
    private void selectPack(CursorPack pack, boolean animate) {
        if (pack == null || getView() == null) return;
        mCurrentPack = pack;
        mFavPrefs.edit().putString("last_pack_id", pack.id).apply();

        View root = getView();
        View classic = root.findViewById(R.id.style_classic);
        View gamepad = root.findViewById(R.id.style_gamepad);
        View custom = root.findViewById(R.id.style_custom);

        if (pack.isClassic) {
            mUseCustomBitmap = false;
            mSelectedCursorStyleRes = R.drawable.ic_mouse_pointer;
            mHotspotX = 0; mHotspotY = 0;
            applyStyleSelection(classic, gamepad, custom, 0);
        } else if (pack.isGamepad) {
            mUseCustomBitmap = false;
            mSelectedCursorStyleRes = R.drawable.ic_gamepad_pointer;
            mHotspotX = 0; mHotspotY = 0;
            applyStyleSelection(classic, gamepad, custom, 1);
        } else {
            Bitmap b = renderPackBitmap(pack);
            if (b == null) return;
            mCurrentCursorBitmap = b;
            mUseCustomBitmap = true;
            if (pack.centerHotspot) {
                mHotspotX = b.getWidth() / 2;
                mHotspotY = b.getHeight() / 2;
            } else {
                mHotspotX = 0; mHotspotY = 0;
            }
            applyStyleSelection(classic, gamepad, custom, 2);
        }

        if (mPackName != null) mPackName.setText(pack.name);
        if (mPackCreator != null) mPackCreator.setText("by " + pack.creator);
        if (mPackCategory != null) mPackCategory.setText(pack.category.toUpperCase(java.util.Locale.US));

        // Selection ring on the grid card
        if (mSelectedPackCard != null) mSelectedPackCard.setBackgroundResource(R.drawable.bg_cs_pack_card);
        for (View card : mPackCards) {
            if (card.getTag() == pack) {
                mSelectedPackCard = card;
                card.setBackgroundResource(R.drawable.bg_cs_pack_card_selected);
                if (animate) {
                    card.animate().cancel();
                    card.setScaleX(0.93f); card.setScaleY(0.93f);
                    card.animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(new OvershootInterpolator(2f)).start();
                }
                break;
            }
        }

        refreshFavoriteButton();
        updateLivePreview();
        if (animate) playPreviewPop();
    }

    /** Rasterize any pack (PNG or vector, optional tint) into a cursor bitmap. */
    private Bitmap renderPackBitmap(CursorPack pack) {
        try {
            android.content.Context ctx = getContext();
            if (ctx == null) return null;
            Drawable d = ContextCompat.getDrawable(ctx, pack.resId);
            if (d == null) return null;
            d = d.mutate();
            if (pack.tint != null) d.setColorFilter(pack.tint, PorterDuff.Mode.SRC_IN);
            int size = 96;
            Bitmap bmp;
            if (d instanceof BitmapDrawable && pack.tint == null) {
                bmp = BitmapFactory.decodeResource(getResources(), pack.resId);
            } else {
                bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                d.setBounds(0, 0, size, size);
                d.draw(canvas);
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private void toggleFavorite() {
        if (mCurrentPack == null || mFavPrefs == null) return;
        boolean nowFav = !mFavPrefs.getBoolean(mCurrentPack.id, false);
        mFavPrefs.edit().putBoolean(mCurrentPack.id, nowFav).apply();
        // Update the mini heart on the grid card
        for (View card : mPackCards) {
            if (card.getTag() == mCurrentPack) {
                View heart = card.findViewWithTag("fav_" + mCurrentPack.id);
                if (heart instanceof ImageView) {
                    ((ImageView) heart).setImageResource(nowFav ? R.drawable.ic_heart_filled : R.drawable.ic_heart_outline);
                    ((ImageView) heart).setColorFilter(nowFav ? 0xFFFF2D55 : 0xFF666B7E);
                }
                break;
            }
        }
        refreshFavoriteButton();
        if (mFavButton != null && nowFav) {
            mFavButton.animate().cancel();
            mFavButton.setScaleX(0.7f); mFavButton.setScaleY(0.7f);
            mFavButton.animate().scaleX(1f).scaleY(1f).setDuration(240)
                    .setInterpolator(new OvershootInterpolator(2f)).start();
        }
    }

    private void refreshFavoriteButton() {
        if (mFavButton == null || mCurrentPack == null || mFavPrefs == null) return;
        boolean fav = mFavPrefs.getBoolean(mCurrentPack.id, false);
        mFavButton.setImageResource(fav ? R.drawable.ic_heart_filled : R.drawable.ic_heart_outline);
        mFavButton.setColorFilter(fav ? 0xFFFF2D55 : 0xFFA8ACBF);
    }

    /** Pulse preview loop whose speed follows the Tuning slider. */
    private void setupAnimSpeedPanel(@NonNull View view) {
        SeekBar speed = view.findViewById(R.id.seek_anim_speed);
        TextView speedText = view.findViewById(R.id.anim_speed_value);
        if (speed != null) {
            mAnimSpeedPercent = speed.getProgress();
            speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    mAnimSpeedPercent = Math.max(0, progress);
                    if (speedText != null) speedText.setText(mAnimSpeedPercent + "%");
                    restartPulse();
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }
        if (mPreviewImage != null) mPreviewImage.post(this::restartPulse);
    }

    private long pulseDuration() {
        // 0% → 1700ms (calm), 100% → 320ms (energetic)
        return (long) (1700 - (mAnimSpeedPercent / 100f) * 1380);
    }

    private void restartPulse() {
        stopPulse();
        if (mPreviewImage == null) return;
        ObjectAnimator sx = ObjectAnimator.ofFloat(mPreviewImage, "scaleX", 1f, 1.07f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(mPreviewImage, "scaleY", 1f, 1.07f);
        long dur = pulseDuration();
        sx.setDuration(dur); sy.setDuration(dur);
        sx.setRepeatCount(ObjectAnimator.INFINITE);
        sy.setRepeatCount(ObjectAnimator.INFINITE);
        sx.setRepeatMode(ObjectAnimator.REVERSE);
        sy.setRepeatMode(ObjectAnimator.REVERSE);
        mPulseAnim = new AnimatorSet();
        mPulseAnim.playTogether(sx, sy);
        mPulseAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        mPulseAnim.start();
    }

    private void stopPulse() {
        if (mPulseAnim != null) {
            mPulseAnim.cancel();
            mPulseAnim = null;
        }
    }

    /** Reactive "click test": squash the cursor, then bounce back. */
    private void setupClickTest(@NonNull View view) {
        View stage = view.findViewById(R.id.cursor_preview_container);
        if (stage == null) return;
        stage.setOnClickListener(v -> playPreviewPop());
    }

    private void playPreviewPop() {
        if (mPreviewImage == null) return;
        stopPulse();
        mPreviewImage.animate().cancel();
        mPreviewImage.setScaleX(0.72f);
        mPreviewImage.setScaleY(0.72f);
        mPreviewImage.animate().scaleX(1f).scaleY(1f)
                .setDuration(Math.max(220, pulseDuration() / 2))
                .setInterpolator(new OvershootInterpolator(2.6f))
                .withEndAction(() -> {
                    if (mPreviewImage != null) mPreviewImage.postDelayed(this::restartPulse, 600);
                })
                .start();
    }

    @Override
    public void onPause() {
        stopPulse();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mPreviewImage != null) mPreviewImage.post(this::restartPulse);
    }

    @Override
    public void onDestroyView() {
        stopPulse();
        super.onDestroyView();
    }
}
