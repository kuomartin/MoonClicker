package android.hardware.input;

import android.view.InputEvent;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(InputManager.class)
public class InputManagerHidden {
    public boolean injectInputEvent(InputEvent event, int mode) {
        throw new RuntimeException("Stub!");
    }
}
