package android.os;

/**
 * Compile-time stub for the @hide ServiceManager.
 * Doesn't need to use @RefineAs, because ServiceManager is not visible in public SDK.
 */
public final class ServiceManager {
    public static IBinder getService(String name) {
        throw new RuntimeException("Stub!");
    }
}
