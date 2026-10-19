package de.starfinanz.pkl.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VaultUriTest {

    @Test
    fun parses_full_uri_with_field() {
        val uri = VaultUri.parse("vault:secret/data/myapp/db#password")
        assertEquals("secret", uri.mount)
        assertEquals("myapp/db", uri.path)
        assertEquals("password", uri.field)
        assertEquals("secret/data/myapp/db", uri.apiPath())
    }

    @Test
    fun parses_uri_without_field() {
        val uri = VaultUri.parse("vault:secret/data/myapp/db")
        assertEquals("secret", uri.mount)
        assertEquals("myapp/db", uri.path)
        assertNull(uri.field)
        assertNull(uri.version)
    }

    @Test
    fun parses_version_query() {
        val uri = VaultUri.parse("vault:secret/data/myapp/db?version=3#password")
        assertEquals("myapp/db", uri.path)
        assertEquals("password", uri.field)
        assertEquals(3, uri.version)
    }

    @Test
    fun parses_version_query_without_field() {
        val uri = VaultUri.parse("vault:secret/data/myapp/db?version=7")
        assertEquals(7, uri.version)
        assertNull(uri.field)
    }

    @Test
    fun rejects_non_numeric_version() {
        assertFailsWith<VaultUriException> { VaultUri.parse("vault:secret/data/app?version=latest#x") }
    }

    @Test
    fun rejects_non_positive_version() {
        assertFailsWith<VaultUriException> { VaultUri.parse("vault:secret/data/app?version=0#x") }
    }

    @Test
    fun rejects_unknown_query_param() {
        assertFailsWith<VaultUriException> { VaultUri.parse("vault:secret/data/app?ver=3#x") }
    }

    @Test
    fun rejects_empty_query() {
        assertFailsWith<VaultUriException> { VaultUri.parse("vault:secret/data/app?#x") }
    }

    @Test
    fun parses_single_path_segment() {
        val uri = VaultUri.parse("vault:kv/data/app#token")
        assertEquals("kv", uri.mount)
        assertEquals("app", uri.path)
        assertEquals("token", uri.field)
    }

    @Test
    fun custom_mount_is_preserved() {
        val uri = VaultUri.parse("vault:team-a/data/service/creds#apiKey")
        assertEquals("team-a", uri.mount)
        assertEquals("service/creds", uri.path)
        assertEquals("team-a/data/service/creds", uri.apiPath())
    }

    @Test
    fun api_path_percent_encodes_segments() {
        val uri = VaultUri.parse("vault:secret/data/my app/db#password")
        assertEquals("secret/data/my%20app/db", uri.apiPath())
    }

    @Test
    fun rejects_wrong_scheme() {
        assertFailsWith<VaultUriException> { VaultUri.parse("http://secret/data/app#x") }
    }

    @Test
    fun rejects_empty_locator() {
        assertFailsWith<VaultUriException> { VaultUri.parse("vault:") }
    }

    @Test
    fun rejects_missing_data_segment() {
        assertFailsWith<VaultUriException> { VaultUri.parse("vault:secret/myapp/db#password") }
    }

    @Test
    fun rejects_missing_path() {
        assertFailsWith<VaultUriException> { VaultUri.parse("vault:secret/data#password") }
    }

    @Test
    fun rejects_empty_mount() {
        assertFailsWith<VaultUriException> { VaultUri.parse("vault:/data/app#x") }
    }

    @Test
    fun rejects_empty_path_segment() {
        assertFailsWith<VaultUriException> { VaultUri.parse("vault:secret/data/app//db#x") }
    }

    @Test
    fun rejects_empty_fragment() {
        assertFailsWith<VaultUriException> { VaultUri.parse("vault:secret/data/app#") }
    }
}
