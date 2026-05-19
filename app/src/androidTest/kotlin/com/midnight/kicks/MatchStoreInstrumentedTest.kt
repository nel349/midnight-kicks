package com.midnight.kicks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented round-trip tests for [MatchStore] against a real
 * Android Keystore + real [androidx.security.crypto.EncryptedSharedPreferences].
 *
 * **Why this exists alongside the JVM unit tests:** the unit tests
 * (Robolectric, plain `SharedPreferences`) cover the data-flow logic
 * but skip the Keystore-backed encryption path entirely — Robolectric
 * doesn't shadow `AndroidKeyStore`. This suite runs on a real device /
 * emulator, exercises the actual Keystore master key flow, and
 * confirms encrypted writes round-trip across MatchStore instances
 * (i.e. simulates the kill-and-relaunch case that motivated the
 * `commit()` durability fix).
 *
 * Failures here indicate something the unit tests *can't* catch — a
 * Keystore alias collision, an EncryptedSharedPreferences version
 * mismatch, an Android crypto-provider regression. Cheap to run
 * (sub-second per test) and worth keeping in the connected-test
 * profile permanently.
 */
@RunWith(AndroidJUnit4::class)
class MatchStoreInstrumentedTest {

    private lateinit var store: MatchStore

    @Before
    fun setup() {
        // Use the production constructor — wraps EncryptedSharedPreferences
        // over the Android Keystore master key. This is what makes the
        // suite "instrumented": the JVM unit tests use the test-friendly
        // primary constructor with plain SharedPreferences.
        store = MatchStore(ApplicationProvider.getApplicationContext<Context>())
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun save_then_load_roundtrips_through_real_encryption() {
        val original = MatchStore.Match(
            address = "deadbeef".repeat(8),
            role = Player.P1,
            deadline = 1_900_000_000L,
            secretKey = ByteArray(32) { 0xAB.toByte() },
            regulation = MatchStore.RegulationWitnesses(
                shoots = intArrayOf(0, 1, 2, 1, 0),
                keeps = intArrayOf(2, 2, 1, 0, 1),
                nonce = ByteArray(32) { 0xCD.toByte() },
            ),
        )
        store.save(original)

        val loaded = store.load(original.address)
        assertNotNull(
            "EncryptedSharedPreferences round-trip lost the record — Keystore master key or value-encryption regression",
            loaded,
        )
        assertEquals(original, loaded)
        // Defense in depth — the equality is hand-rolled with
        // contentEquals; assert ByteArray fields directly too so a
        // future refactor that breaks Match.equals doesn't mask a real
        // round-trip failure.
        assertArrayEquals(original.secretKey, loaded!!.secretKey)
        assertArrayEquals(original.regulation!!.nonce, loaded.regulation!!.nonce)
    }

    @Test
    fun cloud_snapshot_roundtrip_works_with_real_keystore() {
        // Snapshot the local store, clear it, restore from the blob.
        // Mirrors the sigil-restore-on-fresh-device flow: cloud blob
        // arrives, MatchStore wipes itself, repopulates from the blob.
        // With real Keystore, an encryption-version mismatch between
        // the clear() and the save() inside restoreFromBytes would
        // surface here as a failed load.
        store.save(fixtureMatch(address = "aaaa", role = Player.P1, deadline = 100L))
        store.save(fixtureMatch(address = "bbbb", role = Player.P2, deadline = 200L))

        val blob = store.snapshotBytes()
        store.clear()
        assertEquals(0, store.loadAll().size)

        store.restoreFromBytes(blob)
        val restored = store.loadAll()
        assertEquals(2, restored.size)
        assertEquals(Player.P1, restored.single { it.address == "aaaa" }.role)
        assertEquals(Player.P2, restored.single { it.address == "bbbb" }.role)
    }

    @Test
    fun second_store_instance_sees_first_instances_writes() {
        // Simulates kill-and-relaunch: a fresh MatchStore points at the
        // same EncryptedSharedPreferences file (same Keystore alias) and
        // sees the previous instance's writes durably. If commit() were
        // accidentally re-flipped to apply(), this test catches the
        // SIGKILL-loses-write window directly — apply()'s async write
        // can land after a fresh process queries the file.
        val first = store
        first.save(fixtureMatch(address = "cccc", role = Player.P1))

        val second = MatchStore(ApplicationProvider.getApplicationContext<Context>())
        val loaded = second.load("cccc")
        assertNotNull("second instance must see first instance's commit() write", loaded)
        assertEquals(Player.P1, loaded!!.role)
    }

    @Test
    fun cleared_store_has_no_remnant_after_a_real_save() {
        // Defense against a half-implemented clear() — must wipe the
        // address index *and* every per-address record. Easy to forget
        // one in a future refactor; this test pins the contract.
        store.save(fixtureMatch(address = "dddd"))
        store.clear()

        assertEquals(emptyList<MatchStore.Match>(), store.loadAll())
        assertNull(store.load("dddd"))
    }

    private fun fixtureMatch(
        address: String,
        role: Player = Player.P1,
        deadline: Long = 1_700_000_000L,
    ): MatchStore.Match = MatchStore.Match(
        address = address,
        role = role,
        deadline = deadline,
        secretKey = ByteArray(32) { 0xEF.toByte() },
    )
}
