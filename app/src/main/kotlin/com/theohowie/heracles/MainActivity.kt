package com.theohowie.heracles

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.theohowie.heracles.ui.chat.ChatScreen
import com.theohowie.heracles.ui.chat.ChatViewModel
import com.theohowie.heracles.ui.features.AutomationScreen
import com.theohowie.heracles.ui.features.JevLocalSettingsScreen
import com.theohowie.heracles.ui.features.JevSettingsScreen
import com.theohowie.heracles.ui.features.McpScreen
import com.theohowie.heracles.ui.features.SkillsScreen
import com.theohowie.heracles.ui.settings.AboutScreen
import com.theohowie.heracles.ui.settings.AppearanceScreen
import com.theohowie.heracles.ui.settings.ArchivedSessionsScreen
import com.theohowie.heracles.ui.settings.DevicePermissionsScreen
import com.theohowie.heracles.ui.settings.MemoryScreen
import com.theohowie.heracles.ui.settings.ModelEditorScreen
import com.theohowie.heracles.ui.settings.ModelsListScreen
import com.theohowie.heracles.ui.settings.OssLicensesScreen
import com.theohowie.heracles.ui.settings.RobotAccessScreen
import com.theohowie.heracles.ui.settings.SandboxScreen
import com.theohowie.heracles.ui.settings.SearchSettingsScreen
import com.theohowie.heracles.ui.settings.SettingsOverviewScreen
import com.theohowie.heracles.ui.settings.VoiceSettingsScreen
import com.theohowie.heracles.ui.theme.HeraclesTheme
import kotlinx.serialization.Serializable
import java.io.File

