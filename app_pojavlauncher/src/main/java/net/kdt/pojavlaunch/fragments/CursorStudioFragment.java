package net.kdt.pojavlaunch.fragments;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.cursor.CursorController;
import net.kdt.pojavlaunch.cursor.CursorRules;
import net.kdt.pojavlaunch.cursor.CursorState;
import net.kdt.pojavlaunch.cursor.CursorStore;
import net.kdt.pojavlaunch.cursor.CursorStyle;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CURSOR STUDIO V2 — Phase 4.
 *
 * <p>Replaces the single-cursor page with a per-state studio:</p>
 * <ul>
 *   <li><b>States</b> — every {@link CursorState} (Arrow, Hand, I-Beam,
 *       Loading, Forbidden, Move, Resize ×5, Help, Crosshair, Text) with a
 *       live preview and the full style editor: scale, rotation, opacity,
 *       glow, shadow, border, color tint, hotspot, animation speed, presets
 *       and PNG/GIF import. All changes preview instantly and apply on
 *       Save.</li>
 *   <li><b>Rules</b> — the user-editable IF-then table
 *       ({@link CursorRules}): every situation can be pointed at any state,
 *       so "Instead of Hand → Sword" is just retargeting the rule.</li>
 * </ul>
 */
public class CursorStudioFragment extends Fragment {

    public static final String TAG = "CursorStudioFragment";

    private CursorState mState = CursorState.ARROW;
    private CursorStyle mStyle = CursorStyle.defaultFor(CursorState.ARROW);

    private LinearLayout mStateChips;
    private ImageView mPreviewImage;
    private TextView mPreviewStatus;
    private LinearLayout mControls;
    private LinearLayout mRulesList;

    private final List<CursorRules.CursorRule> mRules = new ArrayList<>();
    private final List<TextView> mChipViews = new ArrayList<>();

    private final int[] GLOW_COLORS = {
            0xFFA6FF3D, 0xFFFFFFFF, 0xFFD500F9, 0xFFFF3D00, 0xFFFFEA00, 0xFF00E5FF
    };

    private final int[] PRESET_RES = {
            R.drawable.ic_mouse_pointer, R.drawable.ic_cursor_dot, R.drawable.ic_cursor_ring,
            R.drawable.ic_cursor_beam, R.drawable.ic_cursor_crosshair, R.drawable.cursor_hand
    };
    private final String[] PRESET_NAMES = {"Classic", "Dot", "Ring", "Beam", "Crosshair", "Hand"};

