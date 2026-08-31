package com.nectarmobiledevelopment.sambaloader.core.data.asset

/**
 * The single authority on which [AssetState] transitions are legal (FRD §8.7).
 *
 * Every state mutation in the sync pipeline must pass through [require] so an
 * illegal transition fails loudly at the source instead of corrupting sync
 * state silently. Notable transitions:
 *
 * - `UPLOADING → HASHED` is the process-death recovery reset: a stale
 *   `UPLOADING` row found at startup is returned to the queue.
 * - `FAILED_PERMANENT → HASHED` is the user's manual retry.
 * - `UPLOADED`/`SKIPPED_REMOTE_HAS → DELETED_LOCALLY` is the retention
 *   deletion (D7); `→ HASHED` is "server lost it, re-upload"; and
 *   `→ DISCOVERED` is "local content changed since upload, re-enter".
 * - `DELETED_LOCALLY` is terminal.
 */
object AssetStateMachine {

    private val legalTransitions: Map<AssetState, Set<AssetState>> = mapOf(
        AssetState.DISCOVERED to setOf(
            AssetState.HASHED,
            AssetState.FAILED_RETRYABLE,
        ),
        AssetState.HASHED to setOf(
            AssetState.SKIPPED_REMOTE_HAS,
            AssetState.UPLOADING,
            AssetState.FAILED_RETRYABLE,
        ),
        AssetState.UPLOADING to setOf(
            AssetState.UPLOADED,
            AssetState.FAILED_RETRYABLE,
            AssetState.FAILED_PERMANENT,
            AssetState.HASHED,
        ),
        AssetState.FAILED_RETRYABLE to setOf(
            AssetState.HASHED,
            AssetState.UPLOADING,
            AssetState.FAILED_PERMANENT,
        ),
        AssetState.FAILED_PERMANENT to setOf(
            AssetState.HASHED,
        ),
        AssetState.SKIPPED_REMOTE_HAS to setOf(
            AssetState.DELETED_LOCALLY,
            AssetState.HASHED,
            AssetState.DISCOVERED,
        ),
        AssetState.UPLOADED to setOf(
            AssetState.DELETED_LOCALLY,
            AssetState.HASHED,
            AssetState.DISCOVERED,
        ),
        AssetState.DELETED_LOCALLY to emptySet(),
    )

    fun isLegal(from: AssetState, to: AssetState): Boolean {
        return legalTransitions.getValue(from).contains(to)
    }

    /**
     * Throws [IllegalStateException] when the transition is not legal.
     */
    fun require(from: AssetState, to: AssetState) {
        check(isLegal(from, to)) { "Illegal asset state transition: $from -> $to" }
    }
}
