package com.xaxaxax.relc;

import android.view.MotionEvent;
import android.view.KeyEvent;

interface IRelcV2Service {
    boolean setOverlayAllowed(String packageName) = 1;
    boolean grantRuntimePermission(String packageName, String permissionName) = 2;
    // VirtualDisplay 管理
    int createVirtualDisplay(String name, int width, int height, int densityDpi, int flags) = 101;
    int addVirtualDisplaySurface(int displayId, in Surface surface) = 102;
    boolean removeVirtualDisplaySurface(int displayId, int handle) = 103;
    boolean destroyVirtualDisplay(int displayId) = 104;

    int[] getVirtualDisplays() = 105;

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

    int[] getDisplaySize(int displayId) = 301;

    String debug(String input) = 1001;
    void destroy() = 16777114;
}
