package com.xaxaxax.moonclicker.hiddenapi

import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Reflective lookups against the *platform* classes that `:hidden-api`'s stubs stand in for.
 *
 * Every lookup here resolves names at runtime through [Class.forName] and `getDeclared*`, so
 * nothing on this path needs the stub classes — which is the point. The stubs live in the
 * `android.*` namespace and ART's bootclasspath wins over anything in the test APK, so a test
 * that referenced them would silently measure the platform against itself.
 */
internal object PlatformReflection {

    private val loader: ClassLoader = PlatformReflection::class.java.classLoader!!

    private val PRIMITIVES: Map<String, Class<*>> = mapOf(
        "void" to Void.TYPE,
        "boolean" to java.lang.Boolean.TYPE,
        "byte" to java.lang.Byte.TYPE,
        "char" to Character.TYPE,
        "short" to java.lang.Short.TYPE,
        "int" to Integer.TYPE,
        "long" to java.lang.Long.TYPE,
        "float" to java.lang.Float.TYPE,
        "double" to java.lang.Double.TYPE,
    )

    /**
     * Lifts this process's non-SDK interface restrictions.
     *
     * From API 28 the runtime hides greylist/blacklist members from reflection, so without
     * this every lookup below would report a false absence and the whole suite would go red
     * for a reason that has nothing to do with the stubs.
     */
    fun liftNonSdkRestrictions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }

    /** The class, or null when this API level does not have it at all. */
    fun classOrNull(binaryName: String): Class<*>? =
        try {
            Class.forName(binaryName, false, loader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: NoClassDefFoundError) {
            null
        }

    /** A parameter/return type by source name, or null when this API level lacks it. */
    fun typeOrNull(name: String): Class<*>? = PRIMITIVES[name] ?: classOrNull(name)

    /**
     * Methods are searched across the whole hierarchy: the framework routinely pulls a member
     * up into a new supertype between releases (`RunningTaskInfo`'s fields moved onto `TaskInfo`
     * in API 29) without that being a contract change for us.
     */
    fun findMethod(owner: Class<*>, name: String, parameters: List<Class<*>>): Method? {
        val types = parameters.toTypedArray()
        return hierarchyOf(owner).firstNotNullOfOrNull { type ->
            try {
                type.getDeclaredMethod(name, *types)
            } catch (_: NoSuchMethodException) {
                null
            }
        }
    }

    fun findField(owner: Class<*>, name: String): Field? =
        hierarchyOf(owner).firstNotNullOfOrNull { type ->
            try {
                type.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                null
            }
        }

    /** Breadth-first: the class itself, then its superclasses and every interface they reach. */
    private fun hierarchyOf(start: Class<*>): List<Class<*>> {
        val seen = LinkedHashSet<Class<*>>()
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            if (!seen.add(type)) continue
            type.superclass?.let(queue::addLast)
            type.interfaces.forEach(queue::addLast)
        }
        return seen.toList()
    }
}
