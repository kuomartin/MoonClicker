package android.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;

import androidx.annotation.DeprecatedSinceApi;
import androidx.annotation.RequiresApi;

/**
 * Compile-time stub for the @hide IActivityTaskManager Binder interface.
 * <p>
 * Hidden-api is compileOnly; at runtime this JVM name resolves to framework's
 * real android.app.IActivityTaskManager.
 */
public interface IActivityTaskManager extends IInterface {

    @RequiresApi(Build.VERSION_CODES.R)
    int startActivity(
            IApplicationThread caller,
            String callingPackage,
            String callingFeatureId,
            Intent intent,
            String resolvedType,
            IBinder resultTo,
            String resultWho,
            int requestCode,
            int flags,
            ProfilerInfo profilerInfo,
            Bundle options
    );

    // 10 parameters version only work at API 29
    @RequiresApi(Build.VERSION_CODES.Q)
    @DeprecatedSinceApi(
            api = Build.VERSION_CODES.R,
            message = "10 parameters version only work at API 29")
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
}