@Serializable object RouteChat
@Serializable object RouteSettings
@Serializable object RouteModels
@Serializable data class RouteModelEditor(val providerId: String? = null)
@Serializable object RouteAppearance
@Serializable object RouteMemory
@Serializable object RouteDevice
@Serializable object RouteVoice
@Serializable object RouteSandbox
@Serializable object RouteSearch
@Serializable object RouteRobot
@Serializable object RouteArchived
@Serializable object RouteAbout
@Serializable object RouteOssLicenses
@Serializable object RouteSkills
@Serializable object RouteMcp
@Serializable object RouteAutomation
@Serializable object RouteJevSettings
@Serializable object RouteJevLocal

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppContainer.get(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            HeraclesAppRoot(
                onOpenUrl = { url ->
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                },
            )
        }
    }

    @Composable
    private fun HeraclesAppRoot(onOpenUrl: (String) -> Unit) {
        val chatVm: ChatViewModel = viewModel()
        val settings by chatVm.settings.collectAsStateWithLifecycle()
        val nav = rememberNavController()

        var pendingCameraFile by remember { mutableStateOf<File?>(null) }

        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) chatVm.handlePickedFolderUri(uri)
        }

        val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) chatVm.addAttachmentUri(uri, isImage = true)
        }

        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) chatVm.addAttachmentUri(uri, isImage = false)
        }

        val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val file = pendingCameraFile
            pendingCameraFile = null
            if (ok && file != null) chatVm.addCameraFile(file)
        }

        val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera(cameraLauncher) { pendingCameraFile = it }
        }

        val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) chatVm.toggleVoiceCapture()
        }

        HeraclesTheme(
            themeStyle = settings.themeStyle,
            appearance = settings.appearance,
            language = settings.language,
        ) {
            NavHost(navController = nav, startDestination = RouteChat) {
                composable<RouteChat> {
                    ChatScreen(
                        vm = chatVm,
                        onPickWorkspaceFolder = { folderPicker.launch(null) },
                        onPickPhoto = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onTakePhoto = {
                            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                launchCamera(cameraLauncher) { pendingCameraFile = it }
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        onPickFile = { filePicker.launch(arrayOf("*/*")) },
                        onRequestVoiceAndToggle = {
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                chatVm.toggleVoiceCapture()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onOpenUrl = onOpenUrl,
                        onOpenArchived = { nav.navigate(RouteArchived) },
                        onOpenSkills = { nav.navigate(RouteSkills) },
                        onOpenMcp = { nav.navigate(RouteMcp) },
                        onOpenAutomation = { nav.navigate(RouteAutomation) },
                        onOpenSettings = { nav.navigate(RouteSettings) },
                        onOpenModels = { nav.navigate(RouteModels) },
                        onOpenJevSettings = { nav.navigate(RouteJevSettings) },
                    )
                }

                composable<RouteSettings> {
                    BackHandler { nav.popBackStack() }
                    SettingsOverviewScreen(
                        onBack = { nav.popBackStack() },
                        onOpenModels = { nav.navigate(RouteModels) },
                        onOpenAppearance = { nav.navigate(RouteAppearance) },
                        onOpenMemory = { nav.navigate(RouteMemory) },
                        onOpenDevice = { nav.navigate(RouteDevice) },
                        onOpenVoice = { nav.navigate(RouteVoice) },
                        onOpenSandbox = { nav.navigate(RouteSandbox) },
                        onOpenSearch = { nav.navigate(RouteSearch) },
                        onOpenRobot = { nav.navigate(RouteRobot) },
                        onOpenJev = { nav.navigate(RouteJevSettings) },
                        onOpenArchived = { nav.navigate(RouteArchived) },
                        onOpenAbout = { nav.navigate(RouteAbout) },
                    )
                }

                composable<RouteModels> {
                    BackHandler { nav.popBackStack() }
                    ModelsListScreen(
                        onBack = { nav.popBackStack() },
                        onEditProvider = { id -> nav.navigate(RouteModelEditor(id)) },
                    )
                }

                composable<RouteModelEditor> { entry ->
                    BackHandler { nav.popBackStack() }
                    val args = entry.toRoute<RouteModelEditor>()
                    ModelEditorScreen(providerId = args.providerId, onBack = { nav.popBackStack() })
                }

                composable<RouteAppearance> {
                    BackHandler { nav.popBackStack() }
                    AppearanceScreen(onBack = { nav.popBackStack() })
                }

                composable<RouteMemory> {
                    BackHandler { nav.popBackStack() }
                    MemoryScreen(onBack = { nav.popBackStack() })
                }

                composable<RouteDevice> {
                    BackHandler { nav.popBackStack() }
                    DevicePermissionsScreen(onBack = { nav.popBackStack() })
                }

                composable<RouteVoice> {
                    BackHandler { nav.popBackStack() }
                    VoiceSettingsScreen(onBack = { nav.popBackStack() })
                }

                composable<RouteSandbox> {
                    BackHandler { nav.popBackStack() }
                    SandboxScreen(onBack = { nav.popBackStack() })
                }

                composable<RouteSearch> {
                    BackHandler { nav.popBackStack() }
                    SearchSettingsScreen(onBack = { nav.popBackStack() })
                }

                composable<RouteRobot> {
                    BackHandler { nav.popBackStack() }
                    RobotAccessScreen(onBack = { nav.popBackStack() })
                }

                composable<RouteArchived> {
                    BackHandler { nav.popBackStack() }
                    ArchivedSessionsScreen(onBack = { nav.popBackStack() })
                }

                composable<RouteAbout> {
                    BackHandler { nav.popBackStack() }
                    AboutScreen(
                        onBack = { nav.popBackStack() },
                        onOpenLicenses = { nav.navigate(RouteOssLicenses) },
                    )
                }

                composable<RouteOssLicenses> {
                    BackHandler { nav.popBackStack() }
                    OssLicensesScreen(onBack = { nav.popBackStack() })
                }

                composable<RouteSkills> {
                    BackHandler { nav.popBackStack() }
                    SkillsScreen(onBack = { nav.popBackStack() })
                }

                composable<RouteMcp> {
                    BackHandler { nav.popBackStack() }
                    McpScreen(onBack = { nav.popBackStack() })
                }

                composable<RouteAutomation> {
                    BackHandler { nav.popBackStack() }
                    AutomationScreen(onBack = { nav.popBackStack() })
                }

                composable<RouteJevSettings> {
                    BackHandler { nav.popBackStack() }
                    JevSettingsScreen(
                        onBack = { nav.popBackStack() },
                        onOpenLocal = { nav.navigate(RouteJevLocal) },
                    )
                }

                composable<RouteJevLocal> {
                    BackHandler { nav.popBackStack() }
                    JevLocalSettingsScreen(onBack = { nav.popBackStack() })
                }
            }
        }
    }

    private fun launchCamera(
        launcher: androidx.activity.result.ActivityResultLauncher<Uri>,
        onCreatedFile: (File) -> Unit,
    ) {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "cam_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        onCreatedFile(file)
        launcher.launch(uri)
    }
}
