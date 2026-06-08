package com.opensiri.agent.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for pure-JVM parts of AgentServerManager.
 *
 * We can't run the full class in a plain JVM unit test (it imports
 * android.content.Context + android.util.Log), so we test:
 *  - the AgentType enum (display names, all four agents exist)
 *  - the InstallResult data class (carries success + last output + log path)
 *  - port constants (no two agents collide on a port)
 *
 * The behavioral tests (runInPrefix, isProotInstalled, start/stop gateway)
 * will be added as Robolectric tests later. For now, these catch regressions
 * in the public API surface — e.g. someone renaming a port, dropping an
 * agent, or changing the success contract.
 */
class AgentModelsTest {

    @Test
    fun allFourAgentTypesPresent() {
        val names = AgentServerManager.AgentType.values().map { it.displayName }
        assertEquals(4, names.size)
        assertTrue("Hermes Agent" in names)
        assertTrue("Codex CLI" in names)
        assertTrue("OpenClaw" in names)
        assertTrue("Claude Code" in names)
    }

    @Test
    fun agentTypeNamesAreNonEmpty() {
        AgentServerManager.AgentType.values().forEach { type ->
            assertNotNull(type.displayName)
            assertTrue(
                "displayName for ${type.name} is empty",
                type.displayName.isNotBlank(),
            )
        }
    }

    @Test
    fun installResultCarriesAllFields() {
        val r = AgentServerManager.InstallResult(
            success = true,
            lastOutput = "✓ hermes installed",
            logPath = "/tmp/install.log",
        )
        assertTrue(r.success)
        assertEquals("✓ hermes installed", r.lastOutput)
        assertEquals("/tmp/install.log", r.logPath)
    }

    @Test
    fun installResultAllowsFailure() {
        val r = AgentServerManager.InstallResult(
            success = false,
            lastOutput = "✗ npm install failed",
            logPath = "/tmp/install.log",
        )
        assertFalse(r.success)
        assertTrue(r.lastOutput.contains("failed"))
    }

    @Test
    fun portsAreUniqueAcrossAgents() {
        // The whole point of these constants is to bind one per agent.
        // If two agents collide, you can't run them in parallel.
        val ports = setOf(
            AgentServerManager.WEBVIEW_PORT,
            AgentServerManager.HERMES_GATEWAY_PORT,
            AgentServerManager.HERMES_CONTROL_UI_PORT,
            AgentServerManager.CODEX_GATEWAY_PORT,
            AgentServerManager.OPENCLAW_GATEWAY_PORT,
            AgentServerManager.CLAW_CODE_PORT,
        )
        // 6 constants declared → 6 unique ports
        assertEquals(6, ports.size)
    }

    @Test
    fun webviewPortIsInPrivateRange() {
        // 18923 — must not collide with system ports
        val p = AgentServerManager.WEBVIEW_PORT
        assertTrue("webview port $p below 1024", p >= 1024)
        assertTrue("webview port $p above 65535", p <= 65535)
    }
}
