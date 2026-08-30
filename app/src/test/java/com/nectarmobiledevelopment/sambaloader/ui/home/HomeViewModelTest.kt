package com.nectarmobiledevelopment.sambaloader.ui.home

import com.nectarmobiledevelopment.sambaloader.core.data.time.TimeProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeViewModelTest {

    private val fixedTime = TimeProvider { 1_756_500_000_000 }

    @Test
    fun `starts un-enrolled with an explicit status message`() {
        val viewModel = HomeViewModel(fixedTime)
        val state = viewModel.uiState.value
        assertFalse(state.isEnrolled)
        assertEquals("Not paired with a server yet", state.statusMessage)
        assertTrue(state.appVersion.isNotBlank())
    }
}
