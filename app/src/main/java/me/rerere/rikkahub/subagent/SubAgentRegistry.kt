package me.rerere.rikkahub.subagent

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * 内存中的子代理运行状态存储。
 * 上限 50 条，超了淘汰最旧的已终止记录。
 */
class SubAgentRegistry {

    private val _runs = MutableStateFlow<Map<String, SubAgentRun>>(emptyMap())
    val runs: StateFlow<Map<String, SubAgentRun>> = _runs

    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** 全局正在运行的子代理数量 */
    fun globalActiveCount(): Int =
        _runs.value.values.count { it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING }

    /** 某个助手的子代理数量 */
    fun activeCountForAssistant(assistantId: String): Int =
        _runs.value.values.count {
            it.parentAssistantId == assistantId &&
                (it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING)
        }

    fun add(run: SubAgentRun) {
        _runs.update { pruneIfNeeded(it) + (run.id to run) }
    }

    fun update(id: String, transform: (SubAgentRun) -> SubAgentRun) {
        _runs.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to transform(existing))
        }
    }

    fun get(id: String): SubAgentRun? = _runs.value[id]

    fun list(activeOnly: Boolean): List<SubAgentRun> {
        val all = _runs.value.values.toList()
        return if (activeOnly) all.filter { it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING }
        else all
    }

    fun setJob(id: String, job: Job) { activeJobs[id] = job }
    fun clearJob(id: String) { activeJobs.remove(id) }

    /** 取消指定运行，返回 true 表示确实取消了 */
    fun requestCancel(id: String): Boolean {
        val job = activeJobs.remove(id) ?: return false
        job.cancel()
        return true
    }

    /** 取消某个父对话下的所有子代理 */
    fun cancelAllForParent(parentChatId: String): Int {
        val toCancel = _runs.value.values
            .filter { it.parentChatId == parentChatId && (it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING) }
            .map { it.id }
        var count = 0
        for (runId in toCancel) {
            if (requestCancel(runId)) count++
        }
        return count
    }

    private fun pruneIfNeeded(current: Map<String, SubAgentRun>): Map<String, SubAgentRun> {
        if (current.size < 50) return current
        val oldestTerminal = current.values
            .filter { it.status != SubAgentStatus.RUNNING && it.status != SubAgentStatus.PENDING }
            .sortedBy { it.finishedAtMs ?: it.startedAtMs }
            .firstOrNull()?.id
        return if (oldestTerminal != null) current - oldestTerminal else current
    }
}
