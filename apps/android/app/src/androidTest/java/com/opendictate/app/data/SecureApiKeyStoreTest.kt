package com.opendictate.app.data

import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SecureApiKeyStoreTest {
    @Test fun readingExistingCiphertextNeverCreatesAReplacementEncryptionKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "_read-test-${UUID.randomUUID()}"
        val alias = "opendictate_api_key$namespace"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val store = SecureApiKeyStore(context, namespace)
            store.save("synthetic-credential", durable = true)
            keyStore.deleteEntry(alias)
            assertThrows(IllegalStateException::class.java) { store.get(strict = true) }
            assertFalse(keyStore.containsAlias(alias))
            assertTrue(context.getSharedPreferences("opendictate_credentials$namespace", 0).contains("api_key_ciphertext"))
        } finally {
            context.deleteSharedPreferences("opendictate_credentials$namespace")
            keyStore.deleteEntry(alias)
        }
    }
}
