package android.app;

import android.graphics.Bitmap;

import dev.rikka.tools.refine.RefineAs;

/**
 * 使用 RefineAs 映射到系統真正的 ActivityManager.RunningTaskInfo
 */
@RefineAs(ActivityManager.RunningTaskInfo.class)
public class RunningTaskInfoHidden {
    public int id;
    public int displayId;
    public Bitmap thumbnail;
    public CharSequence description;
    public int numRunning;
}
