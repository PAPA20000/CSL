package net.kdt.pojavlaunch.cursor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.customcontrols.mouse.CursorManager;

import java.io.File;

/**
 * Per-state cursor configuration + the full image-processing pipeline.
 *
 * <p>One {@link CursorStyle} exists for every {@link CursorState}; it can
 * point at a custom PNG/GIF file or fall back to the built-in vector art,
 * and it stacks: scale → rotate → color-tint → shadow → border → glow →
 * opacity. Every property is persisted (see {@link CursorStore}) and can be
 * changed live from Cursor Studio.</p>
 */
public class CursorStyle {

    public String path;          // nullable → use built-in art
    public boolean useCustom;    // true → render `path` (or presetRes)
    /** Optional preset art (a drawable res) overriding {@code state.defaultDrawable}. */
    public int presetRes = 0;
    public float scale = 1f;     // 0.25 .. 4.0
    public float rotation = 0f;  // degrees
    public float opacity = 1f;   // 0 .. 1
    public int glowRadius = 0;   // px (art space)
    public int glowColor = 0xFFA6FF3D;
    public int shadowRadius = 0; // px
    public int shadowColor = 0x99000000;
    public float shadowOffsetX = 0f;
    public float shadowOffsetY = 0f;
    public float borderWidth = 0f; // px
    public int borderColor = 0xFFFFFFFF;
    public boolean tintEnabled = false;
    public int tintColor = 0xFFFFFFFF;
    public int hotspotX = -1;    // px in the ORIGINAL art (pre-scale); -1 = state default
    public int hotspotY = -1;
    public float animSpeed = 1f; // GIF playback speed multiplier

    public CursorStyle() { }

    public CursorStyle(String path, boolean useCustom, float scale) {
        this.path = path;
        this.useCustom = useCustom;
        this.scale = scale;
    }

    public static CursorStyle defaultFor(CursorState state) {
        CursorStyle s = new CursorStyle(null, false, 1f);
        s.hotspotX = state.defaultHotspotX;
        s.hotspotY = state.defaultHotspotY;
        return s;
    }

    public int hotspotX(CursorState state) {
        return hotspotX >= 0 ? hotspotX : state.defaultHotspotX;
    }

    public int hotspotY(CursorState state) {
        return hotspotY >= 0 ? hotspotY : state.defaultHotspotY;
    }

