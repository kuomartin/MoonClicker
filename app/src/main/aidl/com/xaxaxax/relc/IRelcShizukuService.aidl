package com.xaxaxax.relc;

interface IRelcShizukuService {
    boolean setOverlayAllowed(String packageName);
    boolean grantRuntimePermission(String packageName, String permissionName);
    // VirtualDisplay 管理
    int createVirtualDisplay(String name, int width, int height, int densityDpi, in Surface surface);
    boolean setVirtualDisplaySurface(int displayId, in Surface surface);  // 熱插拔 sink
    boolean destroyVirtualDisplay(int displayId);
    // 在指定 Display 中啟動 App
    boolean launchInDisplay(String packageName, int displayId);

}
