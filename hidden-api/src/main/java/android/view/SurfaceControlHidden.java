package android.view;

import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.DeprecatedSinceApi;

import dev.rikka.tools.refine.RefineAs;


@RefineAs(SurfaceControl.class)
public class SurfaceControlHidden {

    // 沒出現在 android 15+；14（UPSIDE_DOWN_CAKE）仍在（apiMatrix 已驗證）。
    @DeprecatedSinceApi(api = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public static IBinder createDisplay(String name, boolean secure) {
        throw new RuntimeException("Stub!");
    }
    public static void openTransaction(){
        throw new RuntimeException("Stub!");
    }
    public static void closeTransaction(){
        throw new RuntimeException("Stub!");
    }
    // 沒出現在 android 15+；14（UPSIDE_DOWN_CAKE）仍在（apiMatrix 已驗證）。
    @DeprecatedSinceApi(api = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public static void setDisplaySurface(IBinder displayToken, Surface surface){
        throw new RuntimeException("Stub!");
    }
    @DeprecatedSinceApi(api = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public static void setDisplayProjection(IBinder displayToken,
                                            int orientation, Rect layerStackRect, Rect displayRect){
        throw new RuntimeException("Stub!");
    }
    @DeprecatedSinceApi(api = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public static void setDisplayLayerStack(IBinder displayToken, int layerStack){
        throw new RuntimeException("Stub!");
    }
    @DeprecatedSinceApi(api = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public static void destroyDisplay(IBinder displayToken){
        throw new RuntimeException("Stub!");
    }
}
