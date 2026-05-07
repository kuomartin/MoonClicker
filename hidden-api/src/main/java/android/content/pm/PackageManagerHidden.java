package android.content.pm;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(PackageManager.class)
public class PackageManagerHidden {
    public interface OnPermissionsChangedListener {
        void onPermissionsChanged(int uid);
    }

    public void grantRuntimePermission(String packageName, String permName, android.os.UserHandle user) {
        throw new RuntimeException("Stub!");
    }

    public void addOnPermissionsChangeListener(OnPermissionsChangedListener listener) {
        throw new RuntimeException("Stub!");
    }

    public void removeOnPermissionsChangeListener(OnPermissionsChangedListener listener) {
        throw new RuntimeException("Stub!");
    }
}