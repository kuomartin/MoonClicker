package android.view;

/**
 * Compile-time stub for the @hide WindowManagerGlobal.
 * Doesn't need to use @RefineAs, because WindowManagerGlobal is not visible in public SDK.
 */
public class WindowManagerGlobal {
    public static WindowManagerGlobal getInstance() { throw new RuntimeException("Stub!"); }
    public static IWindowManager getWindowManagerService()  {
        throw new RuntimeException("Stub!");
    }
}
