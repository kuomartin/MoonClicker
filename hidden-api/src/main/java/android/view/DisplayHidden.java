package android.view;

import android.os.Build;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(Display.class)
public class DisplayHidden {
    public int getType() { throw new RuntimeException("Stub!"); }
    public int getLayerStack() { throw new RuntimeException("Stub!"); }
    public boolean getDisplayInfo(DisplayInfo outDisplayInfo) { throw new RuntimeException("Stub!"); }
}
