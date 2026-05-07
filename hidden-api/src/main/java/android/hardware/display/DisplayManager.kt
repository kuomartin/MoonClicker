// 只有當第三方 Stubs 沒有這個方法時才需要這樣做
package android.hardware.display

import dev.rikka.tools.refine.RefineAs

@RefineAs(DisplayManager::class)
class DisplayManagerHidden {
    companion object {
        @JvmStatic
        fun createVirtualDisplay(
            name: String?,
            width: Int,
            height: Int,
            displayIdToMirror: Int,
            surface: android.view.Surface?
        ): VirtualDisplay {
            throw RuntimeException("Stub!")
        }
    }
}