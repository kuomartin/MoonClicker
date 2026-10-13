package android.companion.virtual;

import android.os.Build;

import androidx.annotation.RequiresApi;

/** Compile-time stub for the @SystemApi VirtualDeviceParams. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public final class VirtualDeviceParams {
    /** Displays of the device stay unlocked while the phone is locked. */
    public static final int LOCK_STATE_ALWAYS_UNLOCKED = 1;

    public static final class Builder {
        public Builder() {
            throw new RuntimeException("Stub!");
        }

        public Builder setLockState(int lockState) {
            throw new RuntimeException("Stub!");
        }

        public VirtualDeviceParams build() {
            throw new RuntimeException("Stub!");
        }
    }
}
