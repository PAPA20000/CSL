package net.kdt.pojavlaunch.cursor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GLFW cursor-handle ⇄ shape registry.
 *
 * <p>This is the same architecture Zalith Launcher 2 uses (see its
 * {@code CursorRegistry}) and it fixes the Pojav problem where
 * {@code glfwCreateStandardCursor()} returned a constant and the shape was
 * lost. When the game calls {@code glfwCreateStandardCursor(shape)} the
 * shape is remembered under a unique handle; when {@code glfwSetCursor()}
 * is later called with that handle we can recover the original shape and
 * switch the launcher cursor accordingly.</p>
 *
 * <p>Deliberately free of Android / LWJGL imports so it can be compiled on
 * the game classpath too. Thread-safe: the game calls these from a
 * non-UI thread.</p>
 */
public final class CursorRegistry {

    /** GLFW standard cursor shapes (kept local — the app module does not
     *  compile against the LWJGL module). */
    public static final int GLFW_ARROW_CURSOR = 0x36001;

    private static final Map<Long, Integer> CURSOR_MAP = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> SHAPE_MAP = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(4L);

    public static final long DEFAULT_CURSOR;

    static {
        DEFAULT_CURSOR = registerStandardShape(GLFW_ARROW_CURSOR);
    }

    private CursorRegistry() { }

    /**
     * Remembers a standard GLFW shape and returns a stable unique handle.
     * Reuses the handle for the same shape (like GLFW does internally).
     */
    public static long registerStandardShape(int glfwShape) {
        Long existing = SHAPE_MAP.get(glfwShape);
        if (existing != null) return existing;

        long id = NEXT_ID.getAndIncrement();
        CURSOR_MAP.put(id, glfwShape);
        SHAPE_MAP.put(glfwShape, id);
        return id;
    }

    /**
     * Recovers the standard GLFW shape for a cursor handle. Falls back to
     * the arrow shape for unknown handles.
     */
    public static int getShape(long cursor) {
        return CURSOR_MAP.getOrDefault(cursor, GLFW_ARROW_CURSOR);
    }

    /** Clears the registry (called on game teardown to avoid leaks). */
    public static void clear() {
        CURSOR_MAP.clear();
        SHAPE_MAP.clear();
        SHAPE_MAP.put(GLFW_ARROW_CURSOR, DEFAULT_CURSOR);
        CURSOR_MAP.put(DEFAULT_CURSOR, GLFW_ARROW_CURSOR);
    }
}
