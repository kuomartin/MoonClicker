package android.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import java.util.List;

/**
 * Compile-time stub for the @hide IActivityManager Binder interface.
 * <p>
 * Hidden-api is compileOnly; at runtime this JVM name resolves to framework's
 * real android.app.IActivityManager.
 */
public interface IActivityManager {
    int startActivity(
            IApplicationThread caller,
            String callingPackage,
            Intent intent,
            String resolvedType,
            IBinder resultTo,
            String resultWho,
            int requestCode,
            int flags,
            ProfilerInfo profilerInfo,
            Bundle options
    );

    void moveStackToDisplay(int stackId, int displayId);

    public List<ActivityManager.RunningTaskInfo> getTasks(int maxNum, int flags);
}
