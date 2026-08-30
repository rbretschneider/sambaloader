package com.nectarmobiledevelopment.sambaloader.core.data.asset

/**
 * Lifecycle of a local media asset, per FRD §8.7. Legal transitions are
 * defined (and enforced) by [AssetStateMachine].
 */
enum class AssetState {
    DISCOVERED,
    HASHED,
    SKIPPED_REMOTE_HAS,
    UPLOADING,
    UPLOADED,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
}
