package com.mobileagent.agent

import com.mobileagent.core.common.Outcome
import com.mobileagent.core.model.RunId
import java.util.concurrent.ConcurrentHashMap

/** Keeps checkpoints in memory only. For tests and the developer CLI; runs don't survive the process. */
class InMemoryRunCheckpointStore : RunCheckpointStore {
    private val checkpoints = ConcurrentHashMap<RunId, RunCheckpoint>()

    override suspend fun save(checkpoint: RunCheckpoint): Outcome<Unit> {
        checkpoints[checkpoint.runId] = checkpoint
        return Outcome.Success(Unit)
    }

    override suspend fun load(runId: RunId): Outcome<RunCheckpoint?> = Outcome.Success(checkpoints[runId])
}
