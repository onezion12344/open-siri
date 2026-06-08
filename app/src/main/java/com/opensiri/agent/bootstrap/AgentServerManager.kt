package com.opensiri.agent.bootstrap

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the lifecycle of all agent server processes running inside the
 * Termux bootstrap environment on Android.
 *
 * Supports multiple agent backends:
 * - Hermes Agent (default, best Android support)
 * - Codex CLI
 * - OpenClaw Gateway
 * - Claude Code
 *
 * Provides a unified WebView server with an agent selector API endpoint
 * so users can switch between agents at runtime.
 */
class AgentServerManager(private val context: Context) {

    companion object {
        private const val TAG = "AgentServerManager"
        const val WEBVIEW_PORT = 18923
        const val HERMES_GATEWAY_PORT = 18789
        const val HERMES_CONTROL_UI_PORT = 19001
        const val CODEX_GATEWAY_PORT = 18790
        const val OPENCLAW_GATEWAY_PORT = 18791
        const val CLAW_CODE_PORT = 18792
        private const val PROXY_PORT = 18924
    }

    // ── Agent type ───────────────────────────────────────────────────────

    enum class AgentType(val displayName: String) {
        HERMES("Hermes Agent"),
        CODEX("Codex CLI"),
        OPENCLAW("OpenClaw"),
        CLAW_CODE("Claude Code");
    }

    var activeAgent: AgentType = AgentType.HERMES

    fun getActiveAgentName(): String = activeAgent.displayName

    /**
     * Result of an agent installation operation.
     * Carries success status, the last N lines of output for error display,
     * and the path to the full install log.
     */
    data class InstallResult(
        val success: Boolean,
        val lastOutput: String,
        val logPath: String,
    )

    /**
     * Switch the active agent. Stops any running agent processes,
     * then starts the new agent's server components.
     */
    fun switchAgent(type: AgentType): Boolean {
        if (activeAgent == type && isRunning) {
            Log.i(TAG, "Already using ${type.displayName}")
            return true
        }

        Log.i(TAG, "Switching agent from ${activeAgent.displayName} to ${type.displayName}")

        // Stop current agent
        stopServer()

        activeAgent = type
        return startServer()
    }

    // ── Process handles ──────────────────────────────────────────────────

    private var serverProcess: Process? = null
    private var proxyProcess: Process? = null
    private var hermesGatewayProcess: Process? = null
    private var hermesControlUiProcess: Process? = null
    private var codexProcess: Process? = null
    private var openclawGatewayProcess: Process? = null
    private var openclawControlUiProcess: Process? = null
    private var clawCodeProcess: Process? = null

    // ── State queries ────────────────────────────────────────────────────

    val isRunning: Boolean
        get() {
            val proc = serverProcess ?: return false
            return try {
                proc.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
        }

    // ── Shell helpers ────────────────────────────────────────────────────

    /**
     * Run a shell command inside the Termux prefix environment.
     * Returns the exit code.
     */
    fun runInPrefix(
        command: String,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths)

        val shell = "${paths.prefixDir}/bin/sh"
        val pb = ProcessBuilder(shell, "-c", command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line = reader.readLine()
        while (line != null) {
            Log.d(TAG, line)
            onOutput?.invoke(line)
            line = reader.readLine()
        }
        return proc.waitFor()
    }

    /**
     * Run a Hermes command inside the prefix.
     * Equivalent to `hermes <command>` in the proot environment.
     *
     * @param command The Hermes subcommand (e.g. "cron run --all")
     * @param onOutput Optional callback for each line of stdout/stderr
     * @return The exit code
     */
    fun runHermesCommand(command: String, onOutput: ((String) -> Unit)? = null): Int {
        Log.i(TAG, "Running hermes $command")
        return runInPrefix("hermes $command", onOutput)
    }

    /**
     * Run a command and capture its stdout as a single trimmed string.
     */
    private fun runCapture(command: String): String {
        val sb = StringBuilder()
        runInPrefix(command) { sb.appendLine(it) }
        return sb.toString().trim()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Install checks
    // ═══════════════════════════════════════════════════════════════════════

    fun isProotInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "bin/proot").exists()
    }

