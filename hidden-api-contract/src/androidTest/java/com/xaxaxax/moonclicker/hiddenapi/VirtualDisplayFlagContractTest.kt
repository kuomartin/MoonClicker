package com.xaxaxax.moonclicker.hiddenapi

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.reflect.Modifier

/**
 * Compares each `VIRTUAL_DISPLAY_FLAG_*` value baked into our callers against the platform's.
 *
 * This is the highest-stakes half of the hidden-API contract. A wrong *method* signature throws
 * `NoSuchMethodError` the first time it is called; a wrong *constant* makes no sound at all —
 * javac inlines it, so `MoonClickerService` would hand `DisplayManager` a flag bitmask that means
 * something else entirely and the virtual display would just come out subtly wrong.
 *
 * The stub-side values arrive via [VirtualDisplayFlagTable], which is Java so that the inlining
 * is the language spec's guarantee rather than a compiler's choice.
 */
@RunWith(Parameterized::class)
class VirtualDisplayFlagContractTest(private val flag: VirtualDisplayFlagTable.Flag) {

    @Test
    fun stubValueMatchesPlatform() {
        val sdk = Build.VERSION.SDK_INT
        val owner = PlatformReflection.classOrNull(VirtualDisplayFlagTable.PLATFORM_OWNER)
        assertNotNull("${VirtualDisplayFlagTable.PLATFORM_OWNER} is missing on API $sdk", owner)

        val field = PlatformReflection.findField(owner!!, flag.name)

        if (field == null) {
            // At or above the level we claim the flag from, absence is a failure: the stub's
            // belief about when it was introduced is wrong. This must assert, not assume —
            // an assumption failure is reported as *skipped* and would leave the build green.
            if (sdk >= flag.sinceApi) {
                fail(
                    "${flag.name} is missing on API $sdk but we claim it from API " +
                        "${flag.sinceApi} — the stub's assumption about when it was introduced " +
                        "is wrong.",
                )
            }
            // Below that level, absence is expected: skip, so the matrix's older devices stay
            // useful for the flags they do have.
            assumeTrue(
                "${flag.name} does not exist until API ${flag.sinceApi}, so API $sdk says " +
                    "nothing about its value.",
                false,
            )
            return
        }

        assertTrue(
            "${flag.name} should be a static final int but is ${Modifier.toString(field.modifiers)} " +
                "${field.type.name}",
            Modifier.isStatic(field.modifiers) &&
                Modifier.isFinal(field.modifiers) &&
                field.type == Integer.TYPE,
        )

        field.isAccessible = true
        val platformValue = field.getInt(null)
        assertEquals(
            "${flag.name} is compiled into our callers as 0x${flag.stubValue.toString(16)} but " +
                "API $sdk defines it as 0x${platformValue.toString(16)}. javac inlined the stub's " +
                "value, so every virtual display we create passes the wrong flag — fix " +
                "DisplayManagerHidden.",
            platformValue,
            flag.stubValue,
        )
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun liftNonSdkRestrictions() = PlatformReflection.liftNonSdkRestrictions()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun flags(): List<VirtualDisplayFlagTable.Flag> = VirtualDisplayFlagTable.all()
    }
}
