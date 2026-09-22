package android.os;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(PowerManager.class)
public class PowerManagerHidden {
    /* android.os.PowerManager.WAKE_REASON_APPLICATION (@hide, @SystemApi not in public SDK) */
    public static final int WAKE_REASON_APPLICATION = 2;

    /* android.os.PowerManager.GO_TO_SLEEP_REASON_APPLICATION (@hide, @SystemApi not in public SDK) */
    public static final int GO_TO_SLEEP_REASON_APPLICATION = 0;

    public void wakeUp(long time, int reason, String details, int displayId) {
        throw new RuntimeException("Stub!");
    }

    public void goToSleep(int displayId, long time, int reason, int flags) {
        throw new RuntimeException("Stub!");
    }
}