    /** Source art: custom file when configured + exists, else built-in drawable. */
    @Nullable
    public Bitmap loadSource(Context ctx, CursorState state) {
        if (useCustom && path != null) {
            File f = new File(path);
            if (f.exists() && !f.isDirectory()) {
                try {
                    return BitmapFactory.decodeFile(path);
                } catch (Throwable ignored) { /* fall through to default */ }
            }
        }
        int res = presetRes != 0 ? presetRes : state.defaultDrawable;
        try {
            return BitmapFactory.decodeResource(ctx.getResources(), res);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the configured file is an animated GIF. */
    public boolean isAnimated() {
        return useCustom && path != null && path.toLowerCase().endsWith(".gif");
    }

    /**
     * Result of processing: the final bitmap plus the hotspot offset that
     * accounts for the padding added by shadow / border / glow stages.
     */
    public static final class Processed {
        public final Bitmap bitmap;
        public final int hotspotX;
        public final int hotspotY;
        Processed(Bitmap bmp, int hx, int hy) {
            this.bitmap = bmp;
            this.hotspotX = hx;
            this.hotspotY = hy;
        }
    }

    /**
     * Runs the whole pipeline on {@code src}. {@code state} is only used for
     * hotspot defaults.
     */
    public Processed process(Bitmap src, CursorState state) {
        if (src == null) return null;

        Bitmap current = src;
        int padX = 0, padY = 0;
        float hx = hotspotX(state), hy = hotspotY(state);

        // 1) Scale
        float s = Math.max(0.1f, Math.min(4f, scale));
        if (Math.abs(s - 1f) > 0.001f) {
            int w = Math.max(1, Math.round(current.getWidth() * s));
            int h = Math.max(1, Math.round(current.getHeight() * s));
            Bitmap scaled = Bitmap.createScaledBitmap(current, w, h, true);
            if (scaled != current) current.recycle();
            current = scaled;
            hx *= s;
            hy *= s;
        }

        // 2) Rotation (around centre)
        float r = ((rotation % 360f) + 360f) % 360f;
        if (Math.abs(r) > 0.01f) {
            Matrix rot = new Matrix();
            rot.postRotate(r, current.getWidth() / 2f, current.getHeight() / 2f);
            Bitmap rotated = Bitmap.createBitmap(current, 0, 0, current.getWidth(), current.getHeight(), rot, true);
            if (rotated != current) current.recycle();
            // rotate the hotspot offset around the centre as well
            float cx = current.getWidth() / 2f, cy = current.getHeight() / 2f;
            float dx = hx - cx, dy = hy - cy;
            double rad = Math.toRadians(r);
            hx = (float) (cx + dx * Math.cos(rad) - dy * Math.sin(rad));
            hy = (float) (cy + dx * Math.sin(rad) + dy * Math.cos(rad));
            current = rotated;
        }

        // 3) Color tint (SRC_IN keeps alpha)
        if (tintEnabled) {
            Bitmap tinted = Bitmap.createBitmap(current.getWidth(), current.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas tc = new Canvas(tinted);
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColorFilter(new android.graphics.PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN));
            tc.drawBitmap(current, 0, 0, tp);
            current.recycle();
            current = tinted;
        }

        // 4) Shadow (pad)
        int shadowPad = 0;
        if (shadowRadius > 0) {
            shadowPad = shadowRadius + (int) Math.max(Math.abs(shadowOffsetX), Math.abs(shadowOffsetY));
        }
        int borderPad = borderWidth > 0 ? (int) Math.ceil(borderWidth) : 0;
        int glowPad = glowRadius > 0 ? glowRadius : 0;
        int totalPad = shadowPad + borderPad + glowPad;

        if (totalPad > 0) {
            Bitmap padded = Bitmap.createBitmap(
                    current.getWidth() + totalPad * 2,
                    current.getHeight() + totalPad * 2,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(padded);
            canvas.drawColor(Color.TRANSPARENT);
            padX = totalPad;
            padY = totalPad;

            // shadow first
            if (shadowRadius > 0) {
                Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                sp.setColor(shadowColor);
                sp.setMaskFilter(new android.graphics.BlurMaskFilter(
                        shadowRadius, android.graphics.BlurMaskFilter.Blur.NORMAL));
                Bitmap alpha = current.extractAlpha();
                canvas.drawBitmap(alpha, padX + shadowOffsetX, padY + shadowOffsetY, sp);
                alpha.recycle();
            }

            // border: 8-direction stroke of the alpha mask
            if (borderWidth > 0) {
                Bitmap alpha = current.extractAlpha();
                Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
                bp.setColor(borderColor);
                float bw = borderWidth;
                canvas.drawBitmap(alpha, padX - bw, padY, bp);
                canvas.drawBitmap(alpha, padX + bw, padY, bp);
                canvas.drawBitmap(alpha, padX, padY - bw, bp);
                canvas.drawBitmap(alpha, padX, padY + bw, bp);
                canvas.drawBitmap(alpha, padX - bw, padY - bw, bp);
                canvas.drawBitmap(alpha, padX + bw, padY - bw, bp);
                canvas.drawBitmap(alpha, padX - bw, padY + bw, bp);
                canvas.drawBitmap(alpha, padX + bw, padY + bw, bp);
                alpha.recycle();
            }

            canvas.drawBitmap(current, padX, padY, null);
            current.recycle();
            current = padded;
        }

        // 5) Glow (outer blur, pads further by glowRadius)
        if (glowRadius > 0) {
            Bitmap glowing = CursorManager.applyGlow(current, glowRadius, glowColor);
            if (glowing != current) current.recycle();
            current = glowing;
        }

        // 6) Opacity
        if (opacity < 0.999f) {
            Bitmap opaque = Bitmap.createBitmap(current.getWidth(), current.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas oc = new Canvas(opaque);
            Paint op = new Paint(Paint.ANTI_ALIAS_FLAG);
            op.setAlpha((int) (Math.max(0f, Math.min(1f, opacity)) * 255));
            oc.drawBitmap(current, 0, 0, op);
            current.recycle();
            current = opaque;
        }

        return new Processed(current,
                Math.round(hx + padX + glowPad),
                Math.round(hy + padY + glowPad));
    }

    /** Copies every field into {@code dst}. */
    public void copyTo(CursorStyle dst) {
        dst.path = path;
        dst.useCustom = useCustom;
        dst.presetRes = presetRes;
        dst.scale = scale;
        dst.rotation = rotation;
        dst.opacity = opacity;
        dst.glowRadius = glowRadius;
        dst.glowColor = glowColor;
        dst.shadowRadius = shadowRadius;
        dst.shadowColor = shadowColor;
        dst.shadowOffsetX = shadowOffsetX;
        dst.shadowOffsetY = shadowOffsetY;
        dst.borderWidth = borderWidth;
        dst.borderColor = borderColor;
        dst.tintEnabled = tintEnabled;
        dst.tintColor = tintColor;
        dst.hotspotX = hotspotX;
        dst.hotspotY = hotspotY;
        dst.animSpeed = animSpeed;
    }
}
