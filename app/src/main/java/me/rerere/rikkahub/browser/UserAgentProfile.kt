package me.rerere.rikkahub.browser

/**
 * User Agent 配置枚举
 *
 * 管理 UA 字符串、默认 Viewport 尺寸。
 */
enum class UserAgentProfile(val value: String, val displayName: String) {
    MOBILE(
        value = "mobile",
        displayName = "📱 手机 Chrome",
    ),
    DESKTOP(
        value = "desktop",
        displayName = "💻 桌面 Chrome",
    ),
    CUSTOM(
        value = "custom",
        displayName = "✏️ 自定义",
    );

    val userAgentString: String?
        get() = when (this) {
            MOBILE -> "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
            DESKTOP -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
            CUSTOM -> null
        }

    /** 默认 Viewport 尺寸 */
    val viewportSize: Pair<Int, Int>
        get() = when (this) {
            DESKTOP -> 1280 to 800
            MOBILE, CUSTOM -> 412 to 915
        }

    companion object {
        fun fromString(s: String): UserAgentProfile? =
            entries.find { it.value == s }

        /** 从 SharedPreferences 读取的 profile 名解析（兼容旧版） */
        fun fromPrefString(s: String): UserAgentProfile =
            entries.find { it.value == s } ?: fromStringLegacy(s) ?: MOBILE

        private fun fromStringLegacy(s: String): UserAgentProfile? = when (s.lowercase()) {
            "mobile_chrome" -> MOBILE
            "desktop_chrome" -> DESKTOP
            else -> null
        }

        /** 显示 UA 字符串 */
        fun displayUA(profile: UserAgentProfile, customUA: String, notSetPlaceholder: String): String =
            when (profile) {
                CUSTOM -> customUA.ifBlank { notSetPlaceholder }
                else -> profile.userAgentString.orEmpty()
            }
    }
}
