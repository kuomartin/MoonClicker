package com.xaxaxax.moonclicker.service

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import java.io.FileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySinksTest {

    /** client 端的 token：只關心 linkToDeath 與讓測試觸發死亡。 */
    private class FakeToken(private val deadAlready: Boolean = false) : IBinder {
        val recipients = mutableListOf<IBinder.DeathRecipient>()

        fun die() = recipients.toList().forEach { it.binderDied() }

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (deadAlready) throw RemoteException()
            recipients += recipient
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean =
            recipients.remove(recipient)

        override fun getInterfaceDescriptor(): String? = null
        override fun pingBinder(): Boolean = !deadAlready
        override fun isBinderAlive(): Boolean = !deadAlready
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        override fun dump(fd: FileDescriptor, args: Array<out String>?) = Unit
        override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) = Unit
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
    }

    /** distributor 與 wake lock 都換成紀錄：掛了哪些 handle、撐著哪些顯示器。 */
    private class World {
        var nextHandle = 0
        val attached = mutableSetOf<Pair<Int, Int>>() // distributorId to handle
        val distributors = mutableSetOf(1, 2, 50)
        val ownsGroup = mutableSetOf(1, 2)
        val awake = mutableListOf<Int>() // 每個元素是一個持有中的 wake lock
        var mirrorOf = mapOf(0 to 50)

        val sinks = DisplaySinks<String>(
            resolveDistributorId = { mirrorOf[it] ?: it },
            attachSurface = { id, _ ->
                if (id !in distributors) -1 else nextHandle++.also { attached += id to it }
            },
            detachSurface = { id, handle -> attached -= id to handle },
            holdAwake = { id ->
                if (id !in ownsGroup) null else {
                    awake += id
                    { awake.remove(id) }
                }
            },
        )
    }

    @Test
    fun attachHoldsTheDisplayAwakeUntilDetach() {
        val w = World()
        val token = FakeToken()

        assertTrue(w.sinks.attach(1, "surface", token))
        assertEquals(listOf(1), w.awake)
        assertEquals(1, w.attached.size)

        assertTrue(w.sinks.detach(token))
        assertEquals(emptyList<Int>(), w.awake)
        assertEquals(emptySet<Pair<Int, Int>>(), w.attached)
        assertTrue(token.recipients.isEmpty())
    }

    @Test
    fun detachingTwiceReleasesOnlyOnce() {
        val w = World()
        val mine = FakeToken()
        val theirs = FakeToken()
        w.sinks.attach(1, "mine", mine)
        w.sinks.attach(1, "theirs", theirs)

        assertTrue(w.sinks.detach(mine))
        assertFalse(w.sinks.detach(mine))

        assertEquals(listOf(1), w.awake)
        assertEquals(1, w.attached.size)
    }

    @Test
    fun unknownTokenTouchesNobody() {
        val w = World()
        w.sinks.attach(1, "surface", FakeToken())

        assertFalse(w.sinks.detach(FakeToken()))

        assertEquals(listOf(1), w.awake)
    }

    @Test
    fun sameTokenCannotAttachTwice() {
        val w = World()
        val token = FakeToken()
        assertTrue(w.sinks.attach(1, "a", token))

        assertFalse(w.sinks.attach(2, "b", token))

        assertEquals(listOf(1), w.awake)
        assertEquals(1, w.attached.size)
    }

    @Test
    fun ownerDeathReleasesItsSink() {
        val w = World()
        val token = FakeToken()
        w.sinks.attach(1, "surface", token)

        token.die()

        assertEquals(emptyList<Int>(), w.awake)
        assertEquals(emptySet<Pair<Int, Int>>(), w.attached)
        assertFalse(w.sinks.detach(token))
    }

    @Test
    fun ownerAlreadyDeadAtAttachLeavesNothingBehind() {
        val w = World()

        assertFalse(w.sinks.attach(1, "surface", FakeToken(deadAlready = true)))

        assertEquals(emptyList<Int>(), w.awake)
        assertEquals(emptySet<Pair<Int, Int>>(), w.attached)
    }

    @Test
    fun distributorGoneDropsItsSinksWithoutDetachingFromIt() {
        val w = World()
        val onOne = FakeToken()
        val onTwo = FakeToken()
        w.sinks.attach(1, "a", onOne)
        w.sinks.attach(2, "b", onTwo)
        val attachedBefore = w.attached.toSet()

        w.sinks.onDistributorUnregistered(1)

        assertEquals(listOf(2), w.awake)
        // 已經消失的 distributor 不再被呼叫 detach。
        assertEquals(attachedBefore, w.attached)
        assertTrue(onOne.recipients.isEmpty())
        assertFalse(w.sinks.detach(onOne))
        assertTrue(w.sinks.detach(onTwo))
    }

    @Test
    fun displayWithoutItsOwnGroupIsNotHeldAwake() {
        val w = World()
        w.ownsGroup.clear()

        assertTrue(w.sinks.attach(1, "surface", FakeToken()))

        assertEquals(emptyList<Int>(), w.awake)
    }

    @Test
    fun attachFailsWhenTheDisplayHasNoDistributor() {
        val w = World()

        assertFalse(w.sinks.attach(7, "surface", FakeToken()))

        assertEquals(emptyList<Int>(), w.awake)
    }

    @Test
    fun physicalDisplayAttachesToItsMirrorAndIsDroppedWithIt() {
        val w = World()
        val token = FakeToken()

        assertTrue(w.sinks.attach(0, "surface", token))
        assertEquals(setOf(50 to 0), w.attached)

        w.sinks.onDistributorUnregistered(50)
        assertFalse(w.sinks.detach(token))
    }
}
