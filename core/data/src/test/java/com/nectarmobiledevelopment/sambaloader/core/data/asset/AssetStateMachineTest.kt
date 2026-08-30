package com.nectarmobiledevelopment.sambaloader.core.data.asset

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AssetStateMachineTest {

    /**
     * The complete legal transition table. Any (from, to) pair not listed
     * here is asserted illegal by the exhaustive test below, so a change to
     * the state machine must update both the production table and this list.
     */
    private val legalPairs = setOf(
        AssetState.DISCOVERED to AssetState.HASHED,
        AssetState.DISCOVERED to AssetState.FAILED_RETRYABLE,
        AssetState.HASHED to AssetState.SKIPPED_REMOTE_HAS,
        AssetState.HASHED to AssetState.UPLOADING,
        AssetState.HASHED to AssetState.FAILED_RETRYABLE,
        AssetState.UPLOADING to AssetState.UPLOADED,
        AssetState.UPLOADING to AssetState.FAILED_RETRYABLE,
        AssetState.UPLOADING to AssetState.FAILED_PERMANENT,
        AssetState.UPLOADING to AssetState.HASHED,
        AssetState.FAILED_RETRYABLE to AssetState.HASHED,
        AssetState.FAILED_RETRYABLE to AssetState.UPLOADING,
        AssetState.FAILED_RETRYABLE to AssetState.FAILED_PERMANENT,
        AssetState.FAILED_PERMANENT to AssetState.HASHED,
    )

    @Test
    fun `every state pair matches the expected transition table exhaustively`() {
        for (from in AssetState.entries) {
            for (to in AssetState.entries) {
                val expected = (from to to) in legalPairs
                assertEquals(
                    expected,
                    AssetStateMachine.isLegal(from, to),
                    "Transition $from -> $to should be ${if (expected) "legal" else "illegal"}",
                )
            }
        }
    }

    @Test
    fun `terminal states allow no outgoing transitions`() {
        for (to in AssetState.entries) {
            assertFalse(AssetStateMachine.isLegal(AssetState.UPLOADED, to))
            assertFalse(AssetStateMachine.isLegal(AssetState.SKIPPED_REMOTE_HAS, to))
        }
    }

    @ParameterizedTest
    @CsvSource(
        "UPLOADING, HASHED",
        "FAILED_PERMANENT, HASHED",
    )
    fun `recovery transitions are legal`(from: AssetState, to: AssetState) {
        assertTrue(AssetStateMachine.isLegal(from, to))
    }

    @Test
    fun `require throws on an illegal transition`() {
        assertThrows(IllegalStateException::class.java) {
            AssetStateMachine.require(AssetState.UPLOADED, AssetState.UPLOADING)
        }
    }

    @Test
    fun `require passes silently on a legal transition`() {
        AssetStateMachine.require(AssetState.DISCOVERED, AssetState.HASHED)
    }
}
