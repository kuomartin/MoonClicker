package com.xaxaxax.moonclicker;

import android.view.MotionEvent;
import android.view.KeyEvent;
import com.xaxaxax.moonclicker.MoonClickerDisplayInfo;

interface IMoonClickerService {
    boolean setOverlayAllowed(String packageName) = 1;
    boolean grantRuntimePermission(String packageName, String permissionName) = 2;
    // VirtualDisplay 管理
    int createVirtualDisplay(String name, int width, int height, int densityDpi, int flags) = 101;
    int addVirtualDisplaySurface(int displayId, in Surface surface) = 102;
    boolean removeVirtualDisplaySurface(int displayId, int handle) = 103;
    boolean destroyVirtualDisplay(int displayId) = 104;

    int[] getVirtualDisplays() = 105;

    boolean acquireDisplayMirror(int displayId) = 109;
    boolean releaseDisplayMirror(int displayId) = 110;
    boolean isDisplayMirrorActive(int displayId) = 111;

    /**
     * Sets a virtual display's user rotation (Surface.ROTATION_*, 0..3).
     *
     * An app running on the display that declares its own orientation wins: WindowManager
     * ignores this value for it, and that is expected rather than a failure. Returns false
     * only when the call itself could not be made. See issue #16.
     */
    boolean setDisplayRotation(int displayId, int rotation) = 108;
    boolean launchInDisplay(String packageName, int displayId) = 106;
    List<String> getLauncherApps() = 107;

    /**
     * points: flattened [x1, y1, x2, y2, ...]
     */
    oneway void multiTouchSwipe(int pointerId, int displayId, in int[] points, long duration, boolean keep) = 201;

    /**
     * returns flattened [id, x, y, id, x, y, ...]
     */
    int[] getPointers(int displayId) = 202;

    boolean injectMotionEvent(in MotionEvent event, int displayId) = 298;
    boolean injectKeyEvent(in KeyEvent event, int displayId) = 299;

    /** Returns [width, height] in **logical** space — Display.getRealSize(), rotation applied. */
    int[] getDisplaySize(int displayId) = 301;

    /**
     * Returns [width, height] in **surface** space — the size the display's buffers are
     * actually allocated at, which does NOT swap when the display rotates.
     *
     * For a virtual display this is the size it was created with, remembered here rather
     * than reconstructed from (logical size, rotation): those are two separate reads and a
     * rotation landing between them yields a confidently wrong answer. For display 0 there
     * is no creation size, so the service derives it from its own DisplayManager — one
     * process, no cross-process tear. Any other display id returns [0, 0]: we have no
     * warrant to guess at geometry for a display we did not create.
     *
     * Callers wanting the rotated, on-screen size want getDisplaySize instead.
     */
    int[] getDisplaySurfaceSize(int displayId) = 302;

    MoonClickerDisplayInfo getDisplayInfo(int displayId) = 303;
    MoonClickerDisplayInfo[] getDisplayInfos() = 304;

    String debug(String input) = 1001;
    void destroy() = 16777114;
}
