package com.xaxaxax.moonclicker.hiddenapi

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Asserts that every member `:hidden-api` declares really exists on the platform, with the
 * signature we compiled against, on the API levels production code assumes it does.
 *
 * Run across the `apiMatrix` managed-device group — a single API level only proves the
 * contract there:
 *
 * ```
 * ./gradlew :hidden-api-contract:apiMatrixGroupDebugAndroidTest
 * ```
 *
 * What this can and cannot show: the tests run as the app UID, not shell, so a member guarded
 * by a `signature|privileged` permission (`freezeDisplayRotation` needs `SET_ORIENTATION`)
 * would throw `SecurityException` if invoked. Nothing here invokes anything — every check is a
 * lookup, which answers "does the member exist" without needing the permission to call it.
 */
@RunWith(Parameterized::class)
internal class HiddenApiMemberContractTest(private val contract: MemberContract) {

    @Test
    fun platformMatchesStub() {
        val sdk = Build.VERSION.SDK_INT
        assumeTrue(
            "$contract is only claimed on API ${contract.sinceApi}..${contract.untilApi.orUnbounded()}, " +
                "so API $sdk says nothing about it.${contract.noteSuffix()}",
            sdk in contract.sinceApi..contract.untilApi,
        )

        val resolution = contract.resolveAgainstPlatform()

        assertTrue(
            "$contract should exist on API $sdk but ${resolution.detail}.${contract.noteSuffix()}",
            resolution.found,
        )
        assertEquals(
            "$contract exists on API $sdk but does not match the stub: " +
                "${resolution.mismatches.joinToString("; ")}.${contract.noteSuffix()}",
            emptyList<String>(),
            resolution.mismatches,
        )
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun liftNonSdkRestrictions() = PlatformReflection.liftNonSdkRestrictions()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun contracts(): List<MemberContract> = HIDDEN_API_CONTRACTS
    }
}

private fun Int.orUnbounded(): String = if (this == Int.MAX_VALUE) "∞" else toString()

private fun MemberContract.noteSuffix(): String = note?.let { " ($it)" }.orEmpty()
