package com.user.data

import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearPreferences() = runTest {
        context.dataStore.edit { it.clear() }
    }

    @Test
    fun missingKeysReturnDefaults() = runTest {
        val settings = AppPreferences(context).getBackendSettings()
        val expected = AppPreferences.defaultSettings

        assertEquals(expected.host, settings.host)
        assertEquals(expected.port, settings.port)
        assertEquals("", settings.token)
        assertEquals(expected.baseUrl, settings.baseUrl)
    }

    @Test
    fun preferencesPersistAcrossNewWrapperInstances() = runTest {
        val first = AppPreferences(context)
        first.saveBackendSettings(
            BackendSettings(
                host = "192.168.1.42",
                port = 8000,
                token = "secret",
                useHttps = true
            )
        )

        val second = AppPreferences(context)
        val settings = second.getBackendSettings()

        assertEquals("192.168.1.42", settings.host)
        assertEquals(8000, settings.port)
        assertEquals("secret", settings.token)
        assertEquals("https://192.168.1.42:8000", settings.baseUrl)
    }
}
