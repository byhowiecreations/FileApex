package com.fileapex.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsMasterKeyTest {
    private class MapStore : SettingsKvStore {
        val strings = linkedMapOf<String, String>()
        override fun contains(key: String) = key in strings
        override fun getBoolean(key: String, default: Boolean) = default
        override fun putBoolean(key: String, value: Boolean) {}
        override fun getString(key: String, default: String) = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun getInt(key: String, default: Int) = default
        override fun putInt(key: String, value: Int) {}
        override fun getLong(key: String, default: Long) = default
        override fun putLong(key: String, value: Long) {}
        override fun clear() = strings.clear()
        override fun keys(prefix: String) = strings.keys.filter { it.startsWith(prefix) }
        override fun remove(key: String) { strings.remove(key) }
    }

    private class FakeSealer : SecretSealer {
        var mode = "ok"
        override fun seal(plain: ByteArray): ByteArray {
            if (mode == "unavailable") throw SecretUnavailableException("locked")
            return plain.reversedArray()
        }
        override fun open(sealed: ByteArray): ByteArray = when (mode) {
            "unavailable" -> throw SecretUnavailableException("locked")
            "broken" -> error("key gone")
            else -> sealed.reversedArray()
        }
    }

    @Test
    fun theMasterKeyIsCreatedOnceKeptSealedAndFoundAgain() {
        val inner = MapStore()
        val sealer = FakeSealer()
        val first = SettingsMasterKey(inner, sealer, log = {}).sealer()!!
        val stored = inner.strings.getValue(SettingsMasterKey.KEY)
        assertEquals(true, stored.startsWith("sealed:1:"))
        val second = SettingsMasterKey(inner, sealer, log = {}).sealer()!!
        assertEquals("v", second.open("settings", "k", first.seal("settings", "k", "v")))
        assertEquals(stored, inner.strings.getValue(SettingsMasterKey.KEY))
    }

    @Test
    fun aKeyThatCannotBeOpenedRightNowIsNeverReplaced() {
        val inner = MapStore()
        val sealer = FakeSealer()
        SettingsMasterKey(inner, sealer, log = {}).sealer()
        val stored = inner.strings.getValue(SettingsMasterKey.KEY)
        sealer.mode = "unavailable"
        assertNull(SettingsMasterKey(inner, sealer, log = {}).sealer())
        assertEquals(stored, inner.strings.getValue(SettingsMasterKey.KEY))
        sealer.mode = "ok"
        assertNotNull(SettingsMasterKey(inner, sealer, log = {}).sealer())
    }

    @Test
    fun aKeyThatIsGoneForGoodIsReplacedSoTheAppStillStarts() {
        val inner = MapStore()
        val sealer = FakeSealer()
        val old = SettingsMasterKey(inner, sealer, log = {}).sealer()!!
        val oldValue = old.seal("settings", "k", "v")
        sealer.mode = "broken"
        val fresh = SettingsMasterKey(inner, sealer, log = {}).sealer()!!
        assertNull("the new key cannot open what the old one sealed", fresh.open("settings", "k", oldValue))
        sealer.mode = "ok"
        inner.strings[SettingsMasterKey.KEY] = "sealed:1:%%%not-base64"
        val replaced = SettingsMasterKey(inner, sealer, log = {}).sealer()!!
        assertNull(replaced.open("settings", "k", oldValue))
    }

    @Test
    fun theSealerIsCachedAfterTheFirstOpen() {
        val inner = MapStore()
        val key = SettingsMasterKey(inner, FakeSealer(), log = {})
        assertEquals(true, key.sealer() === key.sealer())
    }

    /** Both threads are inside `seal` at the same moment, as on a first launch where two stores are built together. */
    private class MeetingSealer(private val meet: java.util.concurrent.CountDownLatch) : SecretSealer {
        override fun seal(plain: ByteArray): ByteArray {
            meet.countDown()
            meet.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            return plain.reversedArray()
        }
        override fun open(sealed: ByteArray): ByteArray = sealed.reversedArray()
    }

    @Test
    fun twoStoresBuiltTogetherOnAFirstLaunchShareOneKeyAndLoseNothing() {
        val inner = MapStore()
        val meet = java.util.concurrent.CountDownLatch(2)
        val wrapper = MeetingSealer(meet)
        val sealers = arrayOfNulls<FieldSealerHolder>(2)
        val threads = (0..1).map { n ->
            Thread { sealers[n] = FieldSealerHolder(SettingsMasterKey(inner, wrapper, log = {}).sealer()!!) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val sealedByFirst = sealers[0]!!.sealer.seal("settings", "app_theme", "KINETIC")
        assertEquals("a value sealed by one store must open in the other", "KINETIC", sealers[1]!!.sealer.open("settings", "app_theme", sealedByFirst))
    }

    private class FieldSealerHolder(val sealer: com.fileapex.data.db.seal.FieldSealer)

    @Test
    fun theKeyReportsWhatItDid() {
        val inner = MapStore()
        val sealer = FakeSealer()
        val first = SettingsMasterKey(inner, sealer, log = {})
        assertEquals(SettingsMasterKey.Outcome.NotYet, first.outcome)
        first.sealer()
        assertEquals(SettingsMasterKey.Outcome.Created, first.outcome)
        val second = SettingsMasterKey(inner, sealer, log = {})
        second.sealer()
        assertEquals(SettingsMasterKey.Outcome.Opened, second.outcome)
        sealer.mode = "unavailable"
        val locked = SettingsMasterKey(inner, sealer, log = {})
        locked.sealer()
        assertEquals(SettingsMasterKey.Outcome.Unavailable, locked.outcome)
        sealer.mode = "broken"
        val replaced = SettingsMasterKey(inner, sealer, log = {})
        replaced.sealer()
        assertEquals(SettingsMasterKey.Outcome.Replaced, replaced.outcome)
    }
}
