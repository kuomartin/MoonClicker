package android.app;

/**
 * Compile-time stub for the @hide ActivityTaskManager.
 * Doesn't need to use @RefineAs, because ActivityTaskManager is not visible in public SDK.
 */
public class ActivityTaskManager {
    public static IActivityTaskManager getService() {
        throw new RuntimeException("Stub!");
    }
}