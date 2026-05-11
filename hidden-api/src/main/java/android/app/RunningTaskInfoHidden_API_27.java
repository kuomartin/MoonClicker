package android.app;

import android.content.ComponentName;

import dev.rikka.tools.refine.RefineAs;

/**
 * 使用 RefineAs 映射到系統真正的 ActivityManager.RunningTaskInfo
 */
@RefineAs(ActivityManager.RunningTaskInfo.class)
public class RunningTaskInfoHidden_API_27 {
    public int id;
    //    public int stackId;
    public ComponentName baseActivity;
//    public ComponentName topActivity;
//    public Bitmap thumbnail;
//    public CharSequence description;
//    public int numActivities;
//    public int numRunning;
//    public long lastActiveTime;
//    public boolean supportsSplitScreenMultiWindow;
//    public int resizeMode;
}
