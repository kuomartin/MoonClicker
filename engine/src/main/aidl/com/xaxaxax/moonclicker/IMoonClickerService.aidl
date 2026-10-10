package com.xaxaxax.moonclicker;

import android.view.MotionEvent;
import android.view.KeyEvent;
import com.xaxaxax.moonclicker.MoonClickerAppTask;
import com.xaxaxax.moonclicker.MoonClickerDisplayInfo;

interface IMoonClickerService {
    boolean setOverlayAllowed(String packageName) = 1;
    boolean grantRuntimePermission(String packageName, String permissionName) = 2;
    // VirtualDisplay 管理
    int createVirtualDisplay(String name, int width, int height, int densityDpi, int flags) = 101;
    /**
     * Attaches a consumer surface to the display's frames (a DisplaySink), owned by [token].
     * While attached, a display owning its display group is kept from idling off. The sink is
     * released on detachDisplaySink(token), when the display is resized or destroyed, or when
     * [token] dies. Returns false if [token] is already attached or the display has no frames.
     */
    boolean attachDisplaySink(int displayId, in Surface surface, IBinder token) = 102;
    /** Returns false if [token] is not attached (never was, already detached, or dropped). */
    boolean detachDisplaySink(IBinder token) = 103;
    boolean destroyVirtualDisplay(int displayId) = 104;

    int[] getVirtualDisplays() = 105;

    boolean acquireDisplayMirror(int displayId) = 109;
    boolean releaseDisplayMirror(int displayId) = 110;
    boolean isDisplayMirrorActive(int displayId) = 111;

    /**
     * Resizes an existing virtual display in place — same displayId, new dimensions.
     *
     * Rebuilds the native GLES distributor at the new size and swaps it in via
     * VirtualDisplay.resize()/setSurface() rather than destroying and recreating the whole
     * VirtualDisplay: callers that only know the displayId (e.g. an already-running script)
     * keep working across the resize. Any sink already attached via attachDisplaySink is
     * dropped with the old distributor and is NOT carried over — its token no longer refers to
     * anything, and the caller must attach again. Returns false (and leaves the display as it
     * was) if resize fails at any step.
     */
    boolean resizeVirtualDisplay(int displayId, int width, int height, int densityDpi) = 112;

    /**
     * Turns off the display group owned by this virtual display (DPMS sleep), without
     * destroying it — the reverse of the implicit wake done on every entry point that
     * operates the display (injected input, launchInDisplay) and of the wake lock held while
     * a sink is attached via attachDisplaySink. Only affects displays this service
     * created that own their display group; returns false otherwise.
     */
    boolean sleepVirtualDisplay(int displayId) = 113;

    /**
     * Whether windows on [displayId] get an input method. Disabled, no IME is bound there:
     * key events reach the focused view as typed, instead of being composed by the user's
     * IME (e.g. Zhuyin turning "rick" into Chinese). Only input sessions started afterwards
     * are affected, so call it before launching apps on the display. Enabling restores the
     * policy the display had before this service disabled it. API 31+; returns false below.
     */
    boolean setDisplayKeyboardEnabled(int displayId, boolean enabled) = 114;

    /**
     * Sets a virtual display's user rotation (Surface.ROTATION_*, 0..3).
     *
     * An app running on the display that declares its own orientation wins: WindowManager
     * ignores this value for it, and that is expected rather than a failure. Returns false
     * only when the call itself could not be made. See issue #16.
     */
    boolean setDisplayRotation(int displayId, int rotation) = 108;
    /**
     * Throws UnsupportedOperationException for a non-default display when the device lacks
     * FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS; returns false for other launch failures.
     */
    boolean launchInDisplay(String packageName, int displayId) = 106;
    /** 讀暖快取，見 [refreshLauncherApps] 與服務啟動時的初次查詢；不會即時重掃 PackageManager。 */
    List<String> getLauncherApps() = 107;
    /** 強制重新查一次 PackageManager 並更新快取，回傳結果同 [getLauncherApps]。手動刷新用。 */
    List<String> refreshLauncherApps() = 305;

    /**
     * 所有顯示器上有 task 的 app，每個 (package, displayId) 一筆，不保證順序。
     * 只有 service、沒有 activity 的 process 不在任何顯示器上，不列入。
     */
    MoonClickerAppTask[] getAppTasks() = 306;

    /**
     * 以 appops 靜音單一 package（PLAY_AUDIO deny、TAKE_AUDIO_FOCUS ignore），與顯示器無關。
     * 靜音前的 mode 由服務持有並持久化：muted = false 時還原，服務停止時全部還原，
     * 服務異常結束則在下次啟動時還原。package 未安裝或 appops 呼叫失敗時回 false。
     */
    boolean setAppMuted(String packageName, boolean muted) = 307;

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
