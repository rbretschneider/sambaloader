package com.nectarmobiledevelopment.sambaloader.enrollment

import com.nectarmobiledevelopment.sambaloader.core.crypto.identity.DeviceKeyPairProvider
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.identity.IdentityRepository
import com.nectarmobiledevelopment.sambaloader.core.data.scan.ScanCursorRepository
import com.nectarmobiledevelopment.sambaloader.core.media.SharedInbox
import javax.inject.Inject

/**
 * Returns the app to its factory state so it can be paired again.
 *
 * All four steps belong together. Forgetting the server but keeping the
 * device key would leave a key no CA has signed; keeping the backup
 * history would be worse still — a new server has never seen this
 * library, and rows saying UPLOADED would silently exclude every existing
 * photo from the first sync, so the app would report "everything backed
 * up" having sent nothing.
 *
 * Nothing in the user's camera roll is touched. The only files deleted
 * are the app's own copies of shared items that never finished uploading.
 */
class UnpairDeviceUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val keyPairProvider: DeviceKeyPairProvider,
    private val assetRepository: AssetRepository,
    private val scanCursorRepository: ScanCursorRepository,
    private val sharedInbox: SharedInbox,
) {

    suspend operator fun invoke() {
        identityRepository.clear()
        // Best effort: a key that was never created, or already gone, is
        // the state we want anyway.
        runCatching { keyPairProvider.delete() }
        assetRepository.forgetEverything()
        // Without this the scanner still believes it has seen everything.
        scanCursorRepository.reset()
        sharedInbox.clear()
    }
}
