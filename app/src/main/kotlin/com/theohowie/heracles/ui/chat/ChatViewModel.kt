package com.theohowie.heracles.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.theohowie.heracles.AppContainer
import com.theohowie.heracles.agent.HeraclesAgent
import com.theohowie.heracles.bridge.AgentChatService
import com.theohowie.heracles.bridge.SafPathHelper
import com.theohowie.heracles.bridge.WorkspaceRegistry
import com.theohowie.heracles.data.GitHubAuthService
import com.theohowie.heracles.data.GitService
import com.theohowie.heracles.i18n.stringsFor
import com.theohowie.heracles.model.AppLanguage
import com.theohowie.heracles.model.AppSettings
import com.theohowie.heracles.model.AttachmentItem
import com.theohowie.heracles.model.AttachmentKind
import com.theohowie.heracles.model.ChatMessage
import com.theohowie.heracles.model.JevConfig
import com.theohowie.heracles.model.JevDecision
import com.theohowie.heracles.model.JevSource
import com.theohowie.heracles.model.ReasoningLevel
import com.theohowie.heracles.model.SessionMeta
import com.theohowie.heracles.model.SessionUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val c = AppContainer.get(application)

    enum class TopMode { CHAT, DECISION }

    data class UiState(
        val mode: TopMode = TopMode.CHAT,
        val workspaces: List<WorkspaceRegistry.Entry> = emptyList(),
        val activeWorkspace: WorkspaceRegistry.Entry? = null,
        val sessions: List<SessionMeta> = emptyList(),
        val activeSession: SessionMeta? = null,
        val messages: List<ChatMessage> = emptyList(),
        val hasEarlier: Boolean = false,
        val usage: SessionUsage = SessionUsage(),
        val attachments: List<AttachmentItem> = emptyList(),
        val inputText: String = "",
        val isRecordingVoice: Boolean = false,
        val voiceTranscript: String = "",
        val processKilledPrompt: Boolean = false,
        val toastMessage: String? = null,
        // Git & GitHub state
        val gitStatus: GitService.RepoStatus = GitService.RepoStatus(isRepo = false),
        val gitBranches: List<String> = emptyList(),
        val gitCommits: List<GitService.CommitSummary> = emptyList(),
        val gitDiff: String = "",
        val gitBusy: Boolean = false,
        val githubDeviceCode: GitHubAuthService.DeviceCodeInfo? = null,
        val githubRepos: List<GitHubAuthService.GitHubRepo> = emptyList(),
        // JEV Decision state
        val jevBusy: Boolean = false,
        val jevLatest: JevDecision? = null,
        val jevError: String? = null,
    )

    val settings: StateFlow<AppSettings> = c.settingsRepo.settings
    val agentState: StateFlow<HeraclesAgent.AgentRuntimeState> = c.agent.state
    val jevConfig: StateFlow<JevConfig> = c.jevRepo.config
    val jevHistory: StateFlow<List<JevDecision>> = c.jevRepo.history
    val githubUser: StateFlow<GitHubAuthService.GitHubUser?> = c.githubAuth.user

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        initializeWorkspaceAndSession()
        viewModelScope.launch {
            if (c.githubAuth.isSignedIn) c.githubAuth.refreshUser()
        }
        viewModelScope.launch {
            c.automationRunner.drainPending()
        }
    }

    private fun initializeWorkspaceAndSession() {
        viewModelScope.launch {
            val defaultWs = withContext(Dispatchers.IO) { c.workspaceRegistry.ensureAppWorkspace() }
            val allWs = withContext(Dispatchers.IO) { c.workspaceRegistry.list() }
            val lastPath = c.settingsRepo.getLastWorkspacePath()
            val chosen = allWs.firstOrNull { it.path == lastPath } ?: defaultWs ?: allWs.firstOrNull()
            val killed = AgentChatService.processKilled(getApplication())
            _ui.value = _ui.value.copy(
                workspaces = allWs,
                activeWorkspace = chosen,
                processKilledPrompt = killed,
            )
            if (chosen != null) {
                loadWorkspaceSessions(chosen)
            }
        }
    }

    fun selectWorkspace(entry: WorkspaceRegistry.Entry) {
        c.settingsRepo.setLastWorkspacePath(entry.path)
        _ui.value = _ui.value.copy(activeWorkspace = entry)
        viewModelScope.launch {
            loadWorkspaceSessions(entry)
            refreshGitStatus()
        }
    }

    fun handlePickedFolderUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            when (val res = SafPathHelper.processTreeUri(getApplication(), c.workspaceRegistry, uri)) {
                is SafPathHelper.PickOutcome.Restricted -> {
                    _ui.value = _ui.value.copy(toastMessage = res.reason)
                }
                is SafPathHelper.PickOutcome.NoLocalPath -> {
                    _ui.value = _ui.value.copy(toastMessage = res.reason)
                }
                is SafPathHelper.PickOutcome.Ready -> {
                    val all = c.workspaceRegistry.list()
                    val lang = settings.value.language
                    val warn = if (!res.posixWritable) {
                        when (lang) {
                            AppLanguage.AR -> " (كتابة عبر قناة SAF فقط)"
                            AppLanguage.EN -> " (SAF-only write)"
                            AppLanguage.ZH -> "（仅 SAF 通道可写）"
                        }
                    } else ""
                    val prefix = when (lang) {
                        AppLanguage.AR -> "تمت إضافة مساحة العمل: "
                        AppLanguage.EN -> "Added workspace: "
                        AppLanguage.ZH -> "已添加工作区："
                    }
                    _ui.value = _ui.value.copy(
                        workspaces = all,
                        toastMessage = "$prefix${res.entry.name}$warn",
                    )
                    selectWorkspace(res.entry)
                }
            }
        }
    }

    fun removeWorkspace(entry: WorkspaceRegistry.Entry) {
        viewModelScope.launch(Dispatchers.IO) {
            c.workspaceRegistry.remove(entry.path)
            val ensured = c.workspaceRegistry.ensureAppWorkspace()
            val all = c.workspaceRegistry.list()
            val next = if (_ui.value.activeWorkspace?.path == entry.path) (ensured ?: all.firstOrNull()) else _ui.value.activeWorkspace
            _ui.value = _ui.value.copy(workspaces = all, activeWorkspace = next)
            if (next != null) loadWorkspaceSessions(next)
        }
    }

    private suspend fun loadWorkspaceSessions(ws: WorkspaceRegistry.Entry) {
        val list = c.sessionStore.listSessions(ws.path, includeArchived = false)
        val lastId = c.settingsRepo.getLastSessionId(ws.path)
        val target = list.firstOrNull { it.id == lastId }
            ?: list.firstOrNull()
            ?: c.sessionStore.createSession(ws.path)
        val refreshedList = if (list.any { it.id == target.id }) list else listOf(target) + list
        _ui.value = _ui.value.copy(sessions = refreshedList)
        openSession(target)
        refreshGitStatus()
    }

    fun createNewSession() {
        val ws = _ui.value.activeWorkspace ?: return
        viewModelScope.launch {
            val s = c.sessionStore.createSession(ws.path)
            val updated = listOf(s) + _ui.value.sessions
            _ui.value = _ui.value.copy(sessions = updated)
            openSession(s)
        }
    }

    fun openSession(meta: SessionMeta) {
        val ws = _ui.value.activeWorkspace ?: return
        c.settingsRepo.setLastSessionId(ws.path, meta.id)
        viewModelScope.launch {
            val loaded = c.sessionStore.loadTail(ws.path, meta)
            c.agent.updatePlanSteps(loaded.plan)
            _ui.value = _ui.value.copy(
                activeSession = loaded.meta,
                messages = loaded.messages,
                hasEarlier = loaded.hasEarlier,
                usage = loaded.usage,
            )
        }
    }

    fun loadEarlierMessages() {
        val ws = _ui.value.activeWorkspace ?: return
        val session = _ui.value.activeSession ?: return
        viewModelScope.launch {
            val full = c.sessionStore.loadFull(ws.path, session)
            _ui.value = _ui.value.copy(
                messages = full.messages,
                hasEarlier = full.hasEarlier,
            )
        }
    }

    fun renameSession(meta: SessionMeta, title: String) {
        val ws = _ui.value.activeWorkspace ?: return
        viewModelScope.launch {
            c.sessionStore.renameSession(ws.path, meta.id, title)
            val list = c.sessionStore.listSessions(ws.path)
            val active = if (_ui.value.activeSession?.id == meta.id) {
                list.firstOrNull { it.id == meta.id } ?: _ui.value.activeSession
            } else {
                _ui.value.activeSession
            }
            _ui.value = _ui.value.copy(sessions = list, activeSession = active)
        }
    }

    fun archiveSession(meta: SessionMeta) {
        val ws = _ui.value.activeWorkspace ?: return
        viewModelScope.launch {
            c.sessionStore.setArchived(ws.path, meta.id, archived = true)
            loadWorkspaceSessions(ws)
        }
    }

    fun deleteSession(meta: SessionMeta) {
        val ws = _ui.value.activeWorkspace ?: return
        viewModelScope.launch {
            c.sessionStore.deleteSession(ws.path, meta.id)
            loadWorkspaceSessions(ws)
        }
    }

    fun setMode(mode: TopMode) {
        _ui.value = _ui.value.copy(mode = mode)
    }

    fun updateInputText(value: String) {
        _ui.value = _ui.value.copy(inputText = value)
    }

    fun addAttachmentUri(uri: Uri, isImage: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val mime = resolver.getType(uri).orEmpty()
            val name = resolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "file_${System.currentTimeMillis()}"
            val cacheDir = File(getApplication<Application>().cacheDir, "attachments").apply { mkdirs() }
            val dst = File(cacheDir, "${UUID.randomUUID().toString().take(6)}_$name")
            runCatching {
                resolver.openInputStream(uri)?.use { inp -> dst.outputStream().use { inp.copyTo(it) } }
            }.onSuccess {
                val item = AttachmentItem(
                    id = UUID.randomUUID().toString().take(8),
                    name = name,
                    path = dst.absolutePath,
                    kind = if (isImage || mime.startsWith("image/")) AttachmentKind.IMAGE else AttachmentKind.FILE,
                    mimeType = mime,
                    sizeBytes = dst.length(),
                )
                _ui.value = _ui.value.copy(attachments = _ui.value.attachments + item)
            }
        }
    }

    fun addCameraFile(file: File) {
        if (!file.isFile) return
        val item = AttachmentItem(
            id = UUID.randomUUID().toString().take(8),
            name = file.name,
            path = file.absolutePath,
            kind = AttachmentKind.IMAGE,
            mimeType = "image/jpeg",
            sizeBytes = file.length(),
        )
        _ui.value = _ui.value.copy(attachments = _ui.value.attachments + item)
    }

    fun removeAttachment(id: String) {
        _ui.value = _ui.value.copy(attachments = _ui.value.attachments.filter { it.id != id })
    }

    fun sendCurrentInput() {
        val ws = _ui.value.activeWorkspace ?: return
        val sess = _ui.value.activeSession ?: return
        val text = _ui.value.inputText.trim()
        val atts = _ui.value.attachments
        if (text.isEmpty() && atts.isEmpty()) return

        if (_ui.value.mode == TopMode.DECISION) {
            _ui.value = _ui.value.copy(inputText = "", jevBusy = true, jevError = null)
            viewModelScope.launch {
                val res = c.jevRepo.evaluateDecision(text)
                res.fold(
                    onSuccess = { d -> _ui.value = _ui.value.copy(jevBusy = false, jevLatest = d) },
                    onFailure = { e -> _ui.value = _ui.value.copy(jevBusy = false, jevError = e.message) },
                )
            }
            return
        }

        _ui.value = _ui.value.copy(inputText = "", attachments = emptyList())
        c.agent.submitOrSteer(
            workspacePath = ws.path,
            sessionId = sess.id,
            text = text,
            attachments = atts,
            currentMessages = _ui.value.messages,
            currentUsage = _ui.value.usage,
            onMessagePersisted = { msg ->
                val cur = _ui.value.messages.filter { it.id != msg.id } + msg
                _ui.value = _ui.value.copy(messages = cur)
                viewModelScope.launch {
                    val refreshed = c.sessionStore.listSessions(ws.path)
                    _ui.value = _ui.value.copy(sessions = refreshed)
                }
            },
            onUsageUpdated = { u -> _ui.value = _ui.value.copy(usage = u) },
        )
    }

    fun resumeAfterProcessKilled() {
        AgentChatService.consumeProcessKilled(getApplication())
        _ui.value = _ui.value.copy(processKilledPrompt = false)
        val ws = _ui.value.activeWorkspace ?: return
        val sess = _ui.value.activeSession ?: return
        c.agent.submitOrSteer(
            workspacePath = ws.path,
            sessionId = sess.id,
            text = stringsFor(settings.value.language).resumeKilledPromptMsg,
            attachments = emptyList(),
            currentMessages = _ui.value.messages,
            currentUsage = _ui.value.usage,
            onMessagePersisted = { msg ->
                _ui.value = _ui.value.copy(messages = _ui.value.messages.filter { it.id != msg.id } + msg)
            },
            onUsageUpdated = { u -> _ui.value = _ui.value.copy(usage = u) },
        )
    }

    fun dismissProcessKilledBanner() {
        AgentChatService.consumeProcessKilled(getApplication())
        _ui.value = _ui.value.copy(processKilledPrompt = false)
    }

    fun stopAgent() = c.agent.stopRun()
    fun resolveApproval(d: HeraclesAgent.ApprovalDecision) = c.agent.resolveApproval(d)
    fun resolveAsk(answer: String) = c.agent.resolveAsk(answer)
    fun clearStopReason() = c.agent.clearStopReason()

    fun toggleVoiceCapture() {
        if (_ui.value.isRecordingVoice) {
            c.voiceRepo.stopSpeechRecognition()
            val stopRes = c.voiceRepo.recorder.stop()
            val transcript = _ui.value.voiceTranscript.trim()
            val merged = if (transcript.isNotBlank()) {
                (_ui.value.inputText + " " + transcript).trim()
            } else {
                _ui.value.inputText
            }
            val nextAtts = if (!stopRes.ok && _ui.value.attachments.isNotEmpty()) {
                _ui.value.attachments
            } else if (transcript.isBlank() && stopRes.wavPath != null) {
                _ui.value.attachments + AttachmentItem(
                    id = UUID.randomUUID().toString().take(8),
                    name = stopRes.fileName ?: "voice.wav",
                    path = stopRes.wavPath,
                    kind = AttachmentKind.FILE,
                    mimeType = "audio/wav",
                    sizeBytes = stopRes.size,
                )
            } else {
                _ui.value.attachments
            }
            val lang = settings.value.language
            val savedAudioMsg = when (lang) {
                AppLanguage.AR -> "تم حفظ التسجيل الصوتي (لم يتم رصد تفريغ نصي من النظام)"
                AppLanguage.EN -> "Saved voice recording (no live system transcript detected)"
                AppLanguage.ZH -> "已保存录音片段，未检测到系统语音转写文本"
            }
            _ui.value = _ui.value.copy(
                isRecordingVoice = false,
                voiceTranscript = "",
                inputText = merged,
                attachments = nextAtts,
                toastMessage = if (transcript.isBlank() && stopRes.wavPath != null) savedAudioMsg else null,
            )
            return
        }

        val startRes = c.voiceRepo.recorder.start()
        if (!startRes.ok) {
            val errFallback = when (settings.value.language) {
                AppLanguage.AR -> "تعذر بدء التسجيل الصوتي"
                AppLanguage.EN -> "Unable to start recording"
                AppLanguage.ZH -> "无法启动录音"
            }
            _ui.value = _ui.value.copy(toastMessage = startRes.error ?: errFallback)
            return
        }
        _ui.value = _ui.value.copy(isRecordingVoice = true, voiceTranscript = "")
        val loc = when (settings.value.language) {
            AppLanguage.AR -> Locale("ar")
            AppLanguage.EN -> Locale.US
            AppLanguage.ZH -> Locale.SIMPLIFIED_CHINESE
        }
        c.voiceRepo.startSpeechRecognition(
            locale = loc,
            onPartial = { txt -> _ui.value = _ui.value.copy(voiceTranscript = txt) },
            onFinal = { txt -> _ui.value = _ui.value.copy(voiceTranscript = txt) },
            onError = { /* Keep raw AudioRecord WAV even if OEM SpeechRecognizer is unavailable */ },
        )
    }

    fun selectProviderAndModel(providerId: String, model: String) {
        c.settingsRepo.updateSettings { s ->
            val updatedProviders = s.providers.map { p ->
                if (p.id == providerId) p.copy(model = model) else p
            }
            s.copy(activeProviderId = providerId, providers = updatedProviders)
        }
    }

    fun updateReasoningLevel(level: ReasoningLevel) {
        c.settingsRepo.updateSettings { it.copy(reasoningLevel = level) }
    }

    fun selectJevSource(source: JevSource) {
        c.jevRepo.updateConfig { it.copy(source = source) }
    }

    fun hasActiveProviderApiKey(): Boolean {
        val active = settings.value.activeProvider ?: return false
        return c.settingsRepo.hasProviderApiKey(active.id)
    }

    fun clearToast() {
        _ui.value = _ui.value.copy(toastMessage = null)
    }

    // ---- Git & GitHub ----
    fun refreshGitStatus() {
        val ws = _ui.value.activeWorkspace ?: return
        viewModelScope.launch {
            val st = c.gitService.status(ws.path)
            val branches = if (st.isRepo) c.gitService.branches(ws.path) else emptyList()
            val commits = if (st.isRepo) c.gitService.log(ws.path, 15) else emptyList()
            val diff = if (st.isRepo) c.gitService.diff(ws.path).take(8000) else ""
            _ui.value = _ui.value.copy(
                gitStatus = st,
                gitBranches = branches,
                gitCommits = commits,
                gitDiff = diff,
            )
        }
    }

    fun gitInit() {
        val ws = _ui.value.activeWorkspace ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(gitBusy = true)
            val r = c.gitService.initRepo(ws.path)
            val lang = settings.value.language
            val okMsg = when (lang) {
                AppLanguage.AR -> "تمت تهيئة مستودع Git"
                AppLanguage.EN -> "Git repository initialized"
                AppLanguage.ZH -> "已初始化 Git 仓库"
            }
            _ui.value = _ui.value.copy(
                gitBusy = false,
                toastMessage = if (r.isSuccess) okMsg else r.exceptionOrNull()?.message,
            )
            refreshGitStatus()
        }
    }

    fun gitCommitAll(message: String) {
        val ws = _ui.value.activeWorkspace ?: return
        if (message.isBlank()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(gitBusy = true)
            val s = c.gitService.stageAll(ws.path)
            val res = if (s.isSuccess) c.gitService.commit(ws.path, message.trim()) else s.map { "" }
            val lang = settings.value.language
            _ui.value = _ui.value.copy(
                gitBusy = false,
                toastMessage = res.fold(
                    {
                        when (lang) {
                            AppLanguage.AR -> "تم الحفظ: $it"
                            AppLanguage.EN -> "Committed $it"
                            AppLanguage.ZH -> "已提交 $it"
                        }
                    },
                    {
                        it.message ?: when (lang) {
                            AppLanguage.AR -> "فشل الحفظ (Commit)"
                            AppLanguage.EN -> "Commit failed"
                            AppLanguage.ZH -> "提交失败"
                        }
                    },
                ),
            )
            refreshGitStatus()
        }
    }

    fun gitPush() {
        val ws = _ui.value.activeWorkspace ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(gitBusy = true)
            val res = c.gitService.push(ws.path, c.githubAuth.getToken().ifBlank { null })
            val failMsg = when (settings.value.language) {
                AppLanguage.AR -> "فشل الرفع (Push)"
                AppLanguage.EN -> "Push failed"
                AppLanguage.ZH -> "推送失败"
            }
            _ui.value = _ui.value.copy(
                gitBusy = false,
                toastMessage = res.fold({ it }, { it.message ?: failMsg }),
            )
            refreshGitStatus()
        }
    }

    fun gitPull() {
        val ws = _ui.value.activeWorkspace ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(gitBusy = true)
            val res = c.gitService.pull(ws.path, c.githubAuth.getToken().ifBlank { null })
            val failMsg = when (settings.value.language) {
                AppLanguage.AR -> "فشل السحب (Pull)"
                AppLanguage.EN -> "Pull failed"
                AppLanguage.ZH -> "拉取失败"
            }
            _ui.value = _ui.value.copy(
                gitBusy = false,
                toastMessage = res.fold({ it }, { it.message ?: failMsg }),
            )
            refreshGitStatus()
        }
    }

    fun gitCheckout(branch: String, create: Boolean = false) {
        val ws = _ui.value.activeWorkspace ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(gitBusy = true)
            val res = c.gitService.checkout(ws.path, branch, create)
            val lang = settings.value.language
            _ui.value = _ui.value.copy(
                gitBusy = false,
                toastMessage = res.fold(
                    {
                        when (lang) {
                            AppLanguage.AR -> "تم التبديل إلى الفرع $branch"
                            AppLanguage.EN -> "Checked out $branch"
                            AppLanguage.ZH -> "已切换分支 $branch"
                        }
                    },
                    {
                        it.message ?: when (lang) {
                            AppLanguage.AR -> "فشل التبديل"
                            AppLanguage.EN -> "Checkout failed"
                            AppLanguage.ZH -> "切换失败"
                        }
                    },
                ),
            )
            refreshGitStatus()
        }
    }

    fun startGitHubDeviceFlow() {
        viewModelScope.launch {
            val codeRes = c.githubAuth.requestDeviceCode()
            val lang = settings.value.language
            codeRes.fold(
                onSuccess = { info ->
                    _ui.value = _ui.value.copy(githubDeviceCode = info)
                    val tokenRes = c.githubAuth.pollForAccessToken(info)
                    _ui.value = _ui.value.copy(
                        githubDeviceCode = null,
                        toastMessage = tokenRes.fold(
                            {
                                when (lang) {
                                    AppLanguage.AR -> "تم تسجيل الدخول في GitHub: ${it.login}"
                                    AppLanguage.EN -> "Signed in to GitHub: ${it.login}"
                                    AppLanguage.ZH -> "已登录 GitHub：${it.login}"
                                }
                            },
                            {
                                it.message ?: when (lang) {
                                    AppLanguage.AR -> "فشل تسجيل الدخول في GitHub"
                                    AppLanguage.EN -> "GitHub login failed"
                                    AppLanguage.ZH -> "GitHub 登录失败"
                                }
                            },
                        ),
                    )
                    if (tokenRes.isSuccess) loadGitHubRepos()
                },
                onFailure = { e ->
                    val errMsg = when (lang) {
                        AppLanguage.AR -> "فشل طلب رمز التفويض"
                        AppLanguage.EN -> "Failed to request device code"
                        AppLanguage.ZH -> "请求授权码失败"
                    }
                    _ui.value = _ui.value.copy(toastMessage = e.message ?: errMsg)
                },
            )
        }
    }

    fun loadGitHubRepos() {
        viewModelScope.launch {
            val res = c.githubAuth.listRepos()
            res.fold(
                onSuccess = { _ui.value = _ui.value.copy(githubRepos = it) },
                onFailure = { _ui.value = _ui.value.copy(toastMessage = it.message) },
            )
        }
    }

    fun cloneGitHubRepo(repo: GitHubAuthService.GitHubRepo) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(gitBusy = true)
            val base = getApplication<Application>().getExternalFilesDir(null)
                ?: getApplication<Application>().filesDir
            val folderName = repo.fullName.substringAfterLast('/')
            val target = File(base, "repos/$folderName")
            val r = c.gitService.cloneRepo(repo.cloneUrl, target.absolutePath, c.githubAuth.getToken())
            _ui.value = _ui.value.copy(gitBusy = false)
            val lang = settings.value.language
            if (r.isSuccess) {
                val added = c.workspaceRegistry.add(target.absolutePath, folderName)
                val all = c.workspaceRegistry.list()
                val doneMsg = when (lang) {
                    AppLanguage.AR -> "اكتمل الاستنساخ: $folderName"
                    AppLanguage.EN -> "Cloned $folderName"
                    AppLanguage.ZH -> "克隆完成：$folderName"
                }
                _ui.value = _ui.value.copy(workspaces = all, toastMessage = doneMsg)
                if (added != null) selectWorkspace(added)
            } else {
                val failMsg = when (lang) {
                    AppLanguage.AR -> "فشل الاستنساخ"
                    AppLanguage.EN -> "Clone failed"
                    AppLanguage.ZH -> "克隆失败"
                }
                _ui.value = _ui.value.copy(toastMessage = r.exceptionOrNull()?.message ?: failMsg)
            }
        }
    }

    fun signOutGitHub() {
        c.githubAuth.signOut()
        val msg = when (settings.value.language) {
            AppLanguage.AR -> "تم تسجيل الخروج من GitHub"
            AppLanguage.EN -> "Signed out of GitHub"
            AppLanguage.ZH -> "已退出 GitHub"
        }
        _ui.value = _ui.value.copy(githubRepos = emptyList(), toastMessage = msg)
    }

    override fun onCleared() {
        c.voiceRepo.releaseSpeechRecognizer()
        super.onCleared()
    }
}
