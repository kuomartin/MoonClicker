package android.app;

import java.util.List;

/**
 * Compile-time stub for the @hide ActivityTaskManager.
 * Doesn't need to use @RefineAs, because ActivityTaskManager is not visible in public SDK.
 */
public class ActivityTaskManager {
    public static IActivityTaskManager getService() {
        throw new RuntimeException("Stub!");
    }

    public static ActivityTaskManager getInstance() {
        throw new RuntimeException("Stub!");
    }

    public List<ActivityManager.RunningTaskInfo> getTasks(int maxNum) {
        throw new RuntimeException("Stub!");
    }
}