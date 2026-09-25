package com.xaxaxax.moonclicker.hiddenapi

import com.xaxaxax.moonclicker.hiddenapi.contracts.ALL_STUB_CONTRACTS
import com.xaxaxax.moonclicker.hiddenapi.contracts.FieldMember
import com.xaxaxax.moonclicker.hiddenapi.contracts.MemberContract
import com.xaxaxax.moonclicker.hiddenapi.contracts.MethodMember
import com.xaxaxax.moonclicker.hiddenapi.contracts.StubConstantTable
import com.xaxaxax.moonclicker.hiddenapi.contracts.StubContracts
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Checks the contracts against the stubs they restate, so a stub member cannot go unverified.
 *
 * The device-side suites prove each contract against the platform, but only for contracts that
 * exist; a stub member nobody wrote a contract for is compiled against and never checked. This
 * runs on the JVM, where the stubs are the classes reflection sees, and holds the two sides in
 * lockstep:
 *
 *  - every public stub method and field has a contract of the same owner, name and parameters,
 *    and they agree on `static`, return type and field type;
 *  - every contract matches a member of the stub file it is filed under;
 *  - every compile-time constant is in [StubConstantTable], whose device test compares values.
 *
 * Stubs are enumerated from `:hidden-api`'s source tree rather than from the contracts, so a
 * stub file with no contracts file at all is caught too.
 */
class StubCoverageTest {

    @Test
    fun every_stub_member_has_a_matching_contract() {
        val failures = stubMembers()
            .filterNot { it.isConstant }
            .mapNotNull { member ->
                val group = groupOf(member.declaringClass)
                    ?: return@mapNotNull "${member.signature}: ${member.declaringClass.name} is not " +
                        "registered in any StubContracts in ALL_STUB_CONTRACTS"
                val contract = group.contracts.firstOrNull { member.matches(it) }
                    ?: return@mapNotNull "${member.signature}: no matching contract among those " +
                        "filed for ${group.stubs.keys.joinToString(" / ")}"
                member.shapeMismatch(contract)?.let { "${member.signature}: $it" }
            }
        assertNoFailures(failures)
    }

    @Test
    fun every_contract_matches_a_stub_member() {
        val members = stubMembers()
        val failures = ALL_STUB_CONTRACTS.flatMap { group ->
            val groupMembers = members.filter { groupOf(it.declaringClass) === group }
            group.contracts
                .filterNot { contract -> groupMembers.any { it.matches(contract) } }
                .map { "$it: no such member on ${group.stubs.keys.joinToString(" / ")}" }
        }
        assertNoFailures(failures)
    }

    @Test
    fun every_stub_constant_is_in_the_constant_table() {
        val table = StubConstantTable.all()
        val constants = stubMembers().filter { it.isConstant }
        val missing = constants
            .filterNot { member -> table.any { it.stub == member.declaringClass.name && it.name == member.name } }
            .map { "${it.signature}: not in StubConstantTable" }
        val stale = table
            .filterNot { entry -> constants.any { it.declaringClass.name == entry.stub && it.name == entry.name } }
            .map { "StubConstantTable ${it.stub}.${it.name}: no such constant on the stub" }
        assertNoFailures(missing + stale)
    }

    private fun assertNoFailures(failures: List<String>) =
        assertTrue(failures.joinToString(prefix = "\n", separator = "\n"), failures.isEmpty())

    private class StubMember(
        val declaringClass: Class<*>,
        /** The platform class the member resolves to at runtime. */
        val owner: String,
        val name: String,
        /** Platform-side type names, `null` for fields. */
        val parameters: List<String>?,
        /** Return type for methods, field type for fields; platform-side. */
        val type: String,
        val static: Boolean,
        val isConstant: Boolean,
        /** Stub-side, as written in the stub source, for failure messages. */
        val signature: String,
    ) {
        fun matches(contract: MemberContract): Boolean {
            if (contract.owner != owner || contract.member.name != name) return false
            return when (val m = contract.member) {
                is MethodMember -> m.parameters == parameters
                is FieldMember -> parameters == null
            }
        }

        fun shapeMismatch(contract: MemberContract): String? {
            val (expectedStatic, expectedType) = when (val m = contract.member) {
                is MethodMember -> m.static to m.returns
                is FieldMember -> m.static to m.type
            }
            return when {
                expectedStatic != static ->
                    "stub is ${staticness(static)} but the contract says ${staticness(expectedStatic)}"
                expectedType != null && expectedType != type ->
                    "stub has type $type but the contract says $expectedType"
                else -> null
            }
        }

        private fun staticness(static: Boolean) = if (static) "static" else "instance"
    }

    private companion object {
        /** Stub class → platform class, from every registered contracts file. */
        val refines: Map<String, String> =
            ALL_STUB_CONTRACTS.flatMap { it.stubs.entries }.associate { it.key to it.value }

        /** The contracts file covering [stub], matching nested classes through their outer stub. */
        fun groupOf(stub: Class<*>): StubContracts? =
            ALL_STUB_CONTRACTS.firstOrNull { group ->
                group.stubs.keys.any { stub.name == it || stub.name.startsWith("$it$") }
            }

        /** What `@RefineAs` turns a stub type name into; unregistered names pass through. */
        fun platformName(type: Class<*>): String {
            val name = type.name
            refines[name]?.let { return it }
            return refines.entries
                .firstOrNull { name.startsWith("${it.key}$") }
                ?.let { it.value + name.removePrefix(it.key) }
                ?: name
        }

        fun stubMembers(): List<StubMember> = stubClasses().flatMap { stub ->
            val methods = stub.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .map { it.toStubMember(stub) }
            val fields = stub.declaredFields
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .map { it.toStubMember(stub) }
            methods + fields
        }

        fun Method.toStubMember(stub: Class<*>) = StubMember(
            declaringClass = stub,
            owner = platformName(stub),
            name = name,
            parameters = parameterTypes.map(::platformName),
            type = platformName(returnType),
            static = Modifier.isStatic(modifiers),
            isConstant = false,
            signature = "${stub.name}.$name(${parameterTypes.joinToString(", ") { it.name }})",
        )

        fun Field.toStubMember(stub: Class<*>) = StubMember(
            declaringClass = stub,
            owner = platformName(stub),
            name = name,
            parameters = null,
            type = platformName(type),
            static = Modifier.isStatic(modifiers),
            isConstant = Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) &&
                (type.isPrimitive || type == String::class.java),
            signature = "${stub.name}.$name",
        )

        /** Every class `:hidden-api` declares in source, plus their public nested classes. */
        fun stubClasses(): List<Class<*>> {
            val root = File(
                requireNotNull(System.getProperty("hiddenApi.srcDir")) {
                    "hiddenApi.srcDir is not set; run through Gradle"
                },
            )
            val loader = StubCoverageTest::class.java.classLoader
            val topLevel = root.walk()
                .filter { it.isFile && it.extension == "java" }
                .map { it.relativeTo(root).path.removeSuffix(".java").replace(File.separatorChar, '.') }
                .map { Class.forName(it, false, loader) }
                .toList()
            check(topLevel.isNotEmpty()) { "no stub sources under $root" }
            return topLevel.flatMap { it.withNestedClasses() }.sortedBy { it.name }
        }

        fun Class<*>.withNestedClasses(): List<Class<*>> =
            listOf(this) + declaredClasses.filter { Modifier.isPublic(it.modifiers) }.flatMap { it.withNestedClasses() }
    }
}
