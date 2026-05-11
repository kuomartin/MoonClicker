package android.app;

import android.content.ComponentName;

import dev.rikka.tools.refine.RefineAs;

/**
 * 使用 RefineAs 映射到系統真正的 ActivityManager.RunningTaskInfo
 */
@RefineAs(ActivityManager.RunningTaskInfo.class)
public class RunningTaskInfoHidden {
    public int displayId;
    public int id;
    public ComponentName baseActivity;
//    public ComponentName topActivity;
//    public Bitmap thumbnail;
//    public CharSequence description;
//    public int numActivities;
//    public int numRunning;
//    public int PROPERTY_VALUE_UNSET;
//    public int SELF_MOVABLE_ALLOWED;
//    public int SELF_MOVABLE_DEFAULT;
//    public int SELF_MOVABLE_DENIED;
//    public int SELF_MOVABLE_UNSET;
//    public  android.app.AppCompatTaskInfo appCompatTaskInfo;
//    public  android.content.ComponentName baseActivity;
//    public android.content.Intent baseIntent;
//    public android.net.Uri capturedLink;
//    public long capturedLinkTimestamp;
//    public android.content.res.Configuration configuration;
//    public int defaultMinSize;
//    public int displayAreaFeatureId;
//    public android.graphics.Rect displayCutoutInsets;
//    // move to top
//    // public int displayId;
//    public int effectiveUid;
//    public boolean isActivityStackTransparent;
//    public boolean isAppBubble;
//    public boolean isFocused;
//    public boolean isInteractive;
//    public boolean isRealActivityAppLockEnabled;
//    public boolean isResizeable;
//    public boolean isRunning;
//    public boolean isSleeping;
//    public boolean isTopActivityNoDisplay;
//    public boolean isTopActivityTransparent;
//    public boolean isVisible;
//    public boolean isVisibleRequested;
//    public long lastActiveTime;
//    public android.graphics.Rect lastNonFullscreenBounds;
//    public int lastParentTaskIdBeforePip;
//    public java.util.ArrayList launchCookies;
//    public int launchIntoPipHostTaskId;
//    public boolean leafTaskBoundsFromOptions;
//    public android.content.LocusId mTopActivityLocusId;
//    public int minHeight;
//    public int minWidth;
//    // move to top
//    // public int numActivities;
//    public android.content.ComponentName origActivity;
//    public int parentTaskId;
//    public android.app.PictureInPictureParams pictureInPictureParams;
//    public android.graphics.Point positionInParent;
//    public android.content.ComponentName realActivity;
//    public int requestedVisibleTypes;
//    public int resizeMode;
//    public boolean shouldDockBigOverlays;
//    public boolean supportsMultiWindow;
//    public boolean supportsMultiWindowWithoutConstraints;
//    public android.app.ActivityManager.TaskDescription taskDescription;
//    public int taskId;
//
//    // class not visible
//    // public android.window.WindowContainerToken token;
//
//    // move to top
//    // public android.content.ComponentName topActivity;
//    public android.content.pm.ActivityInfo topActivityInfo;
//    public android.graphics.Rect topActivityMainWindowFrame;
//    public long topActivityRequestOpenInBrowserEducationTimestamp;
//    public int topActivityType;
//    public int userId;
}
