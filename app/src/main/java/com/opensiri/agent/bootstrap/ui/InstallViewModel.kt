package com.opensiri.agent.bootstrap.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opensiri.agent.bootstrap.BootstrapInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

enum class PhaseState { PENDING, ACTIVE, COMPLETED, FAILED }
enum class StepState { PENDING, CURRENT, DONE }

data class SubStep(
    val name: String,
    val size: String
)

data class Phase(
    val id: Int,
    val icon: String,
    val name: String,
    val desc: String,
    val badge: String,
    val sizeMB: Float,
    val steps: List<SubStep>,
    var state: PhaseState = PhaseState.PENDING
)

data class LogLine(
    val message: String,
    val type: String = "" // "", "warn", "error", "success"
)

data class InstallState(
    val phases: List<Phase> = listOf(
        Phase(0, "🔍", "Checking environment", "Verifying system compatibility", "Setup", 4.2f, listOf(
            SubStep("Termux bootstrap", "4.2 MB"),
            SubStep("Filesystem mount", "—"),
            SubStep("PATH configuration", "—"),
        )),
        Phase(1, "📦", "Installing dependencies", "System tools required for Hermes", "pkg", 61f, listOf(
            SubStep("git (version control)", "12.3 MB"),
            SubStep("nodejs-lts (runtime)", "48.7 MB"),
            SubStep("npm (package manager)", "included"),
        )),
        Phase(2, "⬇️", "Downloading Hermes Agent", "Cloning from GitHub", "git", 8f, listOf(
            SubStep("hermes-agent.git (shallow)", "~8 MB"),
            SubStep("Verifying integrity", "—"),
        )),
        Phase(3, "📥", "Installing packages", "npm dependencies", "npm", 85f, listOf(
            SubStep("node_modules/", "~85 MB"),
            SubStep("Resolving peer deps", "—"),
        )),
        Phase(4, "🔧", "Building", "Compiling Hermes Agent", "build", 0f, listOf(
            SubStep("TypeScript compilation", "—"),
            SubStep("Linking CLI binary", "—"),
        )),
        Phase(5, "✅", "Verifying installation", "Running health checks", "verify", 0f, listOf(
            SubStep("hermes --version", "—"),
            SubStep("PATH check", "—"),
            SubStep("Config init", "—"),
        )),
    ),
    val currentPhase: Int = 0,
    val currentStep: Int = 0,
    val downloadedMB: Float = 0f,
    val totalMB: Float = 158.2f,
    val logLines: List<LogLine> = emptyList(),
    val isRunning: Boolean = false,
    val isComplete: Boolean = false,
    val hasError: Boolean = false,
)

class InstallViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(InstallState())
    val state: StateFlow<InstallState> = _state.asStateFlow()

    fun startInstall() {
        if (_state.value.isRunning) return

        _state.value = _state.value.copy(isRunning = true, hasError = false, logLines = emptyList())
        addLog("Starting installation...")
        runInstallScript()
    }

    fun retryInstall() {
        _state.value = InstallState()
        startInstall()
    }

    private fun addLog(message: String, type: String = "") {
        _state.value = _state.value.copy(
            logLines = _state.value.logLines + LogLine(message, type)
        )
    }

    private fun runInstallScript() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()

                // Step 1: Extract the Termux bootstrap into filesDir/usr so the
                // shell can find pkg, proot, git, node, npm, etc. Without this
                // step, $PREFIX/bin/pkg doesn't exist and 'pkg install' fails.
                addLog("Extracting Termux bootstrap...")
                val paths = BootstrapInstaller.getPaths(context)
                if (!BootstrapInstaller.isBootstrapInstalled(context)) {
                    BootstrapInstaller.install(context, onProgress = { addLog(it) })
                } else {
                    addLog("Bootstrap already installed at ${paths.prefixDir}")
                }

                // Step 2: Copy install script out of APK assets/ into filesDir where
                // the shell can actually read it. `sh -c "cat assets/hermes_install.sh"`
                // does NOT work — `assets/` is a virtual path inside the APK and
                // the shell's CWD has no such file.
                val scriptFile = File(context.filesDir, "hermes_install.sh")
                context.assets.open("hermes_install.sh").use { input ->
                    scriptFile.outputStream().use { output -> input.copyTo(output) }
                }
                scriptFile.setExecutable(true)

                val process = ProcessBuilder()
                    .command("sh", scriptFile.absolutePath)
                    .redirectErrorStream(true)
                    .apply {
                        val env = environment()
                        // Termux prefix so the script, pkg, and apt know where
                        // everything lives. Maps to BootstrapInstaller.getPaths().
                        env["PREFIX"] = paths.prefixDir
                        env["HOME"] = paths.homeDir
                        env["TMPDIR"] = paths.tmpDir
                        // The script reads PACKAGE for /data/user/0/<PACKAGE> prefix.
                        env["PACKAGE"] = context.packageName
                        // Put Termux tools FIRST in PATH so `pkg install` finds them.
                        env["PATH"] = "${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin:/system/xbin"
                        // Termux binaries (mkdir, bash, coreutils, curl, etc.) need
                        // libandroid-support.so and related libs from the bootstrap.
                        env["LD_LIBRARY_PATH"] = "${paths.prefixDir}/lib:${paths.prefixDir}/data/data/com.termux/files/usr/lib"
                        // Git needs to find its helpers (git-remote-https, etc.) at
                        // the Termux-compiled path inside our prefix.
                        env["GIT_EXEC_PATH"] = "${paths.prefixDir}/data/data/com.termux/files/usr/libexec/git-core"
                        // Git SSL CA bundle — skip for bootstrap; real certs require
                        // ca-certificates package. On first launch we may not have it.
                        env["GIT_SSL_NO_VERIFY"] = "true"
                        // Android file descriptor sanitizer interferes with Termux
                        // binaries (apt-get, dpkg, etc.) that were compiled for a
                        // different libc. Shut it down entirely in the install env.
                        env["ANDROID_FDSAN"] = "off"
                    }
                    .start()

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    val text = line ?: continue
                    processLine(text)
                }

                process.waitFor()
                if (process.exitValue() != 0) {
                    addLog("Installation failed with exit code ${process.exitValue()}", "error")
                    markCurrentPhaseFailed()
                } else {
                    completeAllPhases()
                }
            } catch (e: Exception) {
                addLog("Fatal: ${e.message}", "error")
                markCurrentPhaseFailed()
            }
        }
    }

    private fun processLine(line: String) {
        // Parse phase markers: [phase:N] description
        val phaseRegex = Regex("\\[phase:(\\d+|done)\\]\\s*(.*)")
        val match = phaseRegex.find(line)

        if (match != null) {
            val phaseId = match.groupValues[1]
            val desc = match.groupValues[2]
            addLog(line)

            if (phaseId == "done") {
                completeAllPhases()
                return
            }

            val id = phaseId.toIntOrNull() ?: return
            // Complete previous phases and set current
            _state.value = _state.value.copy(
                phases = _state.value.phases.mapIndexed { index, phase ->
                    when {
                        index < id -> phase.copy(state = PhaseState.COMPLETED)
                        index == id -> phase.copy(state = PhaseState.ACTIVE)
                        else -> phase.copy(state = PhaseState.PENDING)
                    }
                },
                currentPhase = id,
                currentStep = 0,
                downloadedMB = _state.value.phases.filter { it.id < id }.sumOf { it.sizeMB.toDouble() }.toFloat()
            )
        } else {
            // Sub-step progress
            val trimmed = line.trim()
            when {
                trimmed.startsWith("✓") -> {
                    addLog(trimmed, "success")
                    advanceStep()
                }
                trimmed.contains("✗") || trimmed.contains("FATAL") || trimmed.contains("failed") -> {
                    addLog(trimmed, "error")
                }
                trimmed.contains("⚠") || trimmed.contains("warn") -> {
                    addLog(trimmed, "warn")
                }
                else -> addLog(trimmed)
            }
        }
    }

    private fun advanceStep() {
        val s = _state.value
        val phase = s.phases.getOrNull(s.currentPhase) ?: return
        val nextStep = s.currentStep + 1
        if (nextStep <= phase.steps.size) {
            // Update download progress for this step
            val stepFraction = if (phase.steps.isNotEmpty()) 1f / phase.steps.size else 1f
            _state.value = s.copy(
                currentStep = nextStep,
                downloadedMB = s.downloadedMB + phase.sizeMB * stepFraction
            )
        }
    }

    private fun markCurrentPhaseFailed() {
        _state.value = _state.value.copy(
            isRunning = false,
            hasError = true,
            phases = _state.value.phases.mapIndexed { index, phase ->
                if (index == _state.value.currentPhase)
                    phase.copy(state = PhaseState.FAILED)
                else phase
            }
        )
    }

    private fun completeAllPhases() {
        _state.value = _state.value.copy(
            isRunning = false,
            isComplete = true,
            downloadedMB = _state.value.totalMB,
            phases = _state.value.phases.map { it.copy(state = PhaseState.COMPLETED) }
        )
        addLog("✓ Installation complete! Hermes Agent ready.", "success")
    }
}
