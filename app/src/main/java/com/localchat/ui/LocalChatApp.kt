package com.localchat.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.localchat.domain.model.ModelState
import com.localchat.ui.chat.AttachmentSheet
import com.localchat.ui.chat.ChatScreen
import com.localchat.ui.chat.ChatViewModel
import com.localchat.ui.chat.cameraImageAttachment
import com.localchat.ui.chat.chatCapabilitiesFor
import com.localchat.ui.chat.documentAttachmentFromUri
import com.localchat.ui.chat.imageAttachmentFromUri
import com.localchat.ui.history.HistoryDrawer
import com.localchat.ui.models.AddModelScreen
import com.localchat.ui.models.ConfigDialog
import com.localchat.ui.models.ModelManagerScreen
import com.localchat.ui.models.ModelManagerViewModel
import com.localchat.ui.models.ModelPickerSheet
import com.localchat.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private object Routes {
    const val Chat = "chat"
    const val Models = "models"
    const val ModelsAdd = "models/add"
    const val Settings = "settings"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalChatApp() {
    val nav = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentRoute by nav.currentBackStackEntryAsState()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // При открытии drawer убираем клавиатуру и снимаем фокус с поля ввода —
    // иначе IME перекрывает список чатов и мешает свайпу.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            keyboard?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    val chatVm: ChatViewModel = hiltViewModel()
    val modelsVm: ModelManagerViewModel = hiltViewModel()

    val chats by chatVm.chats.collectAsState()
    val activeChatId by chatVm.activeChatIdFlow.collectAsState()
    val composer by chatVm.composer.collectAsState()
    val modelStates by modelsVm.states.collectAsState()
    val modelToast by modelsVm.toast.collectAsState()
    val loadedModel = (modelStates.firstOrNull { it is ModelState.Loaded } as? ModelState.Loaded)?.info
    val visionSupported by chatVm.visionSupported.collectAsState()
    val capabilities = chatCapabilitiesFor(loadedModel, visionOverride = visionSupported)

    var showModelPicker by remember { mutableStateOf(false) }
    var showAttachments by remember { mutableStateOf(false) }
    var plusBounds by remember { mutableStateOf<Rect?>(null) }
    var configTargetId by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { chatVm.addAttachment(imageAttachmentFromUri(context, it)) }
    }
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            chatVm.addAttachment(documentAttachmentFromUri(context, it))
        }
    }
    val cameraPreview = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let { chatVm.addAttachment(cameraImageAttachment(context, it)) }
    }
    val voiceInput = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val recognized = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!recognized.isNullOrBlank()) chatVm.appendRecognizedText(recognized)
        }
    }
    fun launchVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говори")
        }
        try {
            voiceInput.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "На устройстве нет офлайн-распознавания речи", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(modelToast) {
        modelToast?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            modelsVm.consumeToast()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Свайп всегда включён когда drawer открыт (чтобы закрыть). Когда закрыт —
        // только на главном чате чтобы не конфликтовать с back-навигацией.
        gesturesEnabled = drawerState.isOpen ||
            currentRoute?.destination?.route == Routes.Chat,
        drawerContent = {
            HistoryDrawer(
                chats = chats,
                activeChatId = activeChatId,
                onChatClick = {
                    chatVm.openChat(it)
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    chatVm.openChat(null)
                    scope.launch { drawerState.close() }
                },
                onRenameChat = { id, title -> chatVm.renameChat(id, title) },
                onDeleteChat = { chatVm.deleteChat(it) },
                onOpenModels = {
                    scope.launch { drawerState.close() }
                    nav.navigate(Routes.Models)
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    nav.navigate(Routes.Settings)
                },
            )
        },
    ) {
        NavHost(navController = nav, startDestination = Routes.Chat, modifier = Modifier.fillMaxSize()) {
            composable(Routes.Chat) {
                ChatScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenModelPicker = { showModelPicker = true },
                    onOpenAttachments = { showAttachments = !showAttachments },
                    attachmentMenuOpen = showAttachments,
                    onPlusBoundsChange = { plusBounds = it },
                    onOpenModelConfig = { configTargetId = it },
                    vm = chatVm,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable(Routes.Models) {
                ModelManagerScreen(
                    onBack = { nav.popBackStack() },
                    onAdd = { nav.navigate(Routes.ModelsAdd) },
                    vm = modelsVm,
                )
            }
            composable(Routes.ModelsAdd) {
                AddModelScreen(onBack = { nav.popBackStack() }, vm = modelsVm)
            }
            composable(Routes.Settings) {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            states = modelStates,
            onLoad = {
                modelsVm.load(it)
                showModelPicker = false
            },
            onUnload = {
                modelsVm.unload()
                showModelPicker = false
            },
            onDownload = {
                modelsVm.startDownload(it)
            },
            onCancelDownload = {
                modelsVm.cancelDownload(it)
            },
            onManage = {
                showModelPicker = false
                nav.navigate(Routes.Models)
            },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showAttachments) {
        AttachmentSheet(
            anchorBounds = plusBounds,
            webSearch = composer.webSearch,
            thinking = composer.thinking,
            onCamera = {
                showAttachments = false
                cameraPreview.launch(null)
            },
            onGallery = {
                showAttachments = false
                imagePicker.launch("image/*")
            },
            onDocument = {
                showAttachments = false
                documentPicker.launch(
                    arrayOf(
                        "text/*",
                        "application/json",
                        "application/pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/rtf",
                        "text/html",
                        "application/octet-stream",
                    )
                )
            },
            onVoice = {
                showAttachments = false
                launchVoiceInput()
            },
            onToggleWebSearch = {
                chatVm.toggleWebSearch()
            },
            onToggleThinking = {
                chatVm.toggleThinking()
            },
            capabilities = capabilities,
            onDismiss = { showAttachments = false },
        )
    }

    configTargetId?.let { id ->
        val info = modelStates.firstOrNull { it.info.id == id }?.info
        if (info != null) {
            ConfigDialog(
                info = info,
                onDismiss = { configTargetId = null },
                onConfirm = { contextSize, maxTokens, topK, topP, temperature, accelerator, enableSpeculative ->
                    // Если модель загружена — перезагрузить с новыми параметрами
                    val isLoaded = modelStates.any {
                        it.info.id == id && it is com.localchat.domain.model.ModelState.Loaded
                    }
                    modelsVm.updateConfig(
                        id, contextSize, maxTokens, topK, topP, temperature, accelerator,
                        enableSpeculative, reload = isLoaded,
                    )
                    configTargetId = null
                },
            )
        } else {
            configTargetId = null
        }
    }
}
