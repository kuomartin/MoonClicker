package com.xaxaxax.moonclicker;

parcelable MoonClickerDisplayInfo {
    int displayId;
    String name;
    int width;
    int height;
    int densityDpi;
    boolean isPhysical;
    boolean isMirrorActive;
    /** 由本服務建立（在 vdStore 裡）；外部 app 建立的虛擬螢幕為 false。 */
    boolean isManaged;
    /**
     * sleepVirtualDisplay 會接受：本服務建立、實際擁有獨立 display group，且平台有帶 displayId
     * 的 goToSleep（API 34+）。
     */
    boolean canSleep;
    /**
     * 本服務建立時的 surface 尺寸，不隨旋轉改變；非本服務建立的顯示器為 0。
     * 判斷「沿用這個 VD 要不要 resize」比的是這個，不是 getDisplaySurfaceSize 的影格尺寸。
     */
    int surfaceWidth;
    int surfaceHeight;
}
