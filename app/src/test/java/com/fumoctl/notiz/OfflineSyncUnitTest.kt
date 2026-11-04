package com.fumoctl.notiz

import com.fumoctl.notiz.data.local.LocalCacheStore
import com.fumoctl.notiz.data.local.SessionPreferences
import com.fumoctl.notiz.data.remote.ApiException
import com.fumoctl.notiz.data.remote.isOfflineOrUnreachable
import com.fumoctl.notiz.data.remote.isUnauthorizedError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class OfflineSyncUnitTest {

    @Test
    fun scopeKey_normalizesBaseUrlAndIsolatesUsers() {
        val k1 = LocalCacheStore.buildScopeKey("http://192.168.1.50:3000/api/", "user-1")
        val k2 = LocalCacheStore.buildScopeKey("http://192.168.1.50:3000", "user-1")
        val k3 = LocalCacheStore.buildScopeKey("http://192.168.1.50:3000", "user-2")
        assertEquals(k1, k2)
        assertFalse(k2 == k3)
        assertEquals("http://192.168.1.50:3000|user-1", k1)
    }

    @Test
    fun localIdDetection_identifiesTemporaryOfflineEntities() {
        assertTrue(LocalCacheStore.isLocalId("${LocalCacheStore.LOCAL_NOTE_PREFIX}abc"))
        assertTrue(LocalCacheStore.isLocalId("${LocalCacheStore.LOCAL_FOLDER_PREFIX}123"))
        assertTrue(LocalCacheStore.isLocalId("${LocalCacheStore.LOCAL_ATTACHMENT_PREFIX}xyz"))
        assertFalse(LocalCacheStore.isLocalId("edd5cc5e-8883-4ade-b0af-656b56ca7439"))
        assertFalse(LocalCacheStore.isLocalId(null))
    }

    @Test
    fun offlineErrorClassification_distinguishesNetworkFromHttpValidation() {
        assertTrue(ConnectException("Connection refused").isOfflineOrUnreachable())
        assertTrue(SocketTimeoutException("connect timed out").isOfflineOrUnreachable())
        assertTrue(UnknownHostException("offline.local").isOfflineOrUnreachable())
        assertTrue(ApiException(503, "Service Unavailable").isOfflineOrUnreachable())

        val unauthorized = ApiException(401, "Token expired")
        assertFalse(unauthorized.isOfflineOrUnreachable())
        assertTrue(unauthorized.isUnauthorizedError())

        val notFound = ApiException(404, "Not found")
        assertFalse(notFound.isOfflineOrUnreachable())
        assertFalse(notFound.isUnauthorizedError())
    }

    @Test
    fun normalizeBaseUrl_stripsTrailingApiAndAddsScheme() {
        assertEquals(
            "http://10.0.2.2:3000",
            SessionPreferences.normalizeBaseUrl("10.0.2.2:3000/api/")
        )
    }
}

