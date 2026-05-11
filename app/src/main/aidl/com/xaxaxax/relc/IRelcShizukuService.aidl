package com.xaxaxax.relc;

import android.view.MotionEvent;
import android.view.KeyEvent;

interface IRelcShizukuService {
    boolean setOverlayAllowed(String packageName);
    boolean grantRuntimePermission(String packageName, String permissionName);
    // VirtualDisplay 管理
    int createVirtualDisplay(String name, int width, int height, int densityDpi, in Surface surface, boolean destroyContent);
    boolean setVirtualDisplaySurface(int displayId, in Surface surface);  // 熱插拔 sink
    boolean destroyVirtualDisplay(int displayId);
    // 在指定 Display 中啟動 App
    boolean launchInDisplay(String packageName, int displayId);

    // Input 注入
    boolean injectMotionEvent(in MotionEvent event, int displayId);
    boolean injectKeyEvent(in KeyEvent event, int displayId);

    String debug(String input);
}
