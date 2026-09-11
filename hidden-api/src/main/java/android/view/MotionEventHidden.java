package android.view;

import android.os.Build;

import androidx.annotation.RequiresApi;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(MotionEvent.class)
public class MotionEventHidden {
    @RequiresApi(Build.VERSION_CODES.Q)
    public void setDisplayId(int displayId) {
        throw new RuntimeException("Stub!");
    }
}