package android.view;

import android.os.Build;

import androidx.annotation.RequiresApi;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(KeyEvent.class)
public class KeyEventHidden {
    @RequiresApi(Build.VERSION_CODES.Q)
    public void setDisplayId(int displayId) {
        throw new RuntimeException("Stub!");
    }
}