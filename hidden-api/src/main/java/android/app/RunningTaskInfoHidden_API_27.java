package android.app;

import android.content.ComponentName;

import dev.rikka.tools.refine.RefineAs;

/**
 * 使用 RefineAs 映射到系統真正的 ActivityManager.RunningTaskInfo
 */
@RefineAs(ActivityManager.RunningTaskInfo.class)
public class RunningTaskInfoHidden_API_27 {
    public int id;
    public ComponentName baseActivity;
}
