package com.nectarmobiledevelopment.sambaloader.sync

/** How a user-initiated backup run ended, in terms the UI can word. */
sealed interface ImmediateRunOutcome {

    data class Uploaded(val count: Int) : ImmediateRunOutcome

    data object NothingToUpload : ImmediateRunOutcome

    /** Files kept back by the metered-data size cap; [uploaded] went up regardless. */
    data class HeldForWifi(val count: Int, val uploaded: Int) : ImmediateRunOutcome

    data object ServerUnreachable : ImmediateRunOutcome

    data object Failed : ImmediateRunOutcome
}
