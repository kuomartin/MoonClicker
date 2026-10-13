package android.companion.virtual;

import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.view.Surface;

import androidx.annotation.RequiresApi;

import java.util.concurrent.Executor;

import dev.rikka.tools.refine.RefineAs;

/**
 * VirtualDeviceManager built from our own context, so the package name and attribution it
 * reports are the shell's rather than those of the context {@code getSystemService} would use.
 */
@RefineAs(VirtualDeviceManager.class)
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class VirtualDeviceManagerHidden {

    public VirtualDeviceManagerHidden(IVirtualDeviceManager service, Context context) {
        throw new RuntimeException("Stub!");
    }

    /** @param associationId a CDM association the context's package holds. */
    public VirtualDevice createVirtualDevice(int associationId, VirtualDeviceParams params) {
        throw new RuntimeException("Stub!");
    }

    public static class VirtualDevice {

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
}
