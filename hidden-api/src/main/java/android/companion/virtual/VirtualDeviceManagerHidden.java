package android.companion.virtual;

import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

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
    public VirtualDeviceManager$VirtualDevice createVirtualDevice(int associationId, VirtualDeviceParams params) {
        throw new RuntimeException("Stub!");
    }
}
