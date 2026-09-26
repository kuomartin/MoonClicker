package android.os;

import androidx.annotation.RequiresApi;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(PowerManager.class)
public class PowerManagerHidden {
    /* android.os.PowerManager.GO_TO_SLEEP_REASON_APPLICATION (@hide, @SystemApi not in public SDK) */
    public static final int GO_TO_SLEEP_REASON_APPLICATION = 0;

    // The displayId overload arrived in API 34 (apiMatrix).
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void goToSleep(int displayId, long time, int reason, int flags) {
        throw new RuntimeException("Stub!");
    }
}
