package android.view;

import android.os.Build;
import android.os.IInterface;

import androidx.annotation.DeprecatedSinceApi;
import androidx.annotation.RequiresApi;

/**
 * Compile-time stub for the @hide IWindowManager Binder interface.
 * <p>
 * Hidden-api is compileOnly; at runtime this JVM name resolves to framework's
 * real android.view.IWindowManager.
 */
public interface IWindowManager extends IInterface {

    /**
     * Lock the display orientation to the specified rotation, or to the current
     * rotation if -1.
     * <p>
     * Signature used on API 29..34. From API 35 the {@code caller} overload
     * below replaces it — pick by {@code Build.VERSION.SDK_INT}, calling the
     * wrong one throws {@link NoSuchMethodError}.
     *
     * @param displayId the ID of display which rotation should be frozen.
     * @param rotation one of {@link android.view.Surface#ROTATION_0},
     *        {@link android.view.Surface#ROTATION_90}, {@link android.view.Surface#ROTATION_180},
     *        {@link android.view.Surface#ROTATION_270} or -1 to freeze it to current rotation.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    @DeprecatedSinceApi(api = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    void freezeDisplayRotation(int displayId, int rotation);

    /**
     * Same as {@link #freezeDisplayRotation(int, int)}, with the {@code caller}
     * parameter added in API 35.
     *
     * @param displayId the ID of display which rotation should be frozen.
     * @param rotation one of {@link android.view.Surface#ROTATION_0},
     *        {@link android.view.Surface#ROTATION_90}, {@link android.view.Surface#ROTATION_180},
     *        {@link android.view.Surface#ROTATION_270} or -1 to freeze it to current rotation.
     * @param caller a string identifying the caller, for logging on the framework side.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    void freezeDisplayRotation(int displayId, int rotation, String caller);
}
