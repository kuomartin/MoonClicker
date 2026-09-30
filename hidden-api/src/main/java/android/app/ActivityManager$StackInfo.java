package android.app;

/**
 * Compile-time stub for the @hide ActivityManager.StackInfo, used on API 27–28 only.
 * <p>
 * The platform class is nested and absent from the current SDK, so there is nothing for
 * {@code @RefineAs} to point at. Declaring a top-level class whose binary name is the nested
 * class's binary name makes the compiled references resolve to the framework class at runtime.
 */
public class ActivityManager$StackInfo {
    public int displayId;
    /** Flattened root component of each task, or a bare package name when the task has none. */
    public String[] taskNames;
}
