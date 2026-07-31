package me.rerere.rikkahub.ui.pages.assistant.groupchat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.GroupChatSeat

/**
 * 辩论预设按钮
 */
@Composable
fun DebatePresetButton(
    emoji: String,
    name: String,
    desc: String,
    accentColor: Color,
    seats: List<GroupChatSeat>,
    settings: Settings,
    onApply: (List<String>) -> Unit,
) {
    Card(
        onClick = {
            val prompts = generateDebatePrompts(name, seats)
            if (prompts.size == seats.size) {
                onApply(prompts)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                HugeIcons.Add01,
                contentDescription = "应用",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    // 显示当前座位及其模型状态
    if (seats.isNotEmpty()) {
        Column(modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)) {
            seats.forEachIndexed { i, seat ->
                val assistant = settings.assistants.firstOrNull { it.id == seat.assistantId }
                val modelId = seat.overrides.chatModelId ?: assistant?.chatModelId ?: settings.chatModelId
                val modelName = settings.findModelById(modelId)?.displayName ?: ""
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 1.dp),
                ) {
                    Text(
                        "${i + 1}. ${assistant?.name?.ifBlank { "助手" } ?: "助手"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (modelName.isNotBlank()) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "· $modelName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 根据预设名称生成相应的辩论角色提示词
 */
fun generateDebatePrompts(presetName: String, seats: List<GroupChatSeat>): List<String> {
    val systemPrompts = when (presetName) {
        "基础辩论" -> generateBasicDebatePrompts(seats.size)
        "专业辩论" -> generateProfessionalDebatePrompts(seats.size)
        "专家论坛" -> generateExpertForumPrompts(seats.size)
        else -> generateBasicDebatePrompts(seats.size)
    }
    return systemPrompts.take(seats.size)
}

private fun generateBasicDebatePrompts(count: Int): List<String> {
    val prompts = mutableListOf<String>()
    if (count >= 1) {
        prompts.add("""你是一位专业的正方辩论者。

你的立场：支持辩论观点。

辩论风格：
- 逻辑清晰，论证有力
- 引用具体事实、数据和案例
- 保持理性和专业的态度
- 每次发言控制在150-200字

请始终站在正方立场，为你的观点据理力争！""")
    }
    if (count >= 2) {
        prompts.add("""你是一位犀利的反方辩论者。

你的立场：反对辩论观点。

辩论风格：
- 思维敏锐，善于发现问题
- 用事实和逻辑拆解对方论证
- 提出有力的反驳和质疑
- 每次发言控制在150-200字

请始终站在反方立场，用理性和事实挑战对方观点！""")
    }
    if (count >= 3) {
        prompts.add("""你是一位专业的辩论主持人。

核心职责：
- 引导辩论方向和节奏
- 总结各方要点和分歧
- 判断讨论是否充分
- 决定何时结束辩论

重要：只有经过至少3轮充分讨论后才考虑结束辩论。""")
    }
    return prompts
}

private fun generateProfessionalDebatePrompts(count: Int): List<String> {
    val prompts = generateBasicDebatePrompts(count).toMutableList()
    if (count >= 4) {
        // Insert neutral analyst at position 2
        prompts.add(2, """你是一位客观中立的分析师。

分析风格：
- 保持绝对中立，不偏向任何一方
- 用理性和逻辑评估论证质量
- 指出可能被忽视的角度
- 寻找双方的共同点
- 每次发言控制在150-200字

请保持中立立场，为辩论提供客观理性的分析！""")
    }
    return prompts
}

private fun generateExpertForumPrompts(count: Int): List<String> {
    val prompts = mutableListOf<String>()
    if (count >= 1) {
        prompts.add("""你是一位资深法律专家，从法律角度参与辩论。

专业视角：
- 从法律法规角度分析问题
- 引用相关法条和判例
- 分析法律风险和合规性
- 每次发言控制在150-200字""")
    }
    if (count >= 2) {
        prompts.add("""你是一位经济学专家，从经济角度参与辩论。

专业视角：
- 分析经济成本和收益
- 评估市场影响和效率
- 考虑宏观和微观经济效应
- 每次发言控制在150-200字""")
    }
    if (count >= 3) {
        prompts.add("""你是一位技术专家，从技术角度参与辩论。

专业视角：
- 分析技术可行性和难度
- 评估技术风险和挑战
- 考虑技术发展趋势
- 每次发言控制在150-200字""")
    }
    if (count >= 4) {
        prompts.add("""你是一位专业的辩论主持人。

核心职责：
- 引导专家讨论方向
- 总结各领域专家的观点
- 推动跨领域交流
- 每次发言控制在150-200字""")
    }
    return prompts
}

/**
 * 应用辩论提示词到座位覆盖
 */
suspend fun applyDebatePrompts(
    vm: GroupChatTemplateDetailVM,
    seats: List<GroupChatSeat>,
    prompts: List<String>,
) {
    seats.take(prompts.size).forEachIndexed { index, seat ->
        if (index < prompts.size) {
            vm.updateSeatOverrides(seat.id) { overrides ->
                overrides.copy(systemPrompt = prompts[index])
            }
        }
    }
}
