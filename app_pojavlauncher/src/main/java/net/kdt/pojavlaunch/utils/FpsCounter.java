package net.kdt.pojavlaunch.utils;

/**
 * FpsCounter — reads the REAL frame rate measured at the GL swap boundary
 * (pojavSwapBuffers counts every presented frame; pojavGetFps reports the
 * per-window rate). 100% accurate for every renderer (gl4es / Zink / osmesa).
 *
 * Values:  >0 fps reading · 0 warming up · -1 window too small / native
 * not ready (caller keeps the last shown value).
 */
public final class FpsCounter {
    private FpsCounter() {}

    private static native int nativeGetFps();

    /** Current FPS. Never throws — native lib glues late on some launches. */
    public static int getFps() {
        try {
            return nativeGetFps();
        } catch (Throwable t) {
            return -1;
        }
    }
}
