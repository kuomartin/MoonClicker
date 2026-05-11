package android.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.DeprecatedSinceApi;
import androidx.annotation.RequiresApi;

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

    int createStackOnDisplay(int displayId);

    void moveTaskToStack(int taskId, int stackId, boolean toTop);

    @RequiresApi(Build.VERSION_CODES.P)
        // takes 1 param since API 28
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum);

    @DeprecatedSinceApi(api = Build.VERSION_CODES.P)
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum, int flags);
}
