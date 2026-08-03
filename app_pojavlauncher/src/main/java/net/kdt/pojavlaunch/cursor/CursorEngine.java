package net.kdt.pojavlaunch.cursor;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * CursorEngine — the central brain of CS Launcher's desktop-style pointer
 * system. Adapted from the Zalith Launcher 2 architecture (single central
 * cursor-shape state consumed by the hover layer), rebuilt natively for the
 * Android View hierarchy:
 *
 *   view under pointer  ->  CursorRules.resolve()  ->  CursorState
 *   CursorState + CursorAppearance  ->  PointerIcon (system or composed PNG)
 *   ProgressKeeper task count       ->  global WORKING state (busy bridge)
 *
 * The engine never touches the in-game virtual cursor pipeline
 * (Touchpad / CursorManager / CallbackBridge); game activities are excluded.
 * Everything silently no-ops below API 24 where PointerIcon does not exist.
 */
public final class CursorEngine {

    private static final String KEY_ENABLED = "cse_enabled";
    private static final int BASE_ICON_DP = 32;
    private static final int MAX_ICON_PX = 232;   // hard-clamped: PointerIcon bitmaps must stay well under 256px

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static boolean sInstalled;
    private static boolean sEnabled = true;
    private static boolean sEnabledLoaded;
    private static boolean sBusy;
    private static boolean sDragging;

    private static final Map<CursorState, PointerIcon> sIconCache = new EnumMap<>(CursorState.class);
    private static final Map<View, Boolean> sTracked = new WeakHashMap<>();
    private static final ArrayList<WeakReference<ViewGroup>> sRoots = new ArrayList<>();

    private CursorEngine() {}

    // ------------------------------------------------------------------ state

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    public static boolean isEnabled() {
        ensureEnabledLoaded();
        return isSupported() && sEnabled;
    }

    public static boolean isBusy() {
        return sBusy;
    }

    public static boolean isDragging() {
        return sDragging;
    }

