package android.app;

import java.util.List;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(AppOpsManager.class)
public class AppOpsManagerHidden {

    public static int strOpToOp(String op) {
        throw new RuntimeException("Stub!");
    }

    public static int opToDefaultMode(int op) {
        throw new RuntimeException("Stub!");
    }

    /**
     * Package-level entries only; an op that was never set or noted for the package is absent.
     * Returns null when the package has no entries at all.
     */
    public List<AppOpsManager$PackageOps> getOpsForPackage(int uid, String packageName, int[] ops) {
        throw new RuntimeException("Stub!");
    }

    public void setMode(int code, int uid, String packageName, int mode) {
        throw new RuntimeException("Stub!");
    }
}