    private final ActivityResultLauncher<String> mImportLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) importCursor(uri);
            });

    public CursorStudioFragment() {
        super(R.layout.fragment_cursor_studio);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mStateChips = view.findViewById(R.id.studio_state_chips);
        mPreviewImage = view.findViewById(R.id.studio_preview_image);
        mPreviewStatus = view.findViewById(R.id.studio_preview_status);
        mControls = view.findViewById(R.id.studio_controls);
        mRulesList = view.findViewById(R.id.studio_rules_list);

        view.findViewById(R.id.studio_back_button).setOnClickListener(v -> {
            if (getActivity() != null) getActivity().onBackPressed();
        });

        // System master switch
        TextView master = view.findViewById(R.id.studio_master_switch);
        master.setOnClickListener(v -> {
            boolean enabled = !CursorStore.isSystemEnabled(requireContext());
            CursorStore.setSystemEnabled(requireContext(), enabled);
            master.setText(enabled ? "On" : "Off");
            master.setTextColor(enabled ? 0xFFE4E4EA : 0xFF6B7280);
            CursorController.reset();
        });

        // Tabs
        view.findViewById(R.id.studio_tab_states).setOnClickListener(v -> switchTab(true));
        view.findViewById(R.id.studio_tab_rules).setOnClickListener(v -> switchTab(false));

        // Actions
        view.findViewById(R.id.studio_import_button).setOnClickListener(v ->
                mImportLauncher.launch("image/*"));
        view.findViewById(R.id.studio_reset_button).setOnClickListener(v -> resetCurrentState());
        view.findViewById(R.id.studio_save_button).setOnClickListener(v -> saveCurrentState());
        view.findViewById(R.id.studio_rule_add).setOnClickListener(v -> addRule());
        view.findViewById(R.id.studio_rule_reset).setOnClickListener(v -> {
            mRules.clear();
            mRules.addAll(CursorRules.defaults());
            persistRules();
            rebuildRules();
        });

        buildStateChips();
        selectState(CursorState.ARROW);
        mRules.addAll(CursorStore.getRules(requireContext()));
        rebuildRules();
        UiMotionLike.press(view, R.id.studio_back_button, R.id.studio_tab_states,
                R.id.studio_tab_rules, R.id.studio_import_button, R.id.studio_reset_button,
                R.id.studio_save_button, R.id.studio_rule_add, R.id.studio_rule_reset,
                R.id.studio_master_switch);
    }

    private void switchTab(boolean states) {
        View s = requireView().findViewById(R.id.studio_states_container);
        View r = requireView().findViewById(R.id.studio_rules_container);
        s.setVisibility(states ? View.VISIBLE : View.GONE);
        r.setVisibility(states ? View.GONE : View.VISIBLE);
        TextView tabS = requireView().findViewById(R.id.studio_tab_states);
        TextView tabR = requireView().findViewById(R.id.studio_tab_rules);
        tabS.setBackgroundResource(states ? R.drawable.bg_cursor_btn_primary : R.drawable.bg_cursor_btn_secondary);
        tabS.setTextColor(states ? 0xFF0D0D0D : 0xFFE4E4EA);
        tabR.setBackgroundResource(states ? R.drawable.bg_cursor_btn_secondary : R.drawable.bg_cursor_btn_primary);
        tabR.setTextColor(states ? 0xFFE4E4EA : 0xFF0D0D0D);
    }

    // ───────────────────── STATES TAB ─────────────────────

    private void buildStateChips() {
        mStateChips.removeAllViews();
        mChipViews.clear();
        for (CursorState state : CursorState.values()) {
            TextView chip = new TextView(requireContext());
            chip.setText(state.labelRes);
            chip.setTextSize(11f);
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);
            chip.setTag(state);
            chip.setOnClickListener(v -> selectState((CursorState) v.getTag()));
            mStateChips.addView(chip);
            mChipViews.add(chip);
        }
    }

    private void selectState(CursorState state) {
        mState = state;
        mStyle = CursorStore.getStyle(requireContext(), state);
        // deep copy so "Reset" can be done before Save
        CursorStyle fresh = CursorStyle.defaultFor(state);
        mStyle.copyTo(fresh);
        mStyle = fresh;

        for (int i = 0; i < mChipViews.size(); i++) {
            TextView chip = mChipViews.get(i);
            CursorState chipState = (CursorState) chip.getTag();
            boolean sel = chipState == state;
            chip.setBackgroundResource(sel ? R.drawable.bg_cursor_btn_primary : R.drawable.bg_cursor_chip);
            chip.setTextColor(sel ? 0xFF0D0D0D : 0xFFE4E4EA);
        }
        rebuildControls();
        refreshPreview();
    }

    private void rebuildControls() {
        mControls.removeAllViews();

        // Source / preset row
        LinearLayout presetRow = row();
        TextView presetTitle = label("Art");
        presetRow.addView(presetTitle);
        mControls.addView(presetRow);
        LinearLayout presetChips = new LinearLayout(requireContext());
        presetChips.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < PRESET_RES.length; i++) {
            final int res = PRESET_RES[i];
            final String name = PRESET_NAMES[i];
            TextView chip = new TextView(requireContext());
            chip.setText(name);
            chip.setTextSize(10f);
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(6));
            lp.topMargin = dp(4);
            chip.setLayoutParams(lp);
            chip.setTag(res);
            boolean sel = !mStyle.useCustom && mStyle.presetRes == res;
            chip.setBackgroundResource(sel ? R.drawable.bg_cursor_btn_primary : R.drawable.bg_cursor_chip);
            chip.setTextColor(sel ? 0xFF0D0D0D : 0xFFE4E4EA);
            chip.setOnClickListener(v -> {
                mStyle.useCustom = false;
                mStyle.path = null;
                mStyle.presetRes = res;
                refreshChipStyles(presetChips);
                refreshPreview();
            });
            presetChips.addView(chip);
        }
        mControls.addView(presetChips);

        addSlider("Scale", mStyle.scale, 25, 400, "%",
                v -> { mStyle.scale = v / 100f; refreshPreview(); });
        addSlider("Rotation", mStyle.rotation, -180, 180, "°",
                v -> { mStyle.rotation = v; refreshPreview(); });
        addSlider("Opacity", mStyle.opacity * 100f, 5, 100, "%",
                v -> { mStyle.opacity = v / 100f; refreshPreview(); });
        addSlider("Glow", mStyle.glowRadius, 0, 32, "px",
                v -> { mStyle.glowRadius = (int) v; refreshPreview(); });
        addSlider("Shadow", mStyle.shadowRadius, 0, 24, "px",
                v -> { mStyle.shadowRadius = (int) v; refreshPreview(); });
        addSlider("Border", mStyle.borderWidth, 0, 12, "px",
                v -> { mStyle.borderWidth = v; refreshPreview(); });
        addSlider("Hotspot X", mStyle.hotspotX(mState), 0, 48, "px",
                v -> { mStyle.hotspotX = (int) v; refreshPreview(); });
        addSlider("Hotspot Y", mStyle.hotspotY(mState), 0, 48, "px",
                v -> { mStyle.hotspotY = (int) v; refreshPreview(); });
        addSlider("Anim speed", mStyle.animSpeed * 100f, 25, 300, "%",
                v -> { mStyle.animSpeed = v / 100f; });

        // Glow color chips
        LinearLayout glowRow = row();
        glowRow.addView(label("Glow color"));
        mControls.addView(glowRow);
        LinearLayout glowChips = new LinearLayout(requireContext());
        glowChips.setOrientation(LinearLayout.HORIZONTAL);
        for (int c : GLOW_COLORS) {
            View dot = colorDot(c, mStyle.glowColor == c);
            dot.setOnClickListener(v -> {
                mStyle.glowColor = c;
                refreshColorDots(glowChips, c);
                refreshPreview();
            });
            glowChips.addView(dot);
        }
        mControls.addView(glowChips);

        // Color tint switch + chips
        LinearLayout tintRow = row();
        tintRow.addView(label("Color tint"));
        Switch tintSwitch = new Switch(requireContext());
        tintSwitch.setChecked(mStyle.tintEnabled);
        tintSwitch.setOnCheckedChangeListener((b, checked) -> {
            mStyle.tintEnabled = checked;
            refreshPreview();
        });
        tintRow.addView(tintSwitch);
        mControls.addView(tintRow);
        LinearLayout tintChips = new LinearLayout(requireContext());
        tintChips.setOrientation(LinearLayout.HORIZONTAL);
        for (int c : GLOW_COLORS) {
            View dot = colorDot(c, mStyle.tintEnabled && mStyle.tintColor == c);
            dot.setOnClickListener(v -> {
                mStyle.tintColor = c;
                refreshColorDots(tintChips, c);
                refreshPreview();
            });
            tintChips.addView(dot);
        }
        mControls.addView(tintChips);
    }

    private void refreshChipStyles(LinearLayout container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            TextView chip = (TextView) container.getChildAt(i);
            Object tag = chip.getTag();
            int res = tag instanceof Integer ? (Integer) tag : 0;
            boolean sel = !mStyle.useCustom && mStyle.presetRes == res;
            chip.setBackgroundResource(sel ? R.drawable.bg_cursor_btn_primary : R.drawable.bg_cursor_chip);
            chip.setTextColor(sel ? 0xFF0D0D0D : 0xFFE4E4EA);
        }
    }

    private void refreshColorDots(LinearLayout container, int selected) {
        for (int i = 0; i < container.getChildCount(); i++) {
            FrameLayout wrap = (FrameLayout) container.getChildAt(i);
            Object tag = wrap.getTag();
            boolean sel = tag != null && (Integer) tag == selected;
            View dot = wrap.getChildAt(0);
            if (dot != null && dot.getBackground() instanceof GradientDrawable) {
                ((GradientDrawable) dot.getBackground()).setStroke(dp(sel ? 3 : 1),
                        sel ? 0xFFFFFFFF : 0x33FFFFFF);
            }
        }
    }

    private View colorDot(int color, boolean selected) {
        FrameLayout wrap = new FrameLayout(requireContext());
        int size = dp(30);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(dp(8));
        wrap.setLayoutParams(lp);

        View dot = new View(requireContext());
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        gd.setStroke(dp(selected ? 3 : 1), selected ? 0xFFFFFFFF : 0x33FFFFFF);
        dot.setBackground(gd);
        wrap.addView(dot, new FrameLayout.LayoutParams(size - dp(8), size - dp(8), Gravity.CENTER));
        wrap.setTag(color);
        return wrap;
    }

    private interface SliderListener {
        void onChanged(int value);
    }

    private void addSlider(String title, float value, int min, int max, String suffix,
                           SliderListener onProgress) {
        LinearLayout header = row();
        TextView titleTv = label(title);
        header.addView(titleTv);
        final TextView valueTv = new TextView(requireContext());
        valueTv.setTextColor(0xFFE4E4EA);
        valueTv.setTextSize(11f);
        valueTv.setText(String.format(Locale.US, "%.0f%s", value, suffix));
        header.addView(valueTv);
        mControls.addView(header);

        SeekBar seek = new SeekBar(requireContext());
        seek.setMax(max - min);
        seek.setProgress((int) Math.max(min, Math.min(max, value)) - min);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int val = progress + min;
                valueTv.setText(String.format(Locale.US, "%d%s", val, suffix));
                onProgress.onChanged(val);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        mControls.addView(seek);
    }

    private LinearLayout row() {
        LinearLayout ll = new LinearLayout(requireContext());
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        ll.setPadding(0, dp(8), 0, 0);
        return ll;
    }

    private TextView label(String text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextColor(0xFF9CA3AF);
        tv.setTextSize(11.5f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return tv;
    }

    private void refreshPreview() {
        Bitmap src;
        if (mStyle.isAnimated() && mStyle.path != null) {
            // BitmapFactory cannot decode GIFs — draw the first frame instead.
            src = null;
            try {
                Movie movie = Movie.decodeFile(mStyle.path);
                if (movie != null) {
                    int w = Math.max(1, movie.width());
                    int h = Math.max(1, movie.height());
                    src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(src);
                    movie.setTime(0);
                    movie.draw(c, 0, 0);
                }
            } catch (Throwable ignored) { }
            if (src == null) src = mStyle.loadSource(requireContext(), mState);
        } else {
            src = mStyle.loadSource(requireContext(), mState);
        }
        if (src == null) return;
        CursorStyle.Processed p = mStyle.process(src, mState);
        if (p == null) return;
        int target = dp(88);
        float scale = Math.min(1f, target / (float) Math.max(p.bitmap.getWidth(), p.bitmap.getHeight()));
        Bitmap display = Bitmap.createScaledBitmap(p.bitmap,
                Math.max(1, (int) (p.bitmap.getWidth() * scale)),
                Math.max(1, (int) (p.bitmap.getHeight() * scale)), true);
        mPreviewImage.setImageBitmap(display);
        mPreviewStatus.setText(String.format(Locale.US,
                "%s  •  %d×%d  •  hotspot %d,%d",
                getString(mState.labelRes), p.bitmap.getWidth(), p.bitmap.getHeight(),
                p.hotspotX, p.hotspotY));
    }

    private void resetCurrentState() {
        mStyle = CursorStyle.defaultFor(mState);
        CursorStore.resetStyle(requireContext(), mState);
        rebuildControls();
        refreshPreview();
        CursorController.reset();
        Toast.makeText(requireContext(), R.string.cursor_studio_reset_done, Toast.LENGTH_SHORT).show();
    }

    private void saveCurrentState() {
        CursorStore.setStyle(requireContext(), mState, mStyle);
        CursorController.reset();
        Toast.makeText(requireContext(),
                getString(R.string.cursor_studio_saved) + " — " + getString(mState.labelRes),
                Toast.LENGTH_SHORT).show();
    }

    private void importCursor(Uri uri) {
        try {
            File dir = new File(Tools.DIR_CURSORS, mState.key);
            if (!dir.exists()) dir.mkdirs();
            String ext = "png";
            String name = uri.getLastPathSegment();
            if (name != null) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".gif")) ext = "gif";
                else if (lower.endsWith(".webp")) ext = "webp";
            }
            File dest = new File(dir, System.currentTimeMillis() + "." + ext);
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            }
            mStyle.useCustom = true;
            mStyle.presetRes = 0;
            mStyle.path = dest.getAbsolutePath();
            rebuildControls();
            refreshPreview();
            Toast.makeText(requireContext(), "Imported → " + getString(mState.labelRes),
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(requireContext(), "Import failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ───────────────────── RULES TAB ─────────────────────

    private void addRule() {
        mRules.add(new CursorRules.CursorRule(CursorRules.Trigger.BUTTON, CursorState.HAND));
        persistRules();
        rebuildRules();
    }

    private void rebuildRules() {
        mRulesList.removeAllViews();
        for (CursorRules.CursorRule rule : mRules) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_cursor_panel);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            row.setLayoutParams(lp);

            TextView trigger = new TextView(requireContext());
            trigger.setText(triggerLabel(rule.trigger));
            trigger.setTextColor(0xFFE4E4EA);
            trigger.setTextSize(12f);
            trigger.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));
            row.addView(trigger);

            final CursorRules.CursorRule finalRule = rule;
            Spinner spinner = new Spinner(requireContext());
            ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_dropdown_item, stateLabels());
            spinner.setAdapter(adapter);
            int idx = indexOfState(rule.target);
            spinner.setSelection(Math.max(0, idx));
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                    if (position >= 0 && position < CursorState.values().length) {
                        finalRule.target = CursorState.values()[position];
                        persistRules();
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
            spinner.setLayoutParams(new LinearLayout.LayoutParams(0, dp(38), 1f));
            row.addView(spinner);

            Switch sw = new Switch(requireContext());
            sw.setChecked(rule.enabled);
            sw.setOnCheckedChangeListener((b, checked) -> {
                finalRule.enabled = checked;
                persistRules();
            });
            row.addView(sw);

            TextView del = new TextView(requireContext());
            del.setText("✕");
            del.setTextColor(0xFFFF6B6B);
            del.setTextSize(14f);
            del.setGravity(Gravity.CENTER);
            del.setPadding(dp(10), 0, dp(4), 0);
            del.setOnClickListener(v -> {
                mRules.remove(finalRule);
                persistRules();
                rebuildRules();
            });
            row.addView(del);

            mRulesList.addView(row);
        }
    }

    private List<CharSequence> stateLabels() {
        List<CharSequence> labels = new ArrayList<>();
        for (CursorState s : CursorState.values()) labels.add(getString(s.labelRes));
        return labels;
    }

    private int indexOfState(CursorState target) {
        CursorState[] values = CursorState.values();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) return i;
        }
        return 0;
    }

    private String triggerLabel(CursorRules.Trigger trigger) {
        switch (trigger) {
            case BUTTON: return getString(R.string.cursor_trigger_button);
            case TEXT_INPUT: return getString(R.string.cursor_trigger_text_input);
            case DISABLED: return getString(R.string.cursor_trigger_disabled);
            case DRAGGING: return getString(R.string.cursor_trigger_dragging);
            case LOADING: return getString(R.string.cursor_trigger_loading);
            case HELP: return getString(R.string.cursor_trigger_help);
            case RESIZE: return getString(R.string.cursor_trigger_resize);
            case DEFAULT: default: return getString(R.string.cursor_trigger_default);
        }
    }

    private void persistRules() {
        CursorStore.saveRules(requireContext(), mRules);
        CursorController.reset();
        ExtraCore.setValue(ExtraConstants.CURSOR_STATE_CHANGED, true);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** Tiny press-feedback helper (mirrors UiMotion.pressFeedback). */
    private static final class UiMotionLike {
        static void press(View root, int... ids) {
            for (int id : ids) {
                View v = root.findViewById(id);
                if (v == null) continue;
                v.setOnTouchListener((vv, e) -> {
                    switch (e.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            vv.animate().scaleX(0.94f).scaleY(0.94f).setDuration(60).start();
                            break;
                        case android.view.MotionEvent.ACTION_UP:
                        case android.view.MotionEvent.ACTION_CANCEL:
                            vv.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                            break;
                    }
                    return false;
                });
            }
        }
    }
}