    public static void setEnabled(@Nullable android.content.Context context, boolean enabled) {
        sEnabled = enabled;
        sEnabledLoaded = true;
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs != null) prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        refreshAll();
    }

    /** Public hook for drag sources (lists, reorder gestures) to flip the MOVE cursor. */
    public static void setDragging(boolean dragging) {
        if (sDragging == dragging) return;
        sDragging = dragging;
        refreshTracked();
    }

    private static void ensureEnabledLoaded() {
        if (sEnabledLoaded) return;
        sEnabledLoaded = true;
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        sEnabled = prefs == null || prefs.getBoolean(KEY_ENABLED, true);
    }

    // ------------------------------------------------------------------ install

    public static void install(@NonNull Application application) {
        if (!isSupported() || sInstalled) return;
        sInstalled = true;
        ensureEnabledLoaded();
        application.registerActivityLifecycleCallbacks(sActivityCallbacks);
        ProgressKeeper.addTaskCountListener(sTaskCountListener);
    }

    private static final TaskCountListener sTaskCountListener =
            new TaskCountListener() {
                @Override public void onUpdateTaskCount(int taskCount) {
                    setBusy(taskCount > 0);
                }
            };

    private static void setBusy(boolean busy) {
        if (sBusy == busy) return;
        sBusy = busy;
        refreshTracked();
    }

    private static final Application.ActivityLifecycleCallbacks sActivityCallbacks =
            new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(@NonNull Activity activity, android.os.Bundle bundle) {
                    attach(activity);
                }
                @Override public void onActivityStarted(@NonNull Activity activity) {}
                @Override public void onActivityResumed(@NonNull Activity activity) {}
                @Override public void onActivityPaused(@NonNull Activity activity) {}
                @Override public void onActivityStopped(@NonNull Activity activity) {}
                @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull android.os.Bundle bundle) {}
                @Override public void onActivityDestroyed(@NonNull Activity activity) {}
            };

    private static boolean isGameActivity(@NonNull Activity activity) {
        String name = activity.getClass().getSimpleName();
        return "MainActivity".equals(name) || "SDLActivity".equals(name)
                || "JavaGUILauncherActivity".equals(name);
    }

    private static void attach(@NonNull Activity activity) {
        if (isGameActivity(activity)) return;
        final ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        synchronized (CursorEngine.class) {
            sRoots.add(new WeakReference<>(root));
        }
        root.post(() -> refreshRoot(root));
        if (activity instanceof AppCompatActivity) {
            ((AppCompatActivity) activity).getSupportFragmentManager()
                    .registerFragmentLifecycleCallbacks(sFragmentCallbacks, true);
        }
    }

    private static final FragmentManager.FragmentLifecycleCallbacks sFragmentCallbacks =
            new FragmentManager.FragmentLifecycleCallbacks() {
                @Override public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                                                            @NonNull View v, @Nullable android.os.Bundle savedInstanceState) {
                    v.post(() -> processTree(v));
                }
            };

    // ------------------------------------------------------------------ resolution

    private static boolean isInteresting(@NonNull View view) {
        return view.isClickable() || view.isLongClickable()
                || view instanceof android.widget.EditText
                || view instanceof android.widget.AbsSeekBar;
    }

    @TargetApi(24)
    private static void updateView(@NonNull View view) {
        if (!isEnabled()) return;
        // Only interactive surfaces get a dedicated icon; everything else
        // inherits the window root's icon which we manage separately.
        boolean isRoot = view.getTag(net.kdt.pojavlaunch.R.id.cse_engine_root_tag) instanceof Boolean;
        if (!isInteresting(view) && !isRoot) return;
        CursorState state = CursorRules.resolve(view, sBusy, sDragging);
        view.setPointerIcon(getIcon(view.getContext(), state));
        synchronized (sTracked) {
            sTracked.put(view, Boolean.TRUE);
        }
    }

    @TargetApi(24)
    private static void processTree(@NonNull View root) {
        if (!isEnabled()) return;
        walk(root);
    }

    private static void walk(@NonNull View view) {
        updateView(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                walk(group.getChildAt(i));
            }
        }
    }

    @TargetApi(24)
    private static void refreshRoot(@NonNull ViewGroup root) {
        root.setTag(net.kdt.pojavlaunch.R.id.cse_engine_root_tag, Boolean.TRUE);
        if (!isEnabled()) {
            root.setPointerIcon(null);
            return;
        }
        updateView(root);
        processTree(root);
    }

    private static void refreshTracked() {
        if (!isSupported()) return;
        Runnable refresh = () -> {
            ArrayList<View> snapshot;
            synchronized (sTracked) {
                snapshot = new ArrayList<>(sTracked.keySet());
            }
            for (View view : snapshot) {
                if (!isEnabled()) {
                    view.setPointerIcon(null);
                } else {
                    CursorState state = CursorRules.resolve(view, sBusy, sDragging);
                    view.setPointerIcon(getIcon(view.getContext(), state));
                }
            }
            ArrayList<WeakReference<ViewGroup>> roots;
            synchronized (CursorEngine.class) {
                roots = new ArrayList<>(sRoots);
            }
            for (WeakReference<ViewGroup> ref : roots) {
                ViewGroup root = ref.get();
                if (root == null) continue;
                if (!isEnabled()) root.setPointerIcon(null);
                else updateView(root);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) refresh.run();
        else MAIN.post(refresh);
    }

    public static void refreshAll() {
        refreshTracked();
    }

    public static void invalidate(@NonNull CursorState state) {
        synchronized (sIconCache) {
            sIconCache.remove(state);
        }
        refreshTracked();
    }

    public static void invalidateAll() {
        synchronized (sIconCache) {
            sIconCache.clear();
        }
        refreshTracked();
    }

    // ------------------------------------------------------------------ icon factory

    @TargetApi(24)
    @NonNull
    public static PointerIcon getIcon(@NonNull android.content.Context context, @NonNull CursorState state) {
        synchronized (sIconCache) {
            PointerIcon icon = sIconCache.get(state);
            if (icon != null) return icon;
        }
        PointerIcon built = buildIcon(context, state);
        synchronized (sIconCache) {
            sIconCache.put(state, built);
        }
        return built;
    }

    @TargetApi(24)
    @NonNull
    private static PointerIcon buildIcon(@NonNull android.content.Context context, @NonNull CursorState state) {
        try {
            CursorAppearance appearance = CursorAppearance.load(state);
            if (appearance.customBitmap && appearance.bitmapPath != null) {
                Bitmap source = decodeFile(appearance.bitmapPath);
                if (source != null) {
                    int basePx = Math.round(BASE_ICON_DP * density(context));
                    Bitmap composed = renderWithAppearance(context, source, appearance, basePx);
                    if (composed != null) {
                        int[] hotspot = computeHotspot(source, appearance, composed, basePx, density(context));
                        return PointerIcon.create(composed, hotspot[0], hotspot[1]);
                    }
                }
            }
            return PointerIcon.getSystemIcon(context, state.systemType);
        } catch (Throwable t) {
            try {
                return PointerIcon.getSystemIcon(context, CursorState.ARROW.systemType);
            } catch (Throwable ignored) {
                return PointerIcon.getSystemIcon(context, 1000);
            }
        }
    }

    private static float density(@NonNull android.content.Context context) {
        return context.getResources().getDisplayMetrics().density;
    }

    @Nullable
    private static Bitmap decodeFile(@Nullable String path) {
        if (path == null) return null;
        File file = new File(path);
        if (!file.exists()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ bitmap pipeline

    /**
     * Full style pipeline shared by the PointerIcon factory and the Studio
     * live preview: scale + rotate, drop shadow, glow, outline border, tint
     * and opacity composed into a single ARGB bitmap.
     */
    @Nullable
    public static Bitmap renderWithAppearance(@NonNull android.content.Context context, @NonNull Bitmap source,
                                              @NonNull CursorAppearance a, int basePx) {
        try {
            float d = density(context);
            float scale = basePx * (a.scalePercent / 100f) / Math.max(source.getWidth(), source.getHeight());
            int w = Math.max(1, Math.round(source.getWidth() * scale));
            int h = Math.max(1, Math.round(source.getHeight() * scale));
            int pad = Math.round((a.glowRadius * 2 + a.shadowRadius * 2 + a.borderWidth + 6) * d);
            while ((w + pad * 2 > MAX_ICON_PX || h + pad * 2 > MAX_ICON_PX) && w > 8 && h > 8) {
                w = (int) (w * 0.85f);
                h = (int) (h * 0.85f);
                scale = w / (float) source.getWidth();
            }
            Bitmap out = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);

            Matrix matrix = new Matrix();
            matrix.setScale(w / (float) source.getWidth(), h / (float) source.getHeight());
            if (a.rotationDeg != 0) matrix.postRotate(a.rotationDeg, w / 2f, h / 2f);
            matrix.postTranslate(pad, pad);

            Bitmap alpha = null;
            if (a.shadowRadius > 0 || (a.glowRadius > 0 && a.glowColor != 0)
                    || (a.borderWidth > 0 && a.borderColor != 0)) {
                alpha = source.extractAlpha();
            }

            // shadow
            if (alpha != null && a.shadowRadius > 0) {
                Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                sp.setColorFilter(new PorterDuffColorFilter(0xA0000000, PorterDuff.Mode.SRC_IN));
                sp.setMaskFilter(new BlurMaskFilter(a.shadowRadius * d, BlurMaskFilter.Blur.NORMAL));
                Matrix sm = new Matrix(matrix);
                sm.postTranslate(d, d * 2);
                canvas.drawBitmap(alpha, sm, sp);
            }

            // glow
            if (alpha != null && a.glowRadius > 0 && a.glowColor != 0) {
                Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
                gp.setColorFilter(new PorterDuffColorFilter(a.glowColor, PorterDuff.Mode.SRC_IN));
                gp.setMaskFilter(new BlurMaskFilter(a.glowRadius * d, BlurMaskFilter.Blur.NORMAL));
                canvas.drawBitmap(alpha, matrix, gp);
            }

            // border: 8-directional silhouette extrusion, cleanest outline at cursor sizes
            if (alpha != null && a.borderWidth > 0 && a.borderColor != 0) {
                Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
                bp.setColorFilter(new PorterDuffColorFilter(a.borderColor, PorterDuff.Mode.SRC_IN));
                float off = a.borderWidth * d;
                for (int k = 0; k < 8; k++) {
                    double ang = Math.PI * k / 4.0;
                    Matrix bm = new Matrix(matrix);
                    bm.postTranslate((float) Math.cos(ang) * off, (float) Math.sin(ang) * off);
                    canvas.drawBitmap(alpha, bm, bp);
                }
            }

            // main bitmap
            Paint main = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            main.setAlpha((int) (255 * (a.opacityPercent / 100f)));
            if (a.tintColor != 0) {
                main.setColorFilter(new PorterDuffColorFilter(a.tintColor, PorterDuff.Mode.MULTIPLY));
            }
            canvas.drawBitmap(source, matrix, main);
            if (alpha != null && alpha != source) alpha.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Hotspot in final composed-bitmap pixels, clamped inside the bitmap. */
    private static int[] computeHotspot(@NonNull Bitmap source, @NonNull CursorAppearance a,
                                        @NonNull Bitmap composed, int basePx, float d) {
        float hx = a.hotspotX >= 0 ? a.hotspotX : source.getWidth() / 2f;
        float hy = a.hotspotY >= 0 ? a.hotspotY : source.getHeight() / 2f;
        float scale = basePx * (a.scalePercent / 100f) / Math.max(source.getWidth(), source.getHeight());
        // Re-derive the geometry that renderWithAppearance produced.
        int w = Math.max(1, Math.round(source.getWidth() * scale));
        int h = Math.max(1, Math.round(source.getHeight() * scale));
        int pad = Math.round((a.glowRadius * 2 + a.shadowRadius * 2 + a.borderWidth + 6) * d);
        if (w + pad * 2 > MAX_ICON_PX || h + pad * 2 > MAX_ICON_PX) {
            float factor = (MAX_ICON_PX - pad * 2f) / Math.max(w, h);
            w = (int) (w * factor);
            h = (int) (h * factor);
            scale = w / (float) source.getWidth();
        }
        Matrix matrix = new Matrix();
        matrix.setScale(w / (float) source.getWidth(), h / (float) source.getHeight());
        if (a.rotationDeg != 0) matrix.postRotate(a.rotationDeg, w / 2f, h / 2f);
        matrix.postTranslate(pad, pad);
        float[] pts = {hx, hy};
        matrix.mapPoints(pts);
        int x = Math.round(pts[0]);
        int y = Math.round(pts[1]);
        x = Math.max(0, Math.min(composed.getWidth() - 1, x));
        y = Math.max(0, Math.min(composed.getHeight() - 1, y));
        return new int[]{x, y};
    }

    // ------------------------------------------------------------------ studio helpers

    /**
     * Renders the live preview bitmap used by Cursor Studio: the imported PNG
     * pushed through the style pipeline, or a generic arrow glyph for states
     * that stay on the system pointer icon.
     */
    @Nullable
    public static Bitmap renderPreview(@NonNull android.content.Context context, @NonNull CursorState state, int basePx) {
        CursorAppearance a = CursorAppearance.load(state);
        Bitmap source = null;
        if (a.customBitmap && a.bitmapPath != null) source = decodeFile(a.bitmapPath);
        if (source == null) source = drawSystemGlyph(basePx * 2);
        return renderWithAppearance(context, source, a, basePx * 2);
    }

    /** Generic arrow glyph, styled like a classic desktop pointer. */
    @NonNull
    private static Bitmap drawSystemGlyph(int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        android.graphics.Path path = new android.graphics.Path();
        float s = px;
        path.moveTo(0.30f * s, 0.12f * s);
        path.lineTo(0.30f * s, 0.74f * s);
        path.lineTo(0.43f * s, 0.63f * s);
        path.lineTo(0.53f * s, 0.86f * s);
        path.lineTo(0.61f * s, 0.82f * s);
        path.lineTo(0.51f * s, 0.59f * s);
        path.lineTo(0.66f * s, 0.59f * s);
        path.close();
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFF2F2F6);
        canvas.drawPath(path, fill);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1.5f, px * 0.02f));
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(0xFF141417);
        canvas.drawPath(path, stroke);
        return out;
    }

    /** Decoded (sampled) source bitmap of a state's imported PNG, for the Studio. */
    @Nullable
    public static Bitmap loadSourceBitmap(@NonNull CursorState state) {
        CursorAppearance a = CursorAppearance.load(state);
        if (!a.customBitmap || a.bitmapPath == null) return null;
        return decodeFile(a.bitmapPath);
    }
}
