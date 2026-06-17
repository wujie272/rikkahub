package me.rerere.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.getActiveDates
import me.rerere.rikkahub.data.db.dao.getHourlyDistribution
import me.rerere.rikkahub.data.db.dao.getMessageCountPerDay
import me.rerere.rikkahub.data.db.dao.getTokenStats
import me.rerere.rikkahub.data.db.dao.getWeekdayDistribution
import me.rerere.rikkahub.data.db.dao.HourlyCount
import me.rerere.rikkahub.data.db.dao.WeekdayCount
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.pages.stats.cards.StreakInfo
import me.rerere.rikkahub.ui.pages.stats.charts.MonthCount
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
    val conversationsByMonth: List<MonthCount> = emptyList(),
    val hourlyDistribution: List<HourlyCount> = emptyList(),
    val weekdayDistribution: List<WeekdayCount> = emptyList(),
    val streakInfo: StreakInfo = StreakInfo(),
    val avgMessagesPerConversation: Double = 0.0,
)

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    init {
        viewModelScope.launch { loadStats() }
    }

    private suspend fun loadStats() {
        delay(50)

        val today = LocalDate.now()

        // 热力图起始日期（52 周前的周日）
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
            .toString()

        // 所有时间范围内用于趋势图
        val allStartDate = today.minusYears(2).toString()
        val allStartMillis = today.minusYears(2).toEpochDay() * 86400000L

        // 基于用户消息的 createdAt 统计每日活跃消息数
        val conversationsPerDay = withContext(Dispatchers.IO) {
            messageNodeDAO
                .getMessageCountPerDay(startDate)
                .mapNotNull { entry ->
                    runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
                }
                .toMap()
        }

        // 按月份聚合对话数（用于折线图）
        val conversationsByMonth = withContext(Dispatchers.IO) {
            conversationDAO.getConversationCountPerDay(allStartMillis)
                .mapNotNull { entry ->
                    runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
                }
                .groupBy(
                    keySelector = { it.first.year to it.first.monthValue },
                    valueTransform = { it.second }
                )
                .map { (yearMonth, counts) ->
                    MonthCount(
                        month = yearMonth.second,
                        year = yearMonth.first,
                        count = counts.sum()
                    )
                }
                .sortedBy { it.year * 12 + it.month }
        }

        // 按小时分布
        val hourlyDistribution = withContext(Dispatchers.IO) {
            messageNodeDAO.getHourlyDistribution()
        }

        // 按星期分布
        val weekdayDistribution = withContext(Dispatchers.IO) {
            messageNodeDAO.getWeekdayDistribution()
        }

        // 连续使用天数
        val streakInfo = withContext(Dispatchers.IO) {
            calculateStreak(messageNodeDAO.getActiveDates(allStartDate))  // uses String date
        }

        val totalConversations = conversationDAO.countAll()

        val tokenStats = messageNodeDAO.getTokenStats()

        val launchCount = settingsStore.settingsFlow.value.launchCount

        val avgMessages = if (totalConversations > 0)
            tokenStats.totalMessages.toDouble() / totalConversations else 0.0

        _stats.value = AppStats(
            isLoading = false,
            totalConversations = totalConversations,
            totalMessages = tokenStats.totalMessages,
            totalPromptTokens = tokenStats.promptTokens,
            totalCompletionTokens = tokenStats.completionTokens,
            totalCachedTokens = tokenStats.cachedTokens,
            conversationsPerDay = conversationsPerDay,
            launchCount = launchCount,
            conversationsByMonth = conversationsByMonth,
            hourlyDistribution = hourlyDistribution,
            weekdayDistribution = weekdayDistribution,
            streakInfo = streakInfo,
            avgMessagesPerConversation = avgMessages,
        )
    }

    private fun calculateStreak(activeDates: List<String>): StreakInfo {
        if (activeDates.isEmpty()) return StreakInfo()

        val dates = activeDates.mapNotNull {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }.sorted()

        if (dates.isEmpty()) return StreakInfo()

        val today = LocalDate.now()
        var currentStreak = 0
        var longestStreak = 0
        var longestStart = dates.first()
        var longestEnd = dates.first()
        var tempStart = dates.first()

        var i = 0
        while (i < dates.size - 1) {
            var j = i
            while (j < dates.size - 1) {
                val diff = dates[j + 1].toEpochDay() - dates[j].toEpochDay()
                if (diff == 1L) {
                    j++
                } else {
                    break
                }
            }
            val streakLen = j - i + 1
            if (streakLen > longestStreak) {
                longestStreak = streakLen
                longestStart = dates[i]
                longestEnd = dates[j]
            }
            i = j + 1
        }

        // 计算当前连续天数
        var streak = 0
        val checkDate = today
        for (dayBack in 0..365) {
            val date = checkDate.minusDays(dayBack.toLong())
            if (date in dates) {
                streak++
            } else {
                break
            }
        }
        currentStreak = streak

        return StreakInfo(
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            longestStart = longestStart.toString(),
            longestEnd = longestEnd.toString(),
        )
    }
}
