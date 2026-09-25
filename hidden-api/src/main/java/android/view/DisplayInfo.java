package android.view;

import android.os.Build;

import androidx.annotation.RequiresApi;

/**
 * Compile-time stub for the @hide DisplayInfo.
 * Doesn't need to use @RefineAs, because DisplayInfo is not visible in public SDK.
 */
public final class DisplayInfo {
    /**
     * The display group this display belongs to. Requesting
     * {@code VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP} does not guarantee a non-default group: with
     * Android 17's separate display-group timeouts enabled, DisplayManagerService overrules it.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    public int displayGroupId;

    public DisplayInfo() { throw new RuntimeException("Stub!"); }
}
