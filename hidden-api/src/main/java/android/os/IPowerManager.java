package android.os;

import androidx.annotation.RequiresApi;

/**
 * Compile-time stub for the @hide IPowerManager Binder interface.
 * <p>
 * Hidden-api is compileOnly; at runtime this JVM name resolves to framework's
 * real android.os.IPowerManager. Called directly rather than through
 * {@link PowerManager#newWakeLock} because the wake lock's {@code packageName}
 * has to be the one the calling uid owns, and {@code PowerManager} takes it
 * from its own context.
 */
public interface IPowerManager extends IInterface {

    /**
     * Signature from API 33, when {@code callback} was appended.
     *
     * @param lock token identifying this wake lock; release with the same token.
     * @param flags level and flags as in {@link PowerManager#newWakeLock}.
     * @param tag wake lock tag, shown in dumpsys.
     * @param packageName must belong to the calling uid.
     * @param ws work source, or null to attribute to the caller.
     * @param historyTag battery history tag, or null.
     * @param displayId display whose display group this wake lock keeps awake;
     *        {@link android.view.Display#INVALID_DISPLAY} for the default group.
     * @param callback notified when the wake lock is disabled/enabled, or null.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    void acquireWakeLock(IBinder lock, int flags, String tag, String packageName, WorkSource ws,
            String historyTag, int displayId, IWakeLockCallback callback);

    /**
     * @param lock token passed to {@link #acquireWakeLock}.
     * @param flags {@link PowerManager#RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY} or 0.
     */
    void releaseWakeLock(IBinder lock, int flags);

    abstract class Stub {
        public static IPowerManager asInterface(IBinder obj) {
            throw new RuntimeException("Stub!");
        }
    }
}
