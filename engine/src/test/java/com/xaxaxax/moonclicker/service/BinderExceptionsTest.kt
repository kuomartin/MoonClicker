package com.xaxaxax.moonclicker.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BinderExceptionsTest {

    @Test
    fun exceptions_that_cross_binder_are_passed_through() {
        listOf(
            IllegalStateException("a"),
            IllegalArgumentException("b"),
            SecurityException("c"),
            UnsupportedOperationException("d"),
            NullPointerException("e"),
        ).forEach { assertSame(it, it.toBinderException("op")) }
    }

    @Test
    fun other_exceptions_carry_the_operation_type_and_message_in_their_message() {
        val cause = NoSuchMethodException("setDisplayId")
        val wrapped = cause.toBinderException("injectKeyEvent")

        assertTrue(wrapped is IllegalStateException)
        assertEquals("injectKeyEvent: java.lang.NoSuchMethodException: setDisplayId", wrapped.message)
        assertSame(cause, wrapped.cause)
    }
}
