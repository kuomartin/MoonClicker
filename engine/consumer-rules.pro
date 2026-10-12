# AIDL 產生的類別無法加 @Keep；native 以類別名稱與欄位名稱 FindClass／GetFieldID 存取它。
-keep class com.xaxaxax.moonclicker.MoonClickerAppTask {
    java.lang.String packageName;
    int displayId;
}
