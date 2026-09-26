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
}
