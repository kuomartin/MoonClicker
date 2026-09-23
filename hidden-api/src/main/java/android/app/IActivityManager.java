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

    // Superseded by IActivityTaskManager in API 29, but the platform never removed it — still
    // present through API 36 (apiMatrix). No @DeprecatedSinceApi: that would claim a removal
    // this stub cannot back up.
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
    @DeprecatedSinceApi(api = Build.VERSION_CODES.Q)
    int createStackOnDisplay(int displayId);
    // Superseded in API 29, but the platform kept it through API 30 (apiMatrix) before removing
    // it in API 31.
    @DeprecatedSinceApi(api = Build.VERSION_CODES.S)
    void moveTaskToStack(int taskId, int stackId, boolean toTop);

    @RequiresApi(Build.VERSION_CODES.P)
        // takes 1 param since API 28
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum);

    @DeprecatedSinceApi(api = Build.VERSION_CODES.P)
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum, int flags);
}
