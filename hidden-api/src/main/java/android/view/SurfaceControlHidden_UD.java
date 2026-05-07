package android.view;

import android.os.IBinder;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(SurfaceControl.class)
public class SurfaceControlHidden_UD {
    public static void setDisplayPowerMode(IBinder displayToken, int mode) {
        throw new RuntimeException("Stub!");
    }
}
