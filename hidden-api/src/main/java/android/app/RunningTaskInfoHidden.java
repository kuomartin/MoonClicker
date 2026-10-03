package android.app;

import android.content.ComponentName;
import android.os.Build;

import androidx.annotation.RequiresApi;

import dev.rikka.tools.refine.RefineAs;

/**
 * 使用 RefineAs 映射到系統真正的 ActivityManager.RunningTaskInfo
 */
@RefineAs(ActivityManager.RunningTaskInfo.class)
public class RunningTaskInfoHidden {
    // Introduced with TaskInfo in Android 10; RunningTaskInfoHidden_API_27 is the stub without it.
    @RequiresApi(Build.VERSION_CODES.Q)
    public int displayId;
    public int id;
    public ComponentName baseActivity;
}
