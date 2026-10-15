package android.app;

import android.os.IBinder;

/**
 * Compile-time stub for the @hide UiAutomationConnection, the in-process end that registers a
 * UiAutomation with the accessibility manager under the caller's (here shell's) permissions.
 */
public final class UiAutomationConnection implements IUiAutomationConnection {
    public UiAutomationConnection() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public IBinder asBinder() {
        throw new RuntimeException("Stub!");
    }
}
