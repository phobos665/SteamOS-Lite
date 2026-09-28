package com.steamoslite.wayland;

import android.view.Surface;

/**
 * The embedded Wayland compositor (libbannerwayland.so, lifted from Bannerlator). gamescope runs as
 * a Wayland client of it and hands it every frame as a dma-buf; the compositor imports them into its
 * own Turnip device and draws them onto the session's Surface.
 *
 * <p>Only the entry points this app uses are declared. The library exports more (HDR, screen
 * effects, frame generation); an undeclared native is simply never bound.
 */
public final class WaylandCompositor {
    static {
        System.loadLibrary("bannerwayland");
    }

    private WaylandCompositor() {}

    /** Pointer and touch coordinates are always in this space, whatever the output size. */
    public static final int INPUT_WIDTH = 1920, INPUT_HEIGHT = 1080;

    /** Pointer actions for {@link #nativeSendPointer}. */
    public static final int POINTER_DOWN = 0, POINTER_MOVE = 1, POINTER_UP = 2;

    private static volatile Runnable firstFrameListener;

    /** Fired once, on the compositor thread, when the first client frame reaches the screen. */
    public static void setFirstFrameListener(Runnable r) { firstFrameListener = r; }

    // ---- Called from native. The library looks every one of these up when it starts, so they
    // stay even where the app has no use for them.

    @SuppressWarnings("unused")
    static void onFirstFramePresented() {
        Runnable r = firstFrameListener;
        if (r != null) r.run();
    }

    @SuppressWarnings("unused")
    static void onGameSurface(String window, String gpuName) {}

    @SuppressWarnings("unused")
    static void onGameFrame() {}

    @SuppressWarnings("unused")
    static void onGameProgram(int pid, String program) {}

    @SuppressWarnings("unused")
    static void onPointerLock(boolean locked, int x, int y) {}

    @SuppressWarnings("unused")
    static void onClipboardText(byte[] utf8) {}

    @SuppressWarnings("unused")
    static void onTextInput(boolean enabled, String program, int x, int y, int w, int h) {}

    // ---- Into native.

    /**
     * Starts the compositor rendering to {@code surface}, its socket (wayland-0) in
     * {@code xdgRuntimeDir}. driverPath/libraryName/nativeLibDir pick the Turnip driver through
     * adrenotools; the system Vulkan cannot import dma-bufs and would leave the screen black.
     */
    public static native void nativeStartWithSurface(Surface surface, String xdgRuntimeDir,
                                                     String driverPath, String libraryName,
                                                     String nativeLibDir);

    /** Replaces or clears the output window as the SurfaceView is recreated or destroyed. */
    public static native void nativeSetSurface(Surface surface);

    /** One pointer event; x/y in the {@link #INPUT_WIDTH} x {@link #INPUT_HEIGHT} space. */
    public static native void nativeSendPointer(int action, int x, int y);

    /** One key; evdev = Linux keycode (KEY_A = 30 ...), state 1 = down, 0 = up. */
    public static native void nativeSendKey(int evdev, int state);

    /** One display refresh (Choreographer). The compositor draws its newest state once per tick. */
    public static native void nativeVsync(long frameTimeNanos);

    /** The panel's refresh rate, advertised on the Wayland output. Before the start. */
    public static native void nativeSetOutputRefreshRate(float hz);

    /** The output's mode, which gamescope is sized to. Before the start. */
    public static native void nativeSetOutputSize(int width, int height);
}
