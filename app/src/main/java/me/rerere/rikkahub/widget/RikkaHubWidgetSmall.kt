package me.rerere.rikkahub.widget

/**
 * 2×2 小尺寸 Widget — 复用 RikkaHubWidget 全部逻辑
 *
 * 独立的类名用于在 AndroidManifest 中注册为独立的 Widget 提供者，
 * 这样澎湃OS小部件中心会显示为独立的选项。
 */
class RikkaHubWidgetSmall : RikkaHubWidget()
