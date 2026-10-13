package android.companion.virtual;

import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;

import androidx.annotation.RequiresApi;

/**
 * Compile-time stub for the @hide IVirtualDeviceManager Binder interface; only passed to
 * {@link VirtualDeviceManagerHidden}'s constructor.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public interface IVirtualDeviceManager extends IInterface {

    abstract class Stub {
        public static IVirtualDeviceManager asInterface(IBinder obj) {
            throw new RuntimeException("Stub!");
        }
    }
}
