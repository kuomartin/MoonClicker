package com.xaxaxax.relc;

import android.view.MotionEvent;
import android.view.KeyEvent;

interface IRelcShizukuService {
    boolean setOverlayAllowed(String packageName) = 1;
    boolean grantRuntimePermission(String packageName, String permissionName) = 2;
    // VirtualDisplay 管理
    int createVirtualDisplay(String name, int width, int height, int densityDpi, in Surface surface, boolean destroyContent, boolean sytemDecorations) = 101;
    boolean setVirtualDisplaySurface(int displayId, in Surface surface) = 102;  // 熱插拔 sink
    boolean destroyVirtualDisplay(int displayId) = 103;

    int[] getVirtualDisplays() = 104;
    // 在指定 Display 中啟動 App
    boolean launchInDisplay(String packageName, int displayId) = 105;
    List<String> getLauncherApps() = 106;

    // Input 注入
    boolean injectMotionEvent(in MotionEvent event, int displayId) = 201;
    boolean injectKeyEvent(in KeyEvent event, int displayId) = 202;

    String debug(String input) = 1001;
    void destroy() = 16777114;
}
