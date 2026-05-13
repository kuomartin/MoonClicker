package android.app;

import android.os.IInterface;

/**
 * Compile-time stub for the @hide IApplicationThread Binder interface.
 * <p>
 * We only need this so that IActivityTaskManager.startActivity()'s
 * first parameter has the correct JVM descriptor
 * (Landroid/app/IApplicationThread). We always pass {@code null}.
 * <p>
 * At runtime, hidden-api is compileOnly and not in the APK;
 * the real android.app.IApplicationThread (same JVM name) is resolved
 * from the bootstrap class loader instead.
 */
public interface IApplicationThread extends IInterface {
    // intentionally empty — we never instantiate this
}
