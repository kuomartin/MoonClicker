package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

/** Lowest API level this app supports; the default lower bound of every contract. */
internal const val MIN_SUPPORTED_API = Build.VERSION_CODES.O_MR1

/** The member a [MemberContract] is about. */
internal sealed interface Member {
    val name: String
}

/**
 * @param parameters source-form type names, e.g. `"int"`, `"java.lang.String"`,
 *   `"android.app.ActivityManager\$RunningTaskInfo"`.
 * @param returns source-form return type, or null to leave the return type unchecked —
 *   use null where the framework's generic signature is not something we depend on.
 */
internal data class MethodMember(
    override val name: String,
    val parameters: List<String> = emptyList(),
    val returns: String? = null,
    val static: Boolean = false,
) : Member

internal data class FieldMember(
    override val name: String,
    val type: String,
    val static: Boolean = false,
) : Member

/**
 * One assertion about the platform: "`owner.member` exists with this shape on API
 * [sinceApi]..[untilApi]".
 *
 * The bounds record **what production code already assumes** — the `Build.VERSION.SDK_INT`
 * branches in `MoonClickerService`/`MoonClickerShizukuService` and the `@RequiresApi`/`@DeprecatedSinceApi`
 * annotations on the stubs. A red test therefore means one of those assumptions is wrong,
 * which is the whole reason this suite exists (see issue #18).
 *
 * The bounds are an iff, not a lower bound: within [sinceApi]..[untilApi] the member must exist
 * with this shape, and outside it the member must not exist at all. A member that lingers past
 * [untilApi] (platforms rarely delete hidden members cleanly) or shows up before [sinceApi] is
 * exactly the kind of wrong assumption this suite exists to catch (see issue #18).
 */
internal data class MemberContract(
    val owner: String,
    val member: Member,
    val sinceApi: Int = MIN_SUPPORTED_API,
    val untilApi: Int = Int.MAX_VALUE,
    val note: String? = null,
) {
    /** Also the JUnit parameter name, so a failure report names the member. */
    override fun toString(): String {
        val signature = when (member) {
            is MethodMember -> "${member.name}(${member.parameters.joinToString(", ") { it.simpleName() }})"
            is FieldMember -> member.name
        }
        return "${owner.simpleName()}.$signature"
    }
}

/**
 * The contracts for one stub file in `:hidden-api`.
 *
 * @param stubs each stub class this file covers, mapped to the platform class it compiles to —
 *   the `@RefineAs` target, or the stub's own name when it needs no refine. Usually one entry;
 *   `RunningTaskInfoHidden` and `RunningTaskInfoHidden_API_27` share one set of contracts.
 */
internal class StubContracts(
    val stubs: Map<String, String>,
    val contracts: List<MemberContract>,
)

internal fun String.simpleName(): String = substringAfterLast('.').substringAfterLast('$')
