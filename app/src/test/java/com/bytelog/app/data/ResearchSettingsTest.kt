package com.bytelog.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.dataStore
import com.bytelog.app.research.KeystoreSecretCipher
import com.bytelog.app.research.ProviderId
import com.bytelog.app.research.SecretCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Same sandbox config as the other Robolectric tests (see DatabaseMigrationTest). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class ResearchSettingsTest {

    /** Reversible stand-in so the storage tests don't depend on the keystore. */
    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(token: String): String? = token.takeIf { it.startsWith("enc:") }?.removePrefix("enc:")?.reversed()
    }

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    // The datastore file outlives a single test, so start each one empty.
    @org.junit.Before fun clearStore() = runBlocking { context.dataStore.edit { it.clear() }; Unit }

    @Test fun defaultsAreSafe() = runBlocking {
        val cfg = SettingsRepository(context, FakeCipher()).researchConfig.first()
        assertEquals(ProviderId.ANTHROPIC, cfg.provider)
        assertTrue("asks before changing data by default", cfg.askBeforeWriting)
        assertFalse(cfg.isConfigured)
        assertEquals("claude-opus-5-5", cfg.modelFor(ProviderId.ANTHROPIC))
    }

    @Test fun keysAreStoredEncryptedPerProvider() = runBlocking {
        val repo = SettingsRepository(context, FakeCipher())
        repo.saveResearchKey(ProviderId.ANTHROPIC, "  sk-ant-PLAINTEXT  ")
        repo.saveResearchKey(ProviderId.GEMINI, "AIza-PLAINTEXT")
        repo.saveTavilyKey("tvly-PLAINTEXT")
        repo.saveResearchProvider(ProviderId.GEMINI)

        val cfg = repo.researchConfig.first()
        assertEquals(ProviderId.GEMINI, cfg.provider)
        assertEquals("sk-ant-PLAINTEXT", cfg.keyFor(ProviderId.ANTHROPIC))
        assertEquals("AIza-PLAINTEXT", cfg.keyFor(ProviderId.GEMINI))
        assertNull(cfg.keyFor(ProviderId.DEEPSEEK))
        assertEquals("tvly-PLAINTEXT", cfg.tavilyKey)
        assertTrue(cfg.isConfigured)

        // What is actually on disk never contains a key.
        val raw: Preferences = context.dataStore.data.first()
        val onDisk = raw.asMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
        assertFalse(onDisk.contains("PLAINTEXT"))
        assertTrue(onDisk.contains("research_key_anthropic"))
    }

    @Test fun removingAKeyClearsIt() = runBlocking {
        val repo = SettingsRepository(context, FakeCipher())
        repo.saveResearchKey(ProviderId.DEEPSEEK, "ds-key")
        repo.saveResearchKey(ProviderId.DEEPSEEK, "")
        assertNull(repo.researchConfig.first().keyFor(ProviderId.DEEPSEEK))
    }

    @Test fun modelOverridesFallBackToTheDefault() = runBlocking {
        val repo = SettingsRepository(context, FakeCipher())
        repo.saveResearchModel(ProviderId.GEMINI, "gemini-custom")
        assertEquals("gemini-custom", repo.researchConfig.first().modelFor(ProviderId.GEMINI))
        repo.saveResearchModel(ProviderId.GEMINI, "")
        assertEquals(ProviderId.GEMINI.defaultModel, repo.researchConfig.first().modelFor(ProviderId.GEMINI))
    }

    @Test fun approvalSettingPersists() = runBlocking {
        val repo = SettingsRepository(context, FakeCipher())
        repo.saveAskBeforeWriting(false)
        assertFalse(repo.researchConfig.first().askBeforeWriting)
    }

    @Test fun aKeyThatCannotBeDecryptedIsTreatedAsMissing() = runBlocking {
        SettingsRepository(context, FakeCipher()).saveResearchKey(ProviderId.ANTHROPIC, "sk-ant-x")
        // e.g. a backup restored onto another phone: the keystore key isn't there
        val other = object : SecretCipher { override fun encrypt(plain: String) = plain; override fun decrypt(token: String): String? = null }
        assertNull(SettingsRepository(context, other).researchConfig.first().keyFor(ProviderId.ANTHROPIC))
    }

    @Test fun theCipherEncryptsWithAesGcmAndRejectsTampering() {
        // A software key stands in for the Keystore key (Robolectric has no AndroidKeyStore provider).
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = KeystoreSecretCipher("test", keyProvider = { key })
        val token = cipher.encrypt("sk-ant-real-secret")
        assertFalse(token.contains("real-secret"))
        assertEquals("sk-ant-real-secret", cipher.decrypt(token))
        assertNotEquals("fresh IV each time", token, cipher.encrypt("sk-ant-real-secret"))
        assertNull(cipher.decrypt("not-a-valid-token"))
        // Flip one byte of the ciphertext: GCM authentication must fail.
        val bytes = android.util.Base64.decode(token, android.util.Base64.NO_WRAP)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull(cipher.decrypt(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
        // A different key can't read it (e.g. a backup restored onto another phone)
        val other = KeystoreSecretCipher("test", keyProvider = { javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() })
        assertNull(other.decrypt(token))
    }
}
