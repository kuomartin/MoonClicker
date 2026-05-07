package android.view;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(MotionEvent.class)
public class MotionEventHidden {
    public void setDisplayId(int displayId) {
        throw new RuntimeException("Stub!");
    }
}