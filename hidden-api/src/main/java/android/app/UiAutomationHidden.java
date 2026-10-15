package android.app;

import android.os.Looper;

import dev.rikka.tools.refine.RefineAs;

/** Compile-time stub for UiAutomation's @hide constructor and connection methods. */
@RefineAs(UiAutomation.class)
public class UiAutomationHidden {
    public UiAutomationHidden(Looper looper, IUiAutomationConnection connection) {
        throw new RuntimeException("Stub!");
    }

    /** Throws IllegalStateException when another UiAutomation is already registered. */
    public void connect(int flags) {
        throw new RuntimeException("Stub!");
    }

    public void disconnect() {
        throw new RuntimeException("Stub!");
    }
}
