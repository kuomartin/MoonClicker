package android.app;

import android.os.Build;

import androidx.annotation.RequiresApi;

import java.util.List;

/**
 * Compile-time stub for the @hide ActivityTaskManager.
 * Doesn't need to use @RefineAs, because ActivityTaskManager is not visible in public SDK.
 */
public class ActivityTaskManager {
    // since android 10
    @RequiresApi(Build.VERSION_CODES.Q)
    public static IActivityTaskManager getService() {
        throw new RuntimeException("Stub!");
    }
    // since android 12
    @RequiresApi(Build.VERSION_CODES.S)
    public static ActivityTaskManager getInstance() {
        throw new RuntimeException("Stub!");
    }
    @RequiresApi(Build.VERSION_CODES.S)
    public List<ActivityManager.RunningTaskInfo> getTasks(int maxNum) {
        throw new RuntimeException("Stub!");
    }
}