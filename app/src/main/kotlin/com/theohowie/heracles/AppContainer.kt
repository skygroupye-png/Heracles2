package com.theohowie.heracles

import android.content.Context
import com.theohowie.heracles.agent.AutomationRunner
import com.theohowie.heracles.agent.HeraclesAgent
import com.theohowie.heracles.agent.ToolRegistry
import com.theohowie.heracles.bridge.DeviceFs
import com.theohowie.heracles.bridge.DeviceHealth
import com.theohowie.heracles.bridge.DeviceShell
import com.theohowie.heracles.bridge.DeviceShellManager
import com.theohowie.heracles.bridge.KeystoreVault
import com.theohowie.heracles.bridge.McpConfigBridge
import com.theohowie.heracles.bridge.MemoryStore
import com.theohowie.heracles.bridge.SandboxManager
import com.theohowie.heracles.bridge.SkillsBridge
import com.theohowie.heracles.bridge.WorkspaceRegistry
import com.theohowie.heracles.data.DocumentReader
import com.theohowie.heracles.data.GitHubAuthService
import com.theohowie.heracles.data.GitService
import com.theohowie.heracles.data.JevRepository
import com.theohowie.heracles.data.LlmClient
import com.theohowie.heracles.data.ScopedMemoryService
import com.theohowie.heracles.data.SearchConfigRepository
import com.theohowie.heracles.data.SessionStore
import com.theohowie.heracles.data.SettingsRepository
import com.theohowie.heracles.data.SkillsRepository
import com.theohowie.heracles.data.VoiceModelRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    init {
        DeviceShellManager.appContext = appContext
    }

    val vault = KeystoreVault(appContext)
    val workspaceRegistry = WorkspaceRegistry(appContext)
    val deviceFs = DeviceFs(appContext, workspaceRegistry)
    val memoryStore = MemoryStore(appContext)
    val sandboxManager = SandboxManager(appContext)
    val deviceHealth = DeviceHealth(appContext)
    val deviceShell = DeviceShell()

    val settingsRepo = SettingsRepository(appContext, vault)
    val sessionStore = SessionStore(deviceFs)
    val scopedMemory = ScopedMemoryService(memoryStore, deviceFs)
    val documentReader = DocumentReader(deviceFs)
    val searchRepo = SearchConfigRepository(appContext)
    val llmClient = LlmClient()
    val gitService = GitService()
    val githubAuth = GitHubAuthService(vault)
    val skillsBridge = SkillsBridge(appContext)
    val skillsRepo = SkillsRepository(skillsBridge)
    val mcpBridge = McpConfigBridge(appContext)
    val jevRepo = JevRepository(appContext, vault)
    val voiceRepo = VoiceModelRepository(appContext)

    val toolRegistry = ToolRegistry(
        context = appContext,
        fs = deviceFs,
        sandbox = sandboxManager,
        deviceShell = deviceShell,
        deviceHealth = deviceHealth,
        documentReader = documentReader,
        gitService = gitService,
        searchRepo = searchRepo,
        globalMemory = memoryStore,
        scopedMemory = scopedMemory,
    )

    val agent = HeraclesAgent(
        context = appContext,
        llmClient = llmClient,
        tools = toolRegistry,
        settingsRepo = settingsRepo,
        sessionStore = sessionStore,
        scopedMemory = scopedMemory,
        skillsRepo = skillsRepo,
    )

    val automationRunner = AutomationRunner(
        context = appContext,
        settingsRepo = settingsRepo,
        llmClient = llmClient,
        tools = toolRegistry,
    )

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
