package com.nectarmobiledevelopment.sambaloader.ui.home

import com.nectarmobiledevelopment.sambaloader.core.data.identity.Enrollment
import com.nectarmobiledevelopment.sambaloader.core.testing.data.FakeIdentityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HomeViewModelTest {

    private val repository = FakeIdentityRepository()

    @BeforeEach
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun makeEnrollment(serverUrl: String = "https://nas.example.com") = Enrollment(
        serverUrl = serverUrl,
        deviceCertificatePem = "cert",
        caCertificatePem = "ca",
        serialHex = "0x1",
        enrolledAtEpochMillis = 0,
    )

    @Test
    fun `un-enrolled state shows the not-paired message`() {
        val state = HomeViewModel(repository).uiState.value
        assertFalse(state.isEnrolled)
        assertEquals("Not paired with a server yet", state.statusMessage)
        assertTrue(state.appVersion.isNotBlank())
    }

    @Test
    fun `enrolled state names the server host`() {
        repository.save(makeEnrollment())
        val state = HomeViewModel(repository).uiState.value
        assertTrue(state.isEnrolled)
        assertEquals("Paired with nas.example.com", state.statusMessage)
    }
}
