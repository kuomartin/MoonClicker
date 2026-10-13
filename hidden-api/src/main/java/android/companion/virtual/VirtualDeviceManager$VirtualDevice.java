package android.companion.virtual;

import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.view.Surface;

import androidx.annotation.RequiresApi;

import java.util.concurrent.Executor;

/**
 * Compile-time stub for the @SystemApi VirtualDeviceManager.VirtualDevice.
 * <p>
 * The nested class is absent from the public SDK, so it is declared top-level under its
 * binary name, as {@code ActivityManager$StackInfo} is.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class VirtualDeviceManager$VirtualDevice {

    /** Deprecated from API 34 but present on every level; the only overload API 33 has. */
    public VirtualDisplay createVirtualDisplay(int width, int height, int densityDpi, Surface surface,
            int flags, Executor executor, VirtualDisplay.Callback callback) {
        throw new RuntimeException("Stub!");
    }

    /** Tears down the device and every display it created. */
    public void close() {
        throw new RuntimeException("Stub!");
    }
}