    fun isNodeInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "bin/node").exists()
    }

    fun isPythonInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "bin/python3").exists() ||
                File(paths.prefixDir, "bin/python").exists()
    }

    /**
     * Check whether Hermes Agent is installed.
     * Looks for the `hermes` binary in the prefix bin directory,
     * or for the hermes-agent directory in ~/.hermes.
     */
    fun isHermesInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val hermesBin = File(paths.prefixDir, "bin/hermes")
        val hermesAgentDir = File(paths.homeDir, ".hermes/hermes-agent")
        return hermesBin.exists() || hermesAgentDir.exists()
    }

    /**
     * Check whether Codex CLI is installed.
     * Looks for the @openai/codex npm package.
     */
    fun isCodexInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "lib/node_modules/@openai/codex/bin/codex.js").exists()
    }

    /**
     * Check whether OpenClaw is installed.
     * Looks for the openclaw npm package.
     */
    fun isOpenClawInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val npmRoot = "${paths.prefixDir}/lib/node_modules"
        return File(npmRoot, "openclaw/package.json").exists()
    }

    /**
     * Check whether Claude Code is installed.
     * Looks for the @anthropic-ai/claude-code npm package.
     */
    fun isClawCodeInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "lib/node_modules/@anthropic-ai/claude-code/cli.js").exists()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Installation
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Install proot from the Termux repository. proot uses ptrace to
     * intercept filesystem syscalls and remap hardcoded Termux paths
     * (e.g. /data/data/com.termux/files/usr) to our actual prefix,
     * enabling dpkg, apt-get install, and other tools that have
     * compiled-in path references.
     */
    fun installProot(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val termuxPrefix = "/data/data/com.termux/files/usr"

        onProgress("Downloading proot…")

        val downloadCmd = """
            cd $prefix/tmp &&
            apt-get update --allow-insecure-repositories 2>&1;
            apt-get download --allow-unauthenticated proot libtalloc 2>&1
        """.trimIndent()

        val dlCode = runInPrefix(downloadCmd, onOutput = { onProgress(it) })
        if (dlCode != 0) {
            Log.e(TAG, "apt-get download proot failed with code $dlCode")
            return false
        }

        onProgress("Extracting proot…")
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _proot_stage &&
            for deb in proot*.deb libtalloc*.deb; do
                [ -f "${'$'}deb" ] && dpkg-deb -x "${'$'}deb" _proot_stage/ 2>&1
            done &&
            if [ -d "_proot_stage$termuxPrefix" ]; then
                cp -a _proot_stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_proot_stage/usr" ]; then
                cp -a _proot_stage/usr/* "$prefix/" 2>&1
            fi &&
            chmod 700 "$prefix/bin/proot" 2>/dev/null
            rm -rf _proot_stage proot*.deb libtalloc*.deb 2>/dev/null
            echo "proot installed"
        """.trimIndent()

        val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
        if (extractCode != 0) {
            Log.e(TAG, "proot extract failed with code $extractCode")
            return false
        }

        return isProotInstalled()
    }

    /**
     * Install Node.js and npm from the Termux repository.
     * Downloads the .deb packages (c-ares, libicu, libsqlite,
     * nodejs-lts, npm) and extracts them into the prefix.
     */
    fun installNode(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val termuxPrefix = "/data/data/com.termux/files/usr"

        onProgress("Downloading Node.js packages…")

        val downloadCmd = """
            cd $prefix/tmp &&
            apt-get update --allow-insecure-repositories 2>&1;
            apt-get download --allow-unauthenticated c-ares libicu libsqlite nodejs-lts npm 2>&1
        """.trimIndent()

        val dlCode = runInPrefix(downloadCmd, onOutput = { onProgress(it) })
        if (dlCode != 0) {
            Log.e(TAG, "apt-get download failed with code $dlCode")
        }

        onProgress("Extracting Node.js packages…")
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _stage &&
            for deb in *.deb; do
                echo "Extracting ${'$'}deb..." &&
                dpkg-deb -x "${'$'}deb" _stage/ 2>&1
            done &&
            if [ -d "_stage$termuxPrefix" ]; then
                cp -a _stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_stage/usr" ]; then
                cp -a _stage/usr/* "$prefix/" 2>&1
            fi &&
            rm -rf _stage *.deb 2>/dev/null
            echo "done"
        """.trimIndent()

        val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
        if (extractCode != 0) {
            Log.e(TAG, "dpkg-deb extract failed with code $extractCode")
            return false
        }

        onProgress("Fixing script paths…")
        val fixCmd = """
            chmod 700 "$prefix/bin/node" 2>/dev/null

            NPM_CLI="$prefix/lib/node_modules/npm/bin/npm-cli.js"
            if [ -f "${'$'}NPM_CLI" ]; then
                rm -f "$prefix/bin/npm"
                echo "#!$prefix/bin/sh" > "$prefix/bin/npm"
                echo "exec $prefix/bin/node $prefix/lib/node_modules/npm/bin/npm-cli.js \"\${'$'}@\"" >> "$prefix/bin/npm"
                chmod 700 "$prefix/bin/npm"
            fi

            echo "Wrapper scripts created"
        """.trimIndent()
        runInPrefix(fixCmd, onOutput = { onProgress(it) })

        return isNodeInstalled()
    }

    /**
     * Install Python using proot to handle dpkg's hardcoded Termux paths.
     * proot bind-mounts our prefix onto the compiled-in Termux prefix so
     * dpkg postinst scripts and shared library lookups resolve correctly.
     */
    fun installPython(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val termuxPrefix = "/data/data/com.termux/files/usr"

        onProgress("Downloading Python packages…")

        val downloadCmd = """
            cd $prefix/tmp &&
            apt-get update --allow-insecure-repositories 2>&1;
            apt-get download --allow-unauthenticated python python-pip 2>&1
        """.trimIndent()

        val dlCode = runInPrefix(downloadCmd, onOutput = { onProgress(it) })
        if (dlCode != 0) {
            Log.e(TAG, "apt-get download python failed with code $dlCode")
        }

        onProgress("Extracting Python…")
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _python_stage &&
            for deb in python*.deb; do
                [ -f "${'$'}deb" ] && echo "Extracting ${'$'}deb..." && dpkg-deb -x "${'$'}deb" _python_stage/ 2>&1
            done &&
            if [ -d "_python_stage$termuxPrefix" ]; then
                cp -a _python_stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_python_stage/usr" ]; then
                cp -a _python_stage/usr/* "$prefix/" 2>&1
            fi &&
            chmod 700 "$prefix/bin/python"* 2>/dev/null
            chmod 700 "$prefix/bin/pip"* 2>/dev/null
            rm -rf _python_stage python*.deb 2>/dev/null
            echo "Python installed"
        """.trimIndent()

        val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
        if (extractCode != 0) {
            Log.e(TAG, "Python extract failed with code $extractCode")
            return false
        }

        // Create python3 wrapper to handle shebang issues
        val fixCmd = """
            if [ -f "$prefix/bin/python3" ] && [ ! -f "$prefix/bin/python" ]; then
                ln -sf python3 "$prefix/bin/python"
            fi
            echo "Python ready"
        """.trimIndent()
        runInPrefix(fixCmd, onOutput = { onProgress(it) })

        return isPythonInstalled()
    }

    /**
     * Install bionic-compat.js from APK assets into the home directory.
     * This shim patches process.platform, os.cpus(), and
     * os.networkInterfaces() for Android compatibility.
     * Loaded via NODE_OPTIONS="-r <path>/bionic-compat.js".
     */
    fun ensureBionicCompat() {
        val paths = BootstrapInstaller.getPaths(context)
        val patchDir = File(paths.homeDir, ".hermes/patches")
        patchDir.mkdirs()

        val target = File(patchDir, "bionic-compat.js")
        try {
            context.assets.open("bionic-compat.js").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "bionic-compat.js installed to $target")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract bionic-compat.js: ${e.message}")
        }
    }

    /**
     * Install Hermes Agent by extracting and running the hermes_install_offline.sh
     * script from APK assets inside the Termux prefix.
     *
     * The script auto-detects which APK flavor we're running:
     *  - COMPLETE flavor:  Uses pre-bundled .deb packages from assets/complete/
     *                      (zero network access needed)
     *  - BOOTSTRAP flavor: Falls back to downloading from the internet
     *                      (original hermes_install.sh behavior)
     *
     * @return InstallResult with success flag, last 20 lines of output, and log path
     */
    fun installHermes(onProgress: (String) -> Unit): InstallResult {
        val paths = BootstrapInstaller.getPaths(context)
        val logPath = "${paths.prefixDir}/tmp/hermes_install.log"

        // ── Try offline installer first (complete flavor) ─────────────────
        // Extract assets/complete/ contents so the offline script can use them
        val offlineAssetDir = File(paths.prefixDir, "assets/complete")
        val offlineDebsDir = File(offlineAssetDir, "debs")

        // Check if the complete flavor's bundled assets exist in the APK
        val hasOfflineAssets = try {
            context.assets.list("complete/debs")?.isNotEmpty() == true
        } catch (_: Exception) {
            false
        }

        if (hasOfflineAssets) {
            onProgress("Complete flavor detected — extracting offline assets...")
            Log.i(TAG, "Complete flavor: offline assets found in APK")

            try {
                // Extract all files from assets/complete/ into the prefix
                extractAssetDir("complete", offlineAssetDir, onProgress)
                Log.i(TAG, "Offline assets extracted to $offlineAssetDir")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract offline assets: ${e.message}")
                onProgress("WARNING: Offline asset extraction failed, falling back to network mode")
            }
        } else {
            onProgress("Bootstrap flavor — will download dependencies from network")
            Log.i(TAG, "Bootstrap flavor: no offline assets in APK, using network mode")
        }

        // ── Extract the installer script ──────────────────────────────────
        val installScript = File(paths.homeDir, "hermes_install_offline.sh")

        onProgress("Extracting Hermes installer...")
        try {
            context.assets.open("hermes_install_offline.sh").use { input ->
                installScript.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            installScript.setExecutable(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract hermes_install_offline.sh: ${e.message}")
            onProgress("ERROR: ${e.message}")
            return InstallResult(success = false, lastOutput = "ERROR: ${e.message}", logPath = logPath)
        }

        // ── Run the installer with output capture ─────────────────────────
        onProgress("Running Hermes installer (this may take several minutes)...")

        val outputBuffer = mutableListOf<String>()

        // Pass the assets directory path as an environment variable
        val exitCode = runInPrefix(
            "ASSETS_DIR=${paths.prefixDir}/assets sh ${installScript.absolutePath} 2>&1"
        ) { line ->
            outputBuffer.add(line)
            onProgress(line)
        }

        // Get last 20 lines for error display
        val lastOutput = outputBuffer.takeLast(20).joinToString("\n")

        if (exitCode != 0) {
            Log.e(TAG, "hermes_install_offline.sh exited with code $exitCode")
            onProgress("Installation failed (exit code $exitCode)")

            // ── Fallback to original network installer ────────────────────
            onProgress("Attempting fallback to original network installer...")
            return installHermesNetwork(onProgress)
        }

        onProgress("Hermes Agent installed successfully")
        val installed = isHermesInstalled()
        return InstallResult(success = installed, lastOutput = lastOutput, logPath = logPath)
    }

    /**
     * Fallback network-based Hermes installation.
     * Uses the original hermes_install.sh script.
     * Cleans up any prior temp files before starting.
     *
     * @return InstallResult with success flag, last 20 lines of output, and log path
     */
    private fun installHermesNetwork(onProgress: (String) -> Unit): InstallResult {
        val paths = BootstrapInstaller.getPaths(context)
        val logPath = "${paths.prefixDir}/tmp/hermes_install.log"
        val installScript = File(paths.homeDir, "hermes_install.sh")

        // Clear prior install temp files for clean retry
        onProgress("Cleaning up prior install state...")
        runInPrefix("rm -rf ${paths.prefixDir}/tmp/hermes* ${paths.prefixDir}/tmp/_hermes* 2>/dev/null; echo 'Cleanup done'")

        onProgress("Extracting Hermes network installer...")
        try {
            context.assets.open("hermes_install.sh").use { input ->
                installScript.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            installScript.setExecutable(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract hermes_install.sh: ${e.message}")
            onProgress("ERROR: ${e.message}")
            return InstallResult(success = false, lastOutput = "ERROR: ${e.message}", logPath = logPath)
        }

        onProgress("Running Hermes network installer (this may take several minutes)...")

        val outputBuffer = mutableListOf<String>()
        val exitCode = runInPrefix("sh ${installScript.absolutePath} 2>&1") { line ->
            outputBuffer.add(line)
            onProgress(line)
        }

        val lastOutput = outputBuffer.takeLast(20).joinToString("\n")

        if (exitCode != 0) {
            Log.e(TAG, "hermes_install.sh exited with code $exitCode")
            onProgress("Installation failed (exit code $exitCode)")
            return InstallResult(success = false, lastOutput = lastOutput, logPath = logPath)
        }

        onProgress("Hermes Agent installed successfully")
        val installed = isHermesInstalled()
        return InstallResult(success = installed, lastOutput = lastOutput, logPath = logPath)
    }

    /**
     * Recursively extract an asset directory to the filesystem.
     * Used to extract assets/complete/ for the offline flavor.
     */
    private fun extractAssetDir(
        assetPath: String,
        targetDir: File,
        onProgress: (String) -> Unit,
    ) {
        targetDir.mkdirs()

        val entries = context.assets.list(assetPath) ?: return

        for (entry in entries) {
            val fullAssetPath = if (assetPath.isEmpty()) entry else "$assetPath/$entry"
            val targetFile = File(targetDir, entry)

            try {
                // Check if it's a directory (has children) or a file
                val subEntries = context.assets.list(fullAssetPath)
                if (subEntries != null && subEntries.isNotEmpty()) {
                    // It's a directory — recurse
                    extractAssetDir(fullAssetPath, targetFile, onProgress)
                } else {
                    // It's a file — copy it
                    targetFile.parentFile?.mkdirs()
                    context.assets.open(fullAssetPath).use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    onProgress("  Extracted: $entry")
                }
            } catch (e: Exception) {
                // Try as a file (list may throw if it's a file)
                try {
                    targetFile.parentFile?.mkdirs()
                    context.assets.open(fullAssetPath).use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e2: Exception) {
                    Log.w(TAG, "Failed to extract $fullAssetPath: ${e2.message}")
                }
            }
        }
    }

    // ── Codex Installation ───────────────────────────────────────────────

    /**
     * Install Codex CLI via npm global install.
     * After installing, ensures the codex wrapper script is created.
     */
    fun installCodex(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val npmCli = "$prefix/lib/node_modules/npm/bin/npm-cli.js"

        onProgress("Installing Codex CLI…")
        val codexCode = runInPrefix(
            "node $npmCli install -g @openai/codex 2>&1",
            onOutput = { onProgress(it) },
        )
        if (codexCode != 0) {
            Log.e(TAG, "npm install @openai/codex failed with code $codexCode")
            return false
        }

        ensureCodexWrapperScript()
        return isCodexInstalled()
    }

    /**
     * Create the codex wrapper script so that `codex` can be invoked
     * from the prefix bin directory.
     */
    private fun ensureCodexWrapperScript() {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val codexJs = File(prefix, "lib/node_modules/@openai/codex/bin/codex.js")
        val codexBin = File(prefix, "bin/codex")

        if (!codexJs.exists()) return
        if (codexBin.exists()) return

        val wrapperCmd = """
            rm -f "$prefix/bin/codex"
            cat > "$prefix/bin/codex" << 'WEOF'
#!$prefix/bin/sh
exec $prefix/bin/node $prefix/lib/node_modules/@openai/codex/bin/codex.js "${'$'}@"
WEOF
            chmod 700 "$prefix/bin/codex"
            echo "codex wrapper created"
        """.trimIndent()
        runInPrefix(wrapperCmd)
        Log.i(TAG, "Created codex wrapper at $codexBin")
    }

    // ── OpenClaw Installation ────────────────────────────────────────────

    /**
     * Install OpenClaw via npm global install (lightweight version).
     * Uses --ignore-scripts to skip native module builds on Android.
     */
    fun installOpenClaw(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val npmCli = "$prefix/lib/node_modules/npm/bin/npm-cli.js"

        // Create directories OpenClaw expects
        runInPrefix("mkdir -p ${paths.homeDir}/.openclaw")

        onProgress("Installing OpenClaw (npm)…")
        val installCode = runInPrefix(
            "node $npmCli install -g --ignore-scripts openclaw@latest 2>&1",
            onOutput = { onProgress(it) },
        )
        if (installCode != 0) {
            Log.e(TAG, "npm install openclaw failed with code $installCode")
            return false
        }

        // Patch the openclaw.mjs shebang for Android
        val patchCmd = """
            ODIR="$prefix/lib/node_modules/openclaw"
            [ ! -d "${'$'}ODIR" ] && echo "OpenClaw dir not found" && exit 0
            if [ -f "${'$'}ODIR/openclaw.mjs" ]; then
                sed -i "1s|#!/usr/bin/env node|#!$prefix/bin/node|" "${'$'}ODIR/openclaw.mjs"
            fi
            echo "OpenClaw path patches applied"
        """.trimIndent()
        runInPrefix(patchCmd) { Log.d(TAG, "[openclaw-install] $it") }

        Log.i(TAG, "OpenClaw installed successfully")
        return isOpenClawInstalled()
    }

    // ── Agent Configuration ──────────────────────────────────────────────

    /**
     * Configure Hermes Agent by writing ~/.hermes/.env with a placeholder
     * API key and ~/.hermes/config.yaml with basic configuration.
     */
    fun configureHermes() {
        val paths = BootstrapInstaller.getPaths(context)
        val hermesDir = File(paths.homeDir, ".hermes")
        hermesDir.mkdirs()

        // Write .env with placeholder API key
        val envFile = File(hermesDir, ".env")
        val envContent = """
            |# Hermes Agent environment configuration
            |HERMES_API_KEY=sk-pla...-key
            |HERMES_MODEL=hermes-agent
            |HERMES_PROVIDER=nousresearch
        """.trimMargin()
        envFile.writeText(envContent)
        Log.i(TAG, "Wrote Hermes .env to $envFile")

        // Write config.yaml with basic configuration
        val configFile = File(hermesDir, "config.yaml")
        val configContent = """
            |# Hermes Agent configuration
            |server:
            |  port: $WEBVIEW_PORT
            |  host: "127.0.0.1"
            |
            |gateway:
            |  port: $HERMES_GATEWAY_PORT
            |  control_ui_port: $HERMES_CONTROL_UI_PORT
            |
            |cron:
            |  enabled: true
            |
            |logging:
            |  level: "info"
            |
            |# Skills and plugins directories
            |skills_dir: "~/.hermes/skills"
            |plugins_dir: "~/.hermes/plugins"
            |cron_dir: "~/.hermes/cron"
            |memories_dir: "~/.hermes/memories"
        """.trimMargin()
        configFile.writeText(configContent)
        Log.i(TAG, "Wrote Hermes config.yaml to $configFile")
    }

    /**
     * Configure Hermes authentication. Hermes reads credentials from
     * ~/.hermes/.env, so this is equivalent to [configureHermes].
     */
    fun configureHermesAuth() {
        configureHermes()
    }

    /**
     * Configure OpenClaw by writing openclaw.json with gateway settings
     * and copying Codex auth tokens into OpenClaw's auth-profiles.json.
     * Uses auth=none + dangerouslyDisableDeviceAuth for local access.
     */
    fun configureOpenClawAuth() {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val openclawDir = File(paths.homeDir, ".openclaw")
        openclawDir.mkdirs()

        val configFile = File(openclawDir, "openclaw.json")
        val configJson = """
            |{
            |  "meta": {
            |    "lastTouchedVersion": "2026.2.21-2",
            |    "lastTouchedAt": "${java.time.Instant.now()}"
            |  },
            |  "commands": {
            |    "native": "auto",
            |    "nativeSkills": "auto",
            |    "restart": true,
            |    "ownerDisplay": "raw"
            |  },
            |  "gateway": {
            |    "mode": "local",
            |    "controlUi": {
            |      "enabled": true,
            |      "allowedOrigins": ["http://127.0.0.1:$OPENCLAW_GATEWAY_PORT", "http://localhost:$OPENCLAW_GATEWAY_PORT"],
            |      "allowInsecureAuth": true,
            |      "dangerouslyDisableDeviceAuth": true
            |    },
            |    "auth": {
            |      "mode": "none"
            |    }
            |  },
            |  "agents": {
            |    "defaults": {
            |      "model": {
            |        "primary": "openai-codex/gpt-5.3-codex"
            |      }
            |    }
            |  }
            |}
        """.trimMargin()
        configFile.writeText(configJson)
        Log.i(TAG, "Wrote OpenClaw config to $configFile")

        // Copy the Codex access_token into OpenClaw's auth-profiles.json
        val authJson = File(paths.homeDir, ".codex/auth.json")
        if (authJson.exists()) {
            val copyScript = """
                node -e "
                  const fs = require('fs');
                  const path = require('path');
                  const auth = JSON.parse(fs.readFileSync('${'$'}HOME/.codex/auth.json','utf8'));
                  const token = auth.tokens && auth.tokens.access_token;
                  if (!token) { console.error('No access_token in codex auth'); process.exit(1); }
                  const profiles = {
                    version: 1,
                    profiles: {
                      'openai-codex:codex-cli': {
                        type: 'token',
                        provider: 'openai-codex',
                        token: token,
                        source: 'codex-auth',
                        createdAt: new Date().toISOString()
                      },
                      'openai:codex': {
                        type: 'token',
                        provider: 'openai',
                        token: token,
                        source: 'codex-auth',
                        createdAt: new Date().toISOString()
                      }
                    },
                    order: ['openai-codex:codex-cli', 'openai:codex']
                  };
                  const json = JSON.stringify(profiles, null, 2);
                  fs.writeFileSync('${'$'}HOME/.openclaw/auth-profiles.json', json);
                  const agentDir = '${'$'}HOME/.openclaw/agents/main/agent';
                  fs.mkdirSync(agentDir, { recursive: true });
                  fs.writeFileSync(path.join(agentDir, 'auth-profiles.json'), json);
                  console.log('OpenClaw auth-profiles.json written (global + agent)');
                " 2>&1
            """.trimIndent()
            runInPrefix(copyScript) { Log.d(TAG, "[openclaw-auth] $it") }
        } else {
            Log.w(TAG, "Codex auth.json not found — OpenClaw will lack API credentials")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Hermes Gateway lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Check whether the Hermes gateway process is currently running.
     */
    fun isHermesGatewayRunning(): Boolean {
        val proc = hermesGatewayProcess ?: return false
        return try {
            proc.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    /**
     * Start the Hermes gateway server inside the Termux prefix.
     * Requires [configureHermes] to be called first.
     */
    fun startHermesGateway(): Boolean {
        if (isHermesGatewayRunning()) {
            Log.i(TAG, "Hermes gateway already running")
            return true
        }

        val paths = BootstrapInstaller.getPaths(context)

        // Kill any orphaned gateway processes
        runInPrefix("""
            for pid in ${'$'}(ls /proc 2>/dev/null | grep '^[0-9]'); do
                if cat /proc/${'$'}pid/cmdline 2>/dev/null | tr '\0' ' ' | grep -q "$HERMES_GATEWAY_PORT"; then
                    kill -9 ${'$'}pid 2>/dev/null
                fi
            done
            rm -f ${paths.prefixDir}/tmp/hermes-gateway.pid ${paths.prefixDir}/tmp/hermes-gateway.lock 2>/dev/null
            sleep 1
            echo "Gateway state cleaned"
        """.trimIndent()) { Log.d(TAG, "[hermes-gw] $it") }

        val env = buildEnvironment(paths)
        val shell = "${paths.prefixDir}/bin/sh"
        val cmd = "exec hermes gateway run --port $HERMES_GATEWAY_PORT 2>&1"

        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        hermesGatewayProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[hermes-gw] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "Hermes gateway exited with code: ${proc.waitFor()}")
        }.start()

        Thread.sleep(3000)
        Log.i(TAG, "Hermes gateway started on port $HERMES_GATEWAY_PORT")
        return true
    }

    /**
     * Stop the Hermes gateway process if it is running.
     */
    fun stopHermesGateway() {
        val proc = hermesGatewayProcess
        hermesGatewayProcess = null

        if (proc != null) {
            try {
                proc.destroy()
                proc.waitFor()
                Log.i(TAG, "Hermes gateway stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping Hermes gateway: ${e.message}")
            }
        }

        // Also kill the control UI if running
        stopHermesControlUi()
    }

    /**
     * Start the Hermes Control UI server.
     */
    fun startHermesControlUi(): Boolean {
        if (hermesControlUiProcess != null) {
            try {
                hermesControlUiProcess!!.exitValue()
                hermesControlUiProcess = null
            } catch (_: IllegalThreadStateException) {
                Log.i(TAG, "Hermes Control UI already running")
                return true
            }
        }

        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths)

        val shell = "${paths.prefixDir}/bin/sh"
        val cmd = "hermes control-ui serve --port $HERMES_CONTROL_UI_PORT 2>&1"

        val pb = ProcessBuilder(shell, "-c", "exec $cmd")
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        hermesControlUiProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[hermes-ui] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "Hermes Control UI exited with code: ${proc.waitFor()}")
        }.start()

        Thread.sleep(1000)
        Log.i(TAG, "Hermes Control UI started on port $HERMES_CONTROL_UI_PORT")
        return true
    }

    private fun stopHermesControlUi() {
        hermesControlUiProcess?.destroy()
        hermesControlUiProcess = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Codex Gateway lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Start the Codex app-server gateway.
     * Runs `codex app-server` to serve the Codex WebView UI.
     */
    fun startCodexGateway(): Boolean {
        if (codexProcess != null) {
            try {
                codexProcess!!.exitValue()
                codexProcess = null
            } catch (_: IllegalThreadStateException) {
                Log.i(TAG, "Codex gateway already running")
                return true
            }
        }

        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths).toMutableMap()
        env["HTTPS_PROXY"] = "http://127.0.0.1:$PROXY_PORT"
        env["HTTP_PROXY"] = "http://127.0.0.1:$PROXY_PORT"

        val shell = "${paths.prefixDir}/bin/sh"
        val cmd = "exec codex app-server --port $CODEX_GATEWAY_PORT --no-password 2>&1"

        Log.i(TAG, "Starting Codex gateway: $cmd")

        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        codexProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[codex] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "Codex gateway exited with code: ${proc.waitFor()}")
        }.start()

        Thread.sleep(3000)
        Log.i(TAG, "Codex gateway started on port $CODEX_GATEWAY_PORT")
        return true
    }

    private fun stopCodexGateway() {
        codexProcess?.destroy()
        codexProcess = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // OpenClaw Gateway lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Start the OpenClaw WebSocket gateway. Requires openclaw.json to be
     * configured first via [configureOpenClawAuth].
     */
    fun startOpenClawGateway(): Boolean {
        if (openclawGatewayProcess != null) {
            try {
                openclawGatewayProcess!!.exitValue()
                openclawGatewayProcess = null
            } catch (_: IllegalThreadStateException) {
                Log.i(TAG, "OpenClaw gateway already running")
                return true
            }
        }

        val paths = BootstrapInstaller.getPaths(context)

        // Kill any orphaned gateway processes and reset device tokens
        runInPrefix("""
            # Kill by PID file
            for pidfile in ${paths.prefixDir}/tmp/openclaw*/gateway.pid ${paths.prefixDir}/tmp/openclaw/gateway.pid; do
                [ -f "${'$'}pidfile" ] && kill -9 ${'$'}(cat "${'$'}pidfile" 2>/dev/null) 2>/dev/null
            done
            # Scan /proc for any node process bound to the gateway port
            for pid in ${'$'}(ls /proc 2>/dev/null | grep '^[0-9]'); do
                if cat /proc/${'$'}pid/cmdline 2>/dev/null | tr '\0' ' ' | grep -q "$OPENCLAW_GATEWAY_PORT"; then
                    kill -9 ${'$'}pid 2>/dev/null
                fi
            done
            # Clear stale lock/pid files
            rm -f ${paths.prefixDir}/tmp/openclaw*/gateway.lock ${paths.prefixDir}/tmp/openclaw*/gateway.pid 2>/dev/null
            rm -f ${paths.prefixDir}/tmp/openclaw/gateway.lock ${paths.prefixDir}/tmp/openclaw/gateway.pid 2>/dev/null
            sleep 1
            echo "Gateway state cleaned"
        """.trimIndent()) { Log.d(TAG, "[openclaw-gw] $it") }

        val env = buildEnvironment(paths)
        val shell = "${paths.prefixDir}/bin/sh"
        val cmd = "exec openclaw gateway run --force --port $OPENCLAW_GATEWAY_PORT 2>&1"

        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        openclawGatewayProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[openclaw-gw] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "OpenClaw gateway exited with code: ${proc.waitFor()}")
        }.start()

        Thread.sleep(5000)
        Log.i(TAG, "OpenClaw gateway started on port $OPENCLAW_GATEWAY_PORT")
        return true
    }

    /**
     * Start a lightweight static file server to serve the OpenClaw
     * Control UI.
     */
    fun startOpenClawControlUi(): Boolean {
        if (openclawControlUiProcess != null) {
            try {
                openclawControlUiProcess!!.exitValue()
                openclawControlUiProcess = null
            } catch (_: IllegalThreadStateException) {
                Log.i(TAG, "OpenClaw Control UI already running")
                return true
            }
        }

        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths)
        val prefix = paths.prefixDir
        val controlUiRoot = "$prefix/lib/node_modules/openclaw/dist/control-ui"

        if (!File(controlUiRoot).exists()) {
            Log.w(TAG, "OpenClaw control-ui directory not found at $controlUiRoot")
            return false
        }

        val shell = "${paths.prefixDir}/bin/sh"
        val serverScript = """
            node -e "
              const http = require('http');
              const fs = require('fs');
              const path = require('path');
              const root = '$controlUiRoot';
              const mimeTypes = {
                '.html':'text/html','.js':'application/javascript',
                '.css':'text/css','.json':'application/json',
                '.svg':'image/svg+xml','.png':'image/png',
                '.woff2':'font/woff2','.woff':'font/woff',
              };
              http.createServer((req, res) => {
                let url = req.url.split('?')[0];
                if (url === '/') url = '/index.html';
                const fp = path.join(root, url);
                if (!fp.startsWith(root)) { res.writeHead(403); return res.end(); }
                fs.readFile(fp, (err, data) => {
                  if (err) {
                    fs.readFile(path.join(root,'index.html'), (e2, d2) => {
                      res.writeHead(200, {'Content-Type':'text/html'});
                      res.end(d2);
                    });
                    return;
                  }
                  const ext = path.extname(fp);
                  res.writeHead(200, {'Content-Type': mimeTypes[ext]||'application/octet-stream'});
                  res.end(data);
                });
              }).listen($OPENCLAW_GATEWAY_PORT, '127.0.0.1', () => console.log('Control UI on port $OPENCLAW_GATEWAY_PORT'));
            " 2>&1
        """.trimIndent()

        val pb = ProcessBuilder(shell, "-c", "exec $serverScript")
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        openclawControlUiProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[openclaw-ui] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "OpenClaw Control UI exited with code: ${proc.waitFor()}")
        }.start()

        Thread.sleep(1000)
        Log.i(TAG, "OpenClaw Control UI started on port $OPENCLAW_GATEWAY_PORT")
        return true
    }

    private fun stopOpenClaw() {
        openclawGatewayProcess?.destroy()
        openclawGatewayProcess = null
        openclawControlUiProcess?.destroy()
        openclawControlUiProcess = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Claude Code lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Start Claude Code in server mode on [CLAW_CODE_PORT].
     * Claude Code supports a local HTTP API for chat interactions.
     */
    fun startClawCode(): Boolean {
        if (clawCodeProcess != null) {
            try {
                clawCodeProcess!!.exitValue()
                clawCodeProcess = null
            } catch (_: IllegalThreadStateException) {
                Log.i(TAG, "Claude Code already running")
                return true
            }
        }

        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths).toMutableMap()
        env["HTTPS_PROXY"] = "http://127.0.0.1:$PROXY_PORT"
        env["HTTP_PROXY"] = "http://127.0.0.1:$PROXY_PORT"

        val shell = "${paths.prefixDir}/bin/sh"
        val cmd = "exec claude serve --port $CLAW_CODE_PORT 2>&1"

        Log.i(TAG, "Starting Claude Code: $cmd")

        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        clawCodeProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[clawcode] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "Claude Code exited with code: ${proc.waitFor()}")
        }.start()

        Thread.sleep(3000)
        Log.i(TAG, "Claude Code started on port $CLAW_CODE_PORT")
        return true
    }

    private fun stopClawCode() {
        clawCodeProcess?.destroy()
        clawCodeProcess = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Hermes Cron
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Start Hermes cron runner in a background thread.
     * Runs `hermes cron run --all` to execute all scheduled cron jobs.
     * Non-blocking — fires and returns immediately.
     */
    fun startHermesCron(): Boolean {
        Log.i(TAG, "Starting Hermes cron runner…")
        Thread {
            try {
                val exitCode = runHermesCommand("cron run --all") { Log.d(TAG, "[hermes-cron] $it") }
                Log.i(TAG, "Hermes cron finished with exit code $exitCode")
            } catch (e: Exception) {
                Log.e(TAG, "Hermes cron failed: ${e.message}")
            }
        }.start()
        return true
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Proxy
    // ═══════════════════════════════════════════════════════════════════════

    fun startProxy(): Boolean {
        if (proxyProcess != null) return true

        val paths = BootstrapInstaller.getPaths(context)
        val proxyScript = File(paths.homeDir, "proxy.js")

        try {
            context.assets.open("proxy.js").use { input ->
                proxyScript.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract proxy.js: ${e.message}")
            return false
        }

        val env = buildEnvironment(paths)
        val shell = "${paths.prefixDir}/bin/sh"
        val cmd = "exec node ${proxyScript.absolutePath}"

        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        proxyProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[proxy] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "Proxy exited with code: ${proc.waitFor()}")
        }.start()

        Thread.sleep(800)
        Log.i(TAG, "CONNECT proxy started on 127.0.0.1:$PROXY_PORT")
        return true
    }

    fun stopProxy() {
        proxyProcess?.destroy()
        proxyProcess = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Unified server lifecycle (multi-agent aware)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Start the active agent's server. Delegates to the appropriate
     * agent-specific starter based on [activeAgent].
     */
    fun startServer(): Boolean {
        return when (activeAgent) {
            AgentType.HERMES -> startHermesServer()
            AgentType.CODEX -> startCodexGateway()
            AgentType.OPENCLAW -> startOpenClawGateway()
            AgentType.CLAW_CODE -> startClawCode()
        }
    }

    /**
     * Start the Hermes server process (the original startServer impl).
     */
    private fun startHermesServer(): Boolean {
        if (isRunning) {
            Log.i(TAG, "Hermes server already running")
            return true
        }

        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths).toMutableMap()
        env["HTTPS_PROXY"] = "http://127.0.0.1:$PROXY_PORT"
        env["HTTP_PROXY"] = "http://127.0.0.1:$PROXY_PORT"

        val shell = "${paths.prefixDir}/bin/sh"
        val command = "exec hermes server start --port $WEBVIEW_PORT 2>&1"

        Log.i(TAG, "Starting Hermes server: $command")

        val pb = ProcessBuilder(shell, "-c", command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        serverProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[server] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "Hermes server exited with code: ${proc.waitFor()}")
        }.start()

        return true
    }

    /**
     * Alias for [startServer]. Starts the WebView server on [WEBVIEW_PORT].
     */
    fun startWebViewServer(): Boolean {
        return startServer()
    }

    fun waitForServer(timeoutMs: Long = 60_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val port = when (activeAgent) {
            AgentType.HERMES -> WEBVIEW_PORT
            AgentType.CODEX -> CODEX_GATEWAY_PORT
            AgentType.OPENCLAW -> OPENCLAW_GATEWAY_PORT
            AgentType.CLAW_CODE -> CLAW_CODE_PORT
        }
        val url = URL("http://127.0.0.1:$port/")

        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..399) {
                    Log.i(TAG, "Server is ready (HTTP $code)")
                    return true
                }
            } catch (_: Exception) {
                // Not ready yet
            }
            Thread.sleep(500)
        }

        Log.e(TAG, "Server did not become ready within ${timeoutMs}ms")
        return false
    }

    fun stopServer() {
        val proc = serverProcess
        serverProcess = null

        if (proc != null) {
            try {
                proc.destroy()
                proc.waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying server process: ${e.message}")
            }
        }

        stopHermesGateway()
        stopCodexGateway()
        stopOpenClaw()
        stopClawCode()
        stopProxy()
        Log.i(TAG, "Server stopped")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Agent selector API (served by WebView server)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns JSON for the /api/agents endpoint listing all available agents
     * and their installation/activation status.
     */
    fun getAgentsJson(): String {
        val agents = AgentType.entries.joinToString(",\n    ") { agent ->
            val installed = when (agent) {
                AgentType.HERMES -> isHermesInstalled()
                AgentType.CODEX -> isCodexInstalled()
                AgentType.OPENCLAW -> isOpenClawInstalled()
                AgentType.CLAW_CODE -> isClawCodeInstalled()
            }
            val active = agent == activeAgent
            val port = when (agent) {
                AgentType.HERMES -> WEBVIEW_PORT
                AgentType.CODEX -> CODEX_GATEWAY_PORT
                AgentType.OPENCLAW -> OPENCLAW_GATEWAY_PORT
                AgentType.CLAW_CODE -> CLAW_CODE_PORT
            }
            """
            |    {
            |      "id": "${agent.name.lowercase()}",
            |      "name": "${agent.displayName}",
            |      "installed": $installed,
            |      "active": $active,
            |      "port": $port
            |    }""".trimMargin()
        }
        return "{\n  \"agents\": [\n$agents\n  ]\n}"
    }

    /**
     * Handle an agent switch request from /api/agents/switch.
     * @param agentId The agent ID to switch to (e.g. "hermes", "codex")
     * @return JSON response with success status
     */
    fun handleAgentSwitch(agentId: String): String {
        val type = try {
            AgentType.valueOf(agentId.uppercase())
        } catch (_: IllegalArgumentException) {
            return """{"error": "Unknown agent: $agentId", "success": false}"""
        }

        if (!when (type) {
                AgentType.HERMES -> isHermesInstalled()
                AgentType.CODEX -> isCodexInstalled()
                AgentType.OPENCLAW -> isOpenClawInstalled()
                AgentType.CLAW_CODE -> isClawCodeInstalled()
            }) {
            return """{"error": "${type.displayName} is not installed", "success": false}"""
        }

        val success = switchAgent(type)
        return """{"success": $success, "agent": "${type.name.lowercase()}", "name": "${type.displayName}"}"""
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Environment
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildEnvironment(
        paths: BootstrapInstaller.Paths,
    ): Map<String, String> {
        val bionicCompat = "${paths.homeDir}/.hermes/patches/bionic-compat.js"
        val bionicCompatOpt = if (File(bionicCompat).exists()) " -r $bionicCompat" else ""

        return mapOf(
            "PREFIX" to paths.prefixDir,
            "HOME" to paths.homeDir,
            "PATH" to "${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin",
            "LD_LIBRARY_PATH" to "${paths.prefixDir}/lib",
            "LD_PRELOAD" to "${paths.prefixDir}/lib/libtermux-exec.so",
            "TERMUX_PREFIX" to paths.prefixDir,
            "TERMUX__PREFIX" to paths.prefixDir,
            "LANG" to "en_US.UTF-8",
            "TMPDIR" to paths.tmpDir,
            "TMP" to paths.tmpDir,
            "TEMP" to paths.tmpDir,
            "PROOT_TMP_DIR" to paths.tmpDir,
            "TERM" to "xterm-256color",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "APT_CONFIG" to "${paths.prefixDir}/etc/apt/apt.conf",
            "DPKG_ADMINDIR" to "${paths.prefixDir}/var/lib/dpkg",
            "SSL_CERT_FILE" to "${paths.prefixDir}/etc/tls/cert.pem",
            "SSL_CERT_DIR" to "/system/etc/security/cacerts",
            "CURL_CA_BUNDLE" to "${paths.prefixDir}/etc/tls/cert.pem",
            "NODE_OPTIONS" to "--openssl-config=${paths.prefixDir}/etc/tls/openssl.cnf --unhandled-rejections=warn$bionicCompatOpt",
            "CONTAINER" to "1",
        )
    }
}
