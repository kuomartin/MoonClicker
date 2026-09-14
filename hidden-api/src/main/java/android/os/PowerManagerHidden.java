package android.os;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(PowerManager.class)
public class PowerManagerHidden {
    /** @hide android.os.PowerManager.WAKE_REASON_APPLICATION，@SystemApi 不在 public SDK 裡。 */
    public static final int WAKE_REASON_APPLICATION = 2;

    public void wakeUp(long time, int reason, String details, int displayId) {
        throw new RuntimeException("Stub!");
    }
}
