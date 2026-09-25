package com.xaxaxax.moonclicker.hiddenapi

import com.xaxaxax.moonclicker.hiddenapi.contracts.FieldMember
import com.xaxaxax.moonclicker.hiddenapi.contracts.MemberContract
import com.xaxaxax.moonclicker.hiddenapi.contracts.MethodMember
import java.lang.reflect.Modifier

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
