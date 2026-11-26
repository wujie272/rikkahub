package me.rerere.rikkahub.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.NULL_CONTEXT
import me.rerere.rikkahub.data.ai.tools.local.browserClickTool
import me.rerere.rikkahub.data.ai.tools.local.browserCurrentUrlTool
import me.rerere.rikkahub.data.ai.tools.local.browserGetTextTool
import me.rerere.rikkahub.data.ai.tools.local.browserOpenTool
import me.rerere.rikkahub.data.ai.tools.local.browserScrollTool
import me.rerere.rikkahub.data.ai.tools.local.browserTypeTool
import me.rerere.rikkahub.data.ai.tools.local.browserWaitForTool
import me.rerere.rikkahub.data.ai.tools.local.createBrowserTool
import me.rerere.rikkahub.data.ai.tools.local.executeJsTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览器工具单元测试。
 *
 * 测试覆盖：
 *   1. 工具工厂 - 每个 ALL_TOOLS 条目都能创建 Tool 对象
 *   2. 参数校验 - 缺失必填参数返回错误信封
 *   3. 未绑定短接 - 未绑定控制器时返回 browser_not_open
 *   4. 默认启用 - 22 个工具都有默认值，读写分类正确
 *   5. 错误信封 - 各错误类型互不相同
 */
class BrowserToolsTest {

    private fun execText(tool: me.rerere.ai.core.Tool, argsJson: String): String = runBlocking {
        val parts = tool.execute(Json.parseToJsonElement(argsJson))
        (parts.first { it is UIMessagePart.Text } as UIMessagePart.Text).text
    }

    // ── 工具工厂 ──

    @Test fun `createBrowserTool returns a Tool for every entry in ALL_TOOLS`() {
        for (name in BrowserToolDefaults.ALL_TOOLS) {
            val t = createBrowserTool(name, NULL_CONTEXT)
            assertNotNull("createBrowserTool returned null for '$name'", t)
            assertEquals("tool factory for '$name' produced wrong name", name, t!!.name)
        }
    }

    @Test fun `createBrowserTool returns null for unknown name`() {
        assertNull(createBrowserTool("not_a_browser_tool", NULL_CONTEXT))
    }

    // ── 参数校验 ──

    @Test fun `browser_open rejects missing url`() {
        val out = execText(browserOpenTool(NULL_CONTEXT), "{}")
        assertTrue("expected missing_url, got: $out", out.contains("missing_url"))
    }

    @Test fun `browser_open rejects blank url`() {
        val out = execText(browserOpenTool(NULL_CONTEXT), """{"url":""}""")
        assertTrue("expected missing_url, got: $out", out.contains("missing_url"))
    }

    @Test fun `browser_click rejects missing selector`() {
        val out = execText(browserClickTool(), "{}")
        assertTrue("expected missing_selector, got: $out", out.contains("missing_selector"))
    }

    @Test fun `browser_type rejects missing selector`() {
        val out = execText(browserTypeTool(), """{"text":"hi"}""")
        assertTrue(out.contains("missing_selector"))
    }

    @Test fun `browser_type rejects missing text`() {
        val out = execText(browserTypeTool(), """{"selector":"#q"}""")
        assertTrue(out.contains("missing_text"))
    }

    @Test fun `browser_scroll rejects missing direction`() {
        val out = execText(browserScrollTool(), "{}")
        assertTrue(out.contains("missing_direction"))
    }

    @Test fun `browser_scroll rejects unknown direction`() {
        val out = execText(browserScrollTool(), """{"direction":"sideways"}""")
        assertTrue("expected error, got: $out", out.contains("missing_direction"))
    }

    @Test fun `browser_eval_js rejects missing code`() {
        val out = execText(executeJsTool(), "{}")
        assertTrue(out.contains("missing_code"))
    }

    @Test fun `browser_wait_for rejects missing selector`() {
        val out = execText(browserWaitForTool(), "{}")
        assertTrue(out.contains("missing_selector"))
    }

    // ── 未绑定短接 ──

    @Test fun `read tools short-circuit to not_open when controller unbound`() {
        for (out in listOf(
            execText(browserCurrentUrlTool(), "{}"),
            execText(browserGetTextTool(), """{"selector":"body"}"""),
            execText(browserWaitForTool(), """{"selector":".loaded"}"""),
        )) {
            assertTrue("expected browser_not_open, got: $out", out.contains("browser_not_open"))
        }
    }

    @Test fun `write tools short-circuit to not_open when controller unbound`() {
        for (out in listOf(
            execText(browserClickTool(), """{"selector":"#go"}"""),
            execText(browserTypeTool(), """{"selector":"#q","text":"hello"}"""),
            execText(browserScrollTool(), """{"direction":"down"}"""),
            execText(executeJsTool(), """{"code":"1+1"}"""),
        )) {
            assertTrue("expected browser_not_open, got: $out", out.contains("browser_not_open"))
        }
    }

    // ── 默认启用配置 ──

    @Test fun `default enabled map covers all 22 tools`() {
        // ALL_TOOLS 中每个工具都必须在 DEFAULT_ENABLED 中有默认值，
        // 反之则说明有拼写错误
        assertEquals(22, BrowserToolDefaults.ALL_TOOLS.size)
        assertEquals(11, BrowserToolDefaults.READ_TOOLS.size)
        assertEquals(11, BrowserToolDefaults.WRITE_TOOLS.size)
        assertEquals(BrowserToolDefaults.ALL_TOOLS.toSet(), BrowserToolDefaults.DEFAULT_ENABLED.keys)
        // Read tools 默认 ON
        for (n in BrowserToolDefaults.READ_TOOLS) {
            assertEquals("$n should default ON", true, BrowserToolDefaults.DEFAULT_ENABLED[n])
        }
        // Write tools 默认 OFF
        for (n in BrowserToolDefaults.WRITE_TOOLS) {
            assertEquals("$n should default OFF", false, BrowserToolDefaults.DEFAULT_ENABLED[n])
        }
    }

    // ── 错误信封 ──

    @Test fun `bindBusyEnvelope is a distinct error shape from the other browser envelopes`() {
        val busy = BrowserController.bindBusyEnvelope().toString()
        assertTrue("expected browser_busy, got: $busy", busy.contains("browser_busy"))
        assertTrue("busy should carry recovery, got: $busy", busy.contains("recovery"))
        val notOpen = BrowserController.notOpenEnvelope().toString()
        val lost = BrowserController.sessionLostEnvelope().toString()
        assertTrue("busy must differ from not_open", busy != notOpen)
        assertTrue("busy must differ from session_lost", busy != lost)
    }

    @Test fun `canBindHeadless allows binding when controller is idle`() {
        assertTrue(BrowserController.canBindHeadless("conv-a"))
        assertTrue(BrowserController.canBindHeadless("conv-b"))
    }
}
