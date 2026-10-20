package com.opendictate.app.network

import com.opendictate.app.data.SettingsSyncDocument
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class GoogleDriveSettingsClientTest {
    @Test fun `unchanged polls and cloud-only edits do not upload but local edits and first link do`() {
        val document = SettingsSyncDocument().record(mapOf("dictionary" to "Cloud"), "mac", 42)
        val existing = GoogleDriveSettingsClient.Remote(document, "own-file")
        assertFalse(existing.needsUpload(document))
        val imported = SettingsSyncDocument().merge(document)
        assertFalse(existing.needsUpload(imported))
        assertTrue(existing.needsUpload(imported.record(mapOf("dictionary" to "Local"), "android", 50)))
        assertTrue(GoogleDriveSettingsClient.Remote(document, null).needsUpload(imported))
    }

    private fun client(handler: (Request) -> Pair<Int, String>) = GoogleDriveSettingsClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build(),
    )

    @Test fun `pagination merges replicas and updates only this device file`() = runTest {
        var calls = 0
        val client = client { request ->
            calls++
            assertEquals("Bearer synthetic-drive-token", request.header("Authorization"))
            when (calls) {
                1 -> {
                    assertEquals("appDataFolder", request.url.queryParameter("spaces"))
                    200 to """{"nextPageToken":"page2","files":[{"id":"mac-file","name":"opendictate-settings-v1-mac.json","size":"150"}]}"""
                }
                2 -> 200 to """{"schemaVersion":1,"entries":{"dictionary":{"value":"Никита","modifiedAt":20,"deviceId":"mac"}}}"""
                3 -> { assertEquals("page2", request.url.queryParameter("pageToken")); 200 to """{"files":[{"id":"own-file","name":"opendictate-settings-v1-android.json","size":"150"}]}""" }
                4 -> 200 to """{"schemaVersion":1,"entries":{"accurateModel":{"value":"gpt-transcribe","modifiedAt":30,"deviceId":"android"}}}"""
                else -> {
                    assertEquals("PATCH", request.method)
                    assertEquals("/upload/drive/v3/files/own-file", request.url.encodedPath)
                    val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                    val document = SettingsSyncDocument.fromJson(buffer.readUtf8())
                    assertEquals("Никита", document.entries["dictionary"]?.value)
                    assertEquals("gpt-transcribe", document.entries["accurateModel"]?.value)
                    200 to "{}"
                }
            }
        }
        val remote = client.download("synthetic-drive-token", "android")
        assertEquals("own-file", remote.ownFileId)
        client.upload("synthetic-drive-token", "android", remote.ownFileId, remote.document)
        assertEquals(5, calls)
    }

    @Test fun `cache follows versions deletion reconnect missing versions and own uploads`() = runTest {
        var version = "1"
        var present = true
        var mediaCalls = 0
        val client = client { request ->
            when {
                request.method == "PATCH" -> 200 to "{}"
                request.url.queryParameter("alt") == "media" -> {
                    mediaCalls++
                    200 to """{"schemaVersion":1,"entries":{"dictionary":{"value":"v$version","modifiedAt":1,"deviceId":"android"}}}"""
                }
                else -> {
                    assertTrue(request.url.queryParameter("fields")!!.contains("version"))
                    val metadata = if (version.isEmpty()) "" else """, "version":"$version""""
                    val files = if (present) """{"id":"own-file","name":"opendictate-settings-v1-android.json","size":"150"$metadata}""" else ""
                    200 to """{"files":[$files]}"""
                }
            }
        }
        client.download("synthetic", "android")
        client.download("synthetic", "android")
        assertEquals(1, mediaCalls)
        version = "2"
        val changed = client.download("synthetic", "android")
        assertEquals("v2", changed.document.entries["dictionary"]?.value)
        assertEquals(2, mediaCalls)
        present = false
        val deleted = client.download("synthetic", "android")
        assertTrue(deleted.document.entries.isEmpty()); assertNull(deleted.ownFileId)
        present = true
        client.download("synthetic", "android")
        assertEquals(3, mediaCalls)
        client.download("synthetic", "android", forceRefresh = true)
        assertEquals(4, mediaCalls)
        client.upload("synthetic", "android", "own-file", changed.document)
        client.download("synthetic", "android")
        assertEquals(5, mediaCalls)
        version = ""
        client.download("synthetic", "android")
        client.download("synthetic", "android")
        assertEquals(7, mediaCalls)
    }

    @Test fun `invalid replica is not cached and a corrected retry downloads again`() = runTest {
        var invalid = true
        var mediaCalls = 0
        val client = client { request ->
            if (request.url.queryParameter("alt") == "media") {
                mediaCalls++
                200 to if (invalid) "invalid" else """{"schemaVersion":1,"entries":{}}"""
            } else 200 to """{"files":[{"id":"cloud","name":"opendictate-settings-v1-mac.json","version":"1"}]}"""
        }
        try { client.download("synthetic", "android"); fail("Invalid data must fail") } catch (_: org.json.JSONException) { }
        invalid = false
        client.download("synthetic", "android")
        client.download("synthetic", "android")
        assertEquals(2, mediaCalls)
    }

    @Test fun `initial upload creates a private appdata replica`() = runTest {
        val client = client { request ->
            assertEquals("POST", request.method)
            assertEquals("multipart", request.url.queryParameter("uploadType"))
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            val body = buffer.readUtf8()
            assertTrue(body.contains("opendictate-settings-v1-android.json"))
            assertTrue(body.contains("appDataFolder"))
            assertTrue(body.contains("schemaVersion"))
            200 to "{}"
        }
        client.upload("synthetic-drive-token", "android", null, SettingsSyncDocument())
    }

    @Test fun `rejected credentials never expose provider payload`() = runTest {
        val client = client { 401 to "private-provider-response" }
        try { client.download("synthetic-drive-token", "android"); fail("Should fail") }
        catch (error: DriveSyncException) {
            assertEquals(401, error.status)
            assertFalse(error.message.orEmpty().contains("private-provider-response"))
        }
    }
}
