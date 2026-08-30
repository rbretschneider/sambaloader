package com.nectarmobiledevelopment.sambaloader.core.crypto.identity

/**
 * The StrongBox-first-then-TEE generation strategy (FRD §8.3), extracted as
 * pure logic so the fallback path is unit-testable without an Android
 * keystore.
 */
object StrongBoxFallback {

    /**
     * Runs [generate] with StrongBox requested when [strongBoxAvailable];
     * if that attempt throws an exception [isStrongBoxFailure] recognizes,
     * retries once without StrongBox. Any other exception propagates —
     * key generation failures must never be silently downgraded further.
     */
    fun <T> generate(
        strongBoxAvailable: Boolean,
        isStrongBoxFailure: (Throwable) -> Boolean,
        generate: (useStrongBox: Boolean) -> T,
    ): T {
        if (!strongBoxAvailable) {
            return generate(false)
        }
        return try {
            generate(true)
            // Generic by design: the caller's predicate decides which
            // exception types mean "StrongBox absent" (they vary by OEM).
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            if (isStrongBoxFailure(failure)) {
                generate(false)
            } else {
                throw failure
            }
        }
    }
}
