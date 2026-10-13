package android.companion;

import android.os.IBinder;
import android.os.IInterface;

import java.util.List;

/**
 * Compile-time stub for the @hide ICompanionDeviceManager Binder interface.
 * <p>
 * Called directly to list and remove the shell package's associations: {@code cmd companiondevice}
 * prints no association id before API 34 and its arguments change across releases, and
 * {@link CompanionDeviceManager} takes the package name from its own context.
 */
public interface ICompanionDeviceManager extends IInterface {

    /**
     * Associations {@code callingPackage} holds for {@code userId}. Returns
     * {@code List<AssociationInfo>} from API 33; earlier levels return {@code List<String>}
     * under the same erased signature, so only call it on API 33+.
     */
    List<AssociationInfo> getAssociations(String callingPackage, int userId);

    /** Removes an association the caller's package holds. API 33+; API 31–32 take a MAC instead. */
    void disassociate(int associationId);

    abstract class Stub {
        public static ICompanionDeviceManager asInterface(IBinder obj) {
            throw new RuntimeException("Stub!");
        }
    }
}
