package com.xaxaxax.moonclicker.hiddenapi

import android.os.Build
import java.lang.reflect.Modifier

/** Lowest API level this app supports; the default lower bound of every contract. */
internal val MIN_SUPPORTED_API = Build.VERSION_CODES.O_MR1

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
 * Outside the range the contract makes no claim: the test still runs and still reports, as a
 * skip naming the range, so every member stays visible at every API level in the matrix.
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

internal fun String.simpleName(): String = substringAfterLast('.').substringAfterLast('$')

/**
 * What the platform actually has. [detail] is always populated so an assertion message can
 * explain itself without the reader re-running anything.
 */
internal data class Resolution(
    val found: Boolean,
    val detail: String,
    val mismatches: List<String> = emptyList(),
)

internal fun MemberContract.resolveAgainstPlatform(): Resolution {
    val ownerClass = PlatformReflection.classOrNull(owner)
        ?: return Resolution(found = false, detail = "class $owner is not present on this API level")

    return when (member) {
        is MethodMember -> resolveMethod(ownerClass, member)
        is FieldMember -> resolveField(ownerClass, member)
    }
}

private fun MemberContract.resolveMethod(ownerClass: Class<*>, member: MethodMember): Resolution {
    val parameterTypes = member.parameters.map { name ->
        PlatformReflection.typeOrNull(name)
            ?: return Resolution(
                found = false,
                detail = "parameter type $name is not present on this API level, " +
                    "so ${member.name} cannot have the declared signature",
            )
    }

    val method = PlatformReflection.findMethod(ownerClass, member.name, parameterTypes)
        ?: return Resolution(
            found = false,
            detail = "no method ${member.name}(${member.parameters.joinToString(", ")}) on " +
                "$owner or any supertype; ${ownerClass.overloadsOf(member.name)}",
        )

    val mismatches = buildList {
        if (Modifier.isStatic(method.modifiers) != member.static) {
            add("expected ${if (member.static) "static" else "instance"} but found ${method.modifiers.accessDescription()}")
        }
        member.returns?.let { expected ->
            when (val expectedType = PlatformReflection.typeOrNull(expected)) {
                null -> add("return type $expected is not present on this API level")
                method.returnType -> Unit
                else -> add("expected return type $expected but found ${method.returnType.name}")
            }
        }
    }
    return Resolution(found = true, detail = "found $method", mismatches = mismatches)
}

private fun MemberContract.resolveField(ownerClass: Class<*>, member: FieldMember): Resolution {
    val field = PlatformReflection.findField(ownerClass, member.name)
        ?: return Resolution(
            found = false,
            detail = "no field ${member.name} on $owner or any supertype",
        )

    val mismatches = buildList {
        when (val expectedType = PlatformReflection.typeOrNull(member.type)) {
            null -> add("type ${member.type} is not present on this API level")
            field.type -> Unit
            else -> add("expected type ${member.type} but found ${field.type.name}")
        }
        if (Modifier.isStatic(field.modifiers) != member.static) {
            add("expected ${if (member.static) "static" else "instance"} but found ${field.modifiers.accessDescription()}")
        }
    }
    return Resolution(found = true, detail = "found $field", mismatches = mismatches)
}

/** The overloads that *do* exist, so a miss says what the platform offers instead. */
private fun Class<*>.overloadsOf(name: String): String {
    val overloads = declaredMethods.filter { it.name == name }
    return if (overloads.isEmpty()) {
        "no overload of that name exists either"
    } else {
        "existing overloads: " + overloads.joinToString("; ") { it.toGenericString() }
    }
}

private fun Int.accessDescription(): String = if (Modifier.isStatic(this)) "static" else "instance"
