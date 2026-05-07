package android.hardware.display;

public class DisplayManagerHidden {
    public static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = 1;
    public static final int VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 << 1;
    public static final int VIRTUAL_DISPLAY_FLAG_SECURE = 1 << 2;
    public static final int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 << 3;
    public static final int VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 1 << 4;
    public static final int VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD = 1 << 5;
    public static final int VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6;
    public static final int VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 << 7;
    public static final int VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    public static final int VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
    public static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;
    public static final int VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    public static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 << 12;
    public static final int VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;
    public static final int VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 << 14;
    public static final int VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 1 << 15;
    public static final int VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED = 1 << 16;
}
