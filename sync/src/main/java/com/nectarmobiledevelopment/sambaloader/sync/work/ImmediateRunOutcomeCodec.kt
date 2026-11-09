package com.nectarmobiledevelopment.sambaloader.sync.work

import androidx.work.Data
import androidx.work.workDataOf
import com.nectarmobiledevelopment.sambaloader.sync.DrainSummary
import com.nectarmobiledevelopment.sambaloader.sync.ImmediateRunOutcome

/** Carries a run's outcome through WorkManager's output [Data]. */
internal object ImmediateRunOutcomeCodec {

    private const val KEY_OUTCOME = "outcome"
    private const val KEY_UPLOADED = "uploaded"
    private const val KEY_HELD = "held"

    private const val TAG_UPLOADED = "uploaded"
    private const val TAG_NOTHING = "nothing"
    private const val TAG_HELD = "held"
    private const val TAG_UNREACHABLE = "unreachable"
    private const val TAG_FAILED = "failed"

    /** Priority order matters: held files are the most actionable news. */
    fun fromSummary(summary: DrainSummary): ImmediateRunOutcome {
        return when {
            summary.waitingForWifi > 0 ->
                ImmediateRunOutcome.HeldForWifi(summary.waitingForWifi, summary.uploaded)
            summary.uploaded > 0 -> ImmediateRunOutcome.Uploaded(summary.uploaded)
            summary.wasOffline -> ImmediateRunOutcome.ServerUnreachable
            summary.failedRetryable + summary.failedPermanent > 0 -> ImmediateRunOutcome.Failed
            else -> ImmediateRunOutcome.NothingToUpload
        }
    }

    fun toData(outcome: ImmediateRunOutcome): Data {
        return when (outcome) {
            is ImmediateRunOutcome.Uploaded ->
                workDataOf(KEY_OUTCOME to TAG_UPLOADED, KEY_UPLOADED to outcome.count)
            ImmediateRunOutcome.NothingToUpload -> workDataOf(KEY_OUTCOME to TAG_NOTHING)
            is ImmediateRunOutcome.HeldForWifi -> workDataOf(
                KEY_OUTCOME to TAG_HELD,
                KEY_HELD to outcome.count,
                KEY_UPLOADED to outcome.uploaded,
            )
            ImmediateRunOutcome.ServerUnreachable -> workDataOf(KEY_OUTCOME to TAG_UNREACHABLE)
            ImmediateRunOutcome.Failed -> workDataOf(KEY_OUTCOME to TAG_FAILED)
        }
    }

    /** Null when the work carried no outcome (scheduled run, or an old one). */
    fun fromData(data: Data): ImmediateRunOutcome? {
        return when (data.getString(KEY_OUTCOME)) {
            TAG_UPLOADED -> ImmediateRunOutcome.Uploaded(data.getInt(KEY_UPLOADED, 0))
            TAG_NOTHING -> ImmediateRunOutcome.NothingToUpload
            TAG_HELD -> ImmediateRunOutcome.HeldForWifi(
                data.getInt(KEY_HELD, 0),
                data.getInt(KEY_UPLOADED, 0),
            )
            TAG_UNREACHABLE -> ImmediateRunOutcome.ServerUnreachable
            TAG_FAILED -> ImmediateRunOutcome.Failed
            else -> null
        }
    }
}
