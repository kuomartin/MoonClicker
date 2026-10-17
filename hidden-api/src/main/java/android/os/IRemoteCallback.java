package android.os;

/**
 * Compile-time stub for the @hide IRemoteCallback Binder interface. MoonClicker implements it
 * (subclassing {@link Stub}) to receive {@code PackageManager.registerPackageMonitorCallback}
 * notifications, so the stub needs {@link Stub}'s constructor and {@link Stub#asBinder}, not just
 * the interface type.
 */
public interface IRemoteCallback extends IInterface {
    void sendResult(Bundle data) throws RemoteException;

    abstract class Stub extends Binder implements IRemoteCallback {
        public Stub() {
            throw new RuntimeException("Stub!");
        }

        @Override
        public IBinder asBinder() {
            throw new RuntimeException("Stub!");
        }
    }
}
