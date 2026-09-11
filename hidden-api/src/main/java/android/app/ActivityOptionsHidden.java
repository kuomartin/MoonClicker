package android.app;

import dev.rikka.tools.refine.RefineAs;


@RefineAs(ActivityOptions.class)
public class ActivityOptionsHidden {
    public ActivityOptions setLaunchDisplayId(int displayId) {
        throw new RuntimeException("Stub!");
    }
}
