package com.nectarmobiledevelopment.sambaloader.sync.work

import androidx.work.Data
import androidx.work.WorkInfo
import com.nectarmobiledevelopment.sambaloader.sync.ImmediateRunOutcome
import com.nectarmobiledevelopment.sambaloader.sync.ImmediateRunState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSchedulerRunStateTest {

    private val id = UUID.randomUUID()

    private fun info(
        state: WorkInfo.State,
        output: Data = Data.EMPTY,
        attempts: Int = 0,
    ) = WorkInfo(id, state, emptySet(), output, Data.EMPTY, attempts, 0)

    @Test
    fun `no work means idle`() {
        assertEquals(ImmediateRunState.Idle, SyncScheduler.toRunState(null))
    }

    @Test
    fun `enqueued running and blocked work all read as running`() {
        listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED).forEach {
            assertEquals(
                ImmediateRunState.Running(id.toString(), isRetrying = false),
                SyncScheduler.toRunState(info(it)),
            )
        }
    }

    @Test
    fun `enqueued work that has already attempted is retrying`() {
        assertEquals(
            ImmediateRunState.Running(id.toString(), isRetrying = true),
            SyncScheduler.toRunState(info(WorkInfo.State.ENQUEUED, attempts = 1)),
        )
    }

    @Test
    fun `first running attempt is not retrying even though the attempt count is one`() {
        assertEquals(
            ImmediateRunState.Running(id.toString(), isRetrying = false),
            SyncScheduler.toRunState(info(WorkInfo.State.RUNNING, attempts = 1)),
        )
    }

    @Test
    fun `succeeded work carries its outcome`() {
        val output = ImmediateRunOutcomeCodec.toData(ImmediateRunOutcome.Uploaded(4))

        assertEquals(
            ImmediateRunState.Finished(id.toString(), ImmediateRunOutcome.Uploaded(4)),
            SyncScheduler.toRunState(info(WorkInfo.State.SUCCEEDED, output)),
        )
    }

    @Test
    fun `succeeded work without output reads as nothing to upload`() {
        assertEquals(
            ImmediateRunState.Finished(id.toString(), ImmediateRunOutcome.NothingToUpload),
            SyncScheduler.toRunState(info(WorkInfo.State.SUCCEEDED)),
        )
    }

    @Test
    fun `failed work without output reads as failed`() {
        assertEquals(
            ImmediateRunState.Finished(id.toString(), ImmediateRunOutcome.Failed),
            SyncScheduler.toRunState(info(WorkInfo.State.FAILED)),
        )
    }

    @Test
    fun `cancelled work reads as idle`() {
        assertEquals(ImmediateRunState.Idle, SyncScheduler.toRunState(info(WorkInfo.State.CANCELLED)))
    }

    @Test
    fun `every outcome survives the output data round trip`() {
        listOf(
            ImmediateRunOutcome.Uploaded(3),
            ImmediateRunOutcome.NothingToUpload,
            ImmediateRunOutcome.HeldForWifi(count = 2, uploaded = 1),
            ImmediateRunOutcome.ServerUnreachable,
            ImmediateRunOutcome.Failed,
        ).forEach {
            assertEquals(it, ImmediateRunOutcomeCodec.fromData(ImmediateRunOutcomeCodec.toData(it)))
        }
    }
}
