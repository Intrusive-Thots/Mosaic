package com.intrusivethots.mosaic.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.intrusivethots.mosaic.ui.components.LibraryHost
import com.intrusivethots.mosaic.ui.components.LibrarySources
import com.intrusivethots.mosaic.ui.components.ProjectLibraryScreen
import com.intrusivethots.mosaic.ui.components.SettingsDialog
import com.intrusivethots.mosaic.ui.components.ShapeEditor
import com.intrusivethots.mosaic.ui.components.StampsScreen
import com.intrusivethots.mosaic.ui.components.StudioScreen
import com.intrusivethots.mosaic.ui.inspect.ResultInspector
import com.intrusivethots.mosaic.ui.state.CollageEdit
import com.intrusivethots.mosaic.ui.state.GenerationUiState
import com.intrusivethots.mosaic.ui.theme.AccentAmber
import com.intrusivethots.mosaic.ui.theme.AccentPink
import com.intrusivethots.mosaic.ui.theme.AccentPurple
import com.intrusivethots.mosaic.ui.theme.DeepBackground
import com.intrusivethots.mosaic.ui.theme.SurfaceDark
import com.intrusivethots.mosaic.ui.theme.SurfaceVariantDark
import com.intrusivethots.mosaic.ui.theme.TextPrimary
import com.intrusivethots.mosaic.ui.theme.TextSecondary
import java.io.File

@Composable
private fun MosaicBottomBar(tab: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = SurfaceDark, tonalElevation = 8.dp) {
        NavigationBarItem(
            selected = tab == 0,
            onClick = { onSelect(0) },
            icon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Studio") },
            label = { Text("Studio") },
            colors = navColors(AccentPurple)
        )
        NavigationBarItem(
            selected = tab == 1,
            onClick = { onSelect(1) },
            icon = { Icon(Icons.Default.ContentCut, contentDescription = "Stamps") },
            label = { Text("Stamps") },
            colors = navColors(AccentAmber)
        )
        NavigationBarItem(
            selected = tab == 2,
            onClick = { onSelect(2) },
            icon = { Icon(Icons.Default.Collections, contentDescription = "Library") },
            label = { Text("Library") },
            colors = navColors(AccentPink)
        )
        NavigationBarItem(
            selected = tab == 3,
            onClick = { onSelect(3) },
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            colors = navColors(AccentPurple)
        )
    }
}

@Composable
private fun navColors(selected: Color) = NavigationBarItemDefaults.colors(
    selectedIconColor = selected,
    selectedTextColor = selected,
    unselectedIconColor = TextSecondary,
    unselectedTextColor = TextSecondary,
    indicatorColor = SurfaceVariantDark
)

@Composable
fun MainScreen(viewModel: MainViewModel) {
    LibraryHost(viewModel) { sources -> HomeScreen(viewModel, sources) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(viewModel: MainViewModel, sources: LibrarySources) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is com.intrusivethots.mosaic.ui.state.UiEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let(viewModel::setTargetImage)
    }
    val targetPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.setTargetImage(uri)
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = DeepBackground,
        topBar = {
            if (!state.inspectOpen) TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(AccentPurple, AccentPink))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Mosaic", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepBackground)
            )
        },
        bottomBar = {
            if (!state.inspectOpen) MosaicBottomBar(tab, onSelect = { selected ->
                tab = selected
                if (selected == 2) viewModel.refreshProjects()
            })
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> StudioScreen(
                    state = state,
                    onGallery = { targetPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onCamera = {
                        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                        val file = File(dir, "target_${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        cameraUri = uri
                        camera.launch(uri)
                    },
                    onPickTiles = sources.pickTiles,
                    onPreset = viewModel::applyQualityPreset,
                    onAspect = viewModel::updateAspectRatio,
                    onStyle = viewModel::updateMosaicStyle,
                    onColumns = viewModel::updateGridColumns,
                    onRows = viewModel::updateGridRows,
                    onLink = viewModel::toggleLinkAspect,
                    onRepetition = viewModel::updateRepetition,
                    onBlend = viewModel::updateColorBlend,
                    onRenderMode = viewModel::updateRenderMode,
                    onOutputMode = viewModel::updateOutputMode,
                    onFit = viewModel::updateTileFit,
                    onAi = viewModel::toggleAiSegmentation,
                    onSegmentation = { max, min, shapes ->
                        viewModel.updateSegmentation(state.config.segmentation.copy(maxExtractedSubjects = max, minSubjectSizePx = min, allowedShapes = shapes))
                    },
                    onCrop = viewModel::applyCropToTarget,
                    onResetCrop = viewModel::resetTargetCrop,
                    shapeEditor = shapeEditor(viewModel),
                    onPreview = viewModel::generatePreview,
                    onRender = viewModel::generateFullMosaic,
                    onCancel = viewModel::cancelGeneration,
                    onExport = viewModel::exportToGallery
                )
                1 -> StampsScreen(
                    stamps = state.customStamps,
                    extracting = state.libraryMessage.isNotEmpty(),
                    progress = state.libraryMessage,
                    onPick = sources.pickStamps,
                    onRemove = viewModel::removeCustomStamp,
                    onClear = viewModel::clearStamps,
                    onTighten = viewModel::tightenStamp,
                    onCancel = viewModel::cancelExtract,
                    onReplace = viewModel::replaceStamp
                )
                2 -> ProjectLibraryScreen(state.projects, viewModel::deleteProject, viewModel::exportProjectToGallery)
                else -> SettingsDialog(state.apiKey, state.keystoreAvailable, viewModel::saveApiKey)
            }
            if (state.inspectOpen) InspectorOverlay(state, viewModel)
        }
    }
}

@Composable
private fun InspectorOverlay(state: com.intrusivethots.mosaic.ui.state.MosaicUiState, viewModel: MainViewModel) {
    val path = state.inspectPath
    if (path == null) {
        Box(Modifier.fillMaxSize().background(DeepBackground), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AccentAmber)
        }
        return
    }
    val running = state.generation as? GenerationUiState.Running
    ResultInspector(
        imagePath = path,
        imageToken = state.inspectToken,
        busy = running != null,
        progress = running?.fraction ?: 0f,
        progressLabel = running?.label ?: "",
        seed = state.config.randomSeed,
        colorStrength = state.config.colorMatchWeight,
        canUndo = state.canUndoEdit,
        canRedo = state.canRedoEdit,
        onClose = viewModel::closeInspector,
        onRegenerate = viewModel::regenerateRegion,
        onCancel = viewModel::cancelGeneration,
        onUndo = { viewModel.onCollageEdit(CollageEdit.Undo) },
        onRedo = { viewModel.onCollageEdit(CollageEdit.Redo) },
        sourceAt = viewModel::sourceLabel
    )
}

private fun shapeEditor(viewModel: MainViewModel) = ShapeEditor(
    onClearTiles = viewModel::clearTiles,
    onRotateTarget = viewModel::rotateTarget,
    onRotateTile = viewModel::rotateTile,
    onCellAspect = viewModel::updateCellAspect,
    onLayout = viewModel::updateLayoutMode,
    onRotation = viewModel::updateRotationMode,
    onScale = viewModel::updateTargetScale,
    onOutput = viewModel::updateCustomOutput,
    onKind = viewModel::updateMosaicKind,
    onCollage = viewModel::updateCollage,
    onRemoveStamp = viewModel::removeCustomStamp,
    onCollageStyle = viewModel::applyCollageStyle,
    onStack = viewModel::applyStack,
    onEdit = viewModel::onCollageEdit,
    onInspect = viewModel::openInspector
)
