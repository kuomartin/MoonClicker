package com.xaxaxax.moonclicker.hiddenapi

import android.os.Build
import com.xaxaxax.moonclicker.hiddenapi.contracts.StubConstantTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.reflect.Modifier

/**
 * Compares each stub constant baked into our callers against the platform's value.
 *
 * This is the highest-stakes half of the hidden-API contract. A wrong *method* signature throws
 * `NoSuchMethodError` the first time it is called; a wrong *constant* makes no sound at all —
 * javac inlines it, so `MoonClickerService` would hand `DisplayManager` a flag bitmask that means
 * something else entirely and the virtual display would just come out subtly wrong.
 *
 * The stub-side values arrive via [StubConstantTable], which is Java so that the inlining is the
 * language spec's guarantee rather than a compiler's choice.
 */
@RunWith(Parameterized::class)
class StubConstantContractTest(private val constant: StubConstantTable.Constant) {

    @Test
    fun stubValueMatchesPlatform() {
        val sdk = Build.VERSION.SDK_INT
        val owner = PlatformReflection.classOrNull(constant.platformOwner)
        assertNotNull("${constant.platformOwner} is missing on API $sdk", owner)

        val field = PlatformReflection.findField(owner!!, constant.name)

        if (sdk < constant.sinceApi) {
            // Below the level we claim the constant from, the field must not exist yet: finding it
            // means the stub's belief about when it was introduced is wrong.
            assertNull(
                "${constant.name} exists on API $sdk but we claim it only from API " +
                    "${constant.sinceApi} — the stub's assumption about when it was introduced is " +
                    "wrong.",
                field,
            )
            return
        }

        if (field == null) {
            fail(
                "${constant.name} is missing on API $sdk but we claim it from API " +
                    "${constant.sinceApi} — the stub's assumption about when it was introduced " +
                    "is wrong.",
            )
            return
        }

        assertTrue(
            "${constant.name} should be a static final int but is ${Modifier.toString(field.modifiers)} " +
                "${field.type.name}",
            Modifier.isStatic(field.modifiers) &&
                Modifier.isFinal(field.modifiers) &&
                field.type == Integer.TYPE,
        )

        field.isAccessible = true
        val platformValue = field.getInt(null)
        assertEquals(
            "${constant.name} is compiled into our callers as 0x${constant.stubValue.toString(16)} but " +
                "API $sdk defines it as 0x${platformValue.toString(16)}. javac inlined the stub's " +
                "value, so every caller passes the wrong value — fix ${constant.stub}.",
            platformValue,
            constant.stubValue,
        )
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun liftNonSdkRestrictions() = PlatformReflection.liftNonSdkRestrictions()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun constants(): List<StubConstantTable.Constant> = StubConstantTable.all()
    }
}
