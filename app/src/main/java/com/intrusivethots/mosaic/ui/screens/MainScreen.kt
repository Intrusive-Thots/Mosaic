package com.intrusivethots.mosaic.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.intrusivethots.mosaic.core.AspectRatioPreset
import com.intrusivethots.mosaic.core.MosaicStyle
import com.intrusivethots.mosaic.data.MosaicProject
import com.intrusivethots.mosaic.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) } // 0: Studio, 1: Stamps, 2: Library, 3: Settings

    // Camera capture temp URI state
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempCameraUri != null) {
            viewModel.setTargetImage(tempCameraUri!!)
        }
    }

    // Photo picker for main target image
    val targetPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.setTargetImage(uri)
        }
    }

    // Multiple photo picker for general tiles
    val tilesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addTileImages(uris)
        }
    }

    // Photo picker specifically for pulling multiple stamps from an image
    var isExtractingStamps by remember { mutableStateOf(false) }
    val stampExtractorLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                isExtractingStamps = true
                val stamps = viewModel.extractStampsFromUri(uri)
                if (stamps.isNotEmpty()) {
                    viewModel.addCustomStamps(stamps)
                }
                isExtractingStamps = false
            }
        }
    }

    // Toast/Snackbar export feedback
    val exportStatus by viewModel.exportStatusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(exportStatus) {
        exportStatus?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearExportStatus()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(AccentPurple, AccentPink)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Mosaic",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DeepBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Studio") },
                    label = { Text("Studio") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentPurple,
                        selectedTextColor = AccentPurple,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = SurfaceVariantDark
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.ContentCut, contentDescription = "Stamps") },
                    label = { Text("Stamps") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentAmber,
                        selectedTextColor = AccentAmber,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = SurfaceVariantDark
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                        viewModel.loadProjects()
                    },
                    icon = { Icon(Icons.Default.Collections, contentDescription = "Library") },
                    label = { Text("Library") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentPink,
                        selectedTextColor = AccentPink,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = SurfaceVariantDark
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentPurple,
                        selectedTextColor = AccentPurple,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = SurfaceVariantDark
                    )
                )
            }
        },
        containerColor = DeepBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> {
                    StudioTab(
                        viewModel = viewModel,
                        onPickTargetFromGallery = {
                            targetPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onTakeTargetWithCamera = {
                            val cacheDir = File(context.cacheDir, "camera").apply { mkdirs() }
                            val file = File(cacheDir, "target_${System.currentTimeMillis()}.jpg")
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.files",
                                file
                            )
                            tempCameraUri = uri
                            cameraLauncher.launch(uri)
                        },
                        onPickTiles = {
                            tilesPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }
                1 -> {
                    StampsTab(
                        viewModel = viewModel,
                        isExtracting = isExtractingStamps,
                        onPickImageToExtractStamps = {
                            stampExtractorLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }
                2 -> {
                    LibraryTab(
                        viewModel = viewModel,
                        onExportProject = { viewModel.exportProjectToGallery(it) }
                    )
                }
                3 -> {
                    SettingsTab(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun StudioTab(
    viewModel: MainViewModel,
    onPickTargetFromGallery: () -> Unit,
    onTakeTargetWithCamera: () -> Unit,
    onPickTiles: () -> Unit
) {
    val scrollState = rememberScrollState()
    val targetBitmap by viewModel.targetBitmap.collectAsState()
    val previewBitmap by viewModel.previewBitmap.collectAsState()
    val fullBitmap by viewModel.fullBitmap.collectAsState()
    val tileUris by viewModel.tileUris.collectAsState()
    val customStamps by viewModel.customTileBitmaps.collectAsState()
    val genState by viewModel.generationState.collectAsState()
    val config by viewModel.config.collectAsState()

    var projectTitle by remember { mutableStateOf("") }
    var showFullDialog by remember { mutableStateOf(false) }
    var showCropDialog by remember { mutableStateOf(false) }

    val displayBmp = fullBitmap ?: previewBitmap

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Step 1: Base Target Image Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "1. Main Target Image",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "The overarching image that the mosaic will depict.",
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                    }
                    if (targetBitmap != null) {
                        Row {
                            IconButton(onClick = { showCropDialog = true }) {
                                Icon(Icons.Default.Crop, contentDescription = "Crop", tint = AccentPurple)
                            }
                            IconButton(onClick = { viewModel.resetTargetCrop() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reset Crop", tint = TextSecondary)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (targetBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        Image(
                            bitmap = targetBitmap!!.asImageBitmap(),
                            contentDescription = "Target Image",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onPickTargetFromGallery,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Gallery")
                    }
                    Button(
                        onClick = onTakeTargetWithCamera,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Camera")
                    }
                }
            }
        }

        // Step 2: Tile Images & Custom Stamps Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "2. Tile Pool",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "${tileUris.size} tile photos + ${customStamps.size} AI stamps",
                            fontSize = 13.sp,
                            color = if (tileUris.isEmpty() && customStamps.isEmpty()) TextSecondary else AccentPink
                        )
                    }
                    if (tileUris.isNotEmpty() || customStamps.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearTiles() }) {
                            Text("Clear All", color = TextSecondary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onPickTiles,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPink),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Tile Images (up to 100)")
                }
            }
        }

        // Step 3: Mosaic Tuning Controls
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "3. Aspect Ratio & Shape Presets",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Aspect Ratio Horizontal Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(AspectRatioPreset.values().toList()) { preset ->
                        FilterChip(
                            selected = config.aspectRatio == preset,
                            onClick = { viewModel.updateAspectRatio(preset) },
                            label = { Text(preset.label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentPurple,
                                selectedLabelColor = Color.White,
                                containerColor = SurfaceVariantDark,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = SurfaceVariantDark)
                Spacer(modifier = Modifier.height(14.dp))

                // AI Subject Segmentation Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("AI Auto-Cutout on Tile Uploads", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPrimary)
                        }
                        Text("Extracts subjects (dogs, cats, people) from tile photos automatically", fontSize = 12.sp, color = TextSecondary)
                    }
                    Switch(
                        checked = config.extractSubjectsWithAi,
                        onCheckedChange = { viewModel.toggleAiSegmentation(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentAmber)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = SurfaceVariantDark)
                Spacer(modifier = Modifier.height(14.dp))

                // Mosaic Layout Style
                Text("Mosaic Pattern Layout", fontSize = 13.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MosaicStyle.values().forEach { style ->
                        FilterChip(
                            selected = config.mosaicStyle == style,
                            onClick = { viewModel.updateMosaicStyle(style) },
                            label = { Text(style.label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentPink,
                                selectedLabelColor = Color.White,
                                containerColor = SurfaceVariantDark,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = SurfaceVariantDark)
                Spacer(modifier = Modifier.height(14.dp))

                // Columns Slider
                Text("Columns: ${config.gridColumns}", fontSize = 13.sp, color = TextSecondary)
                Slider(
                    value = config.gridColumns.toFloat(),
                    onValueChange = { viewModel.updateGridColumns(it.toInt()) },
                    valueRange = 15f..100f,
                    steps = 16,
                    colors = SliderDefaults.colors(thumbColor = AccentPurple, activeTrackColor = AccentPurple)
                )

                // Row Linking Toggle & Row Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-link Rows to Aspect Ratio", fontSize = 13.sp, color = TextSecondary)
                    Switch(
                        checked = config.linkAspectToGrid,
                        onCheckedChange = { viewModel.toggleLinkAspect(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentPurple)
                    )
                }

                if (!config.linkAspectToGrid) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Custom Rows: ${config.gridRows}", fontSize = 13.sp, color = TextSecondary)
                    Slider(
                        value = config.gridRows.toFloat(),
                        onValueChange = { viewModel.updateGridRows(it.toInt()) },
                        valueRange = 15f..100f,
                        steps = 16,
                        colors = SliderDefaults.colors(thumbColor = AccentPurple, activeTrackColor = AccentPurple)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text("Color Blending Tint: ${(config.colorMatchWeight * 100).toInt()}%", fontSize = 13.sp, color = TextSecondary)
                Slider(
                    value = config.colorMatchWeight,
                    onValueChange = { viewModel.updateColorBlend(it) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(thumbColor = AccentPink, activeTrackColor = AccentPink)
                )
            }
        }

        // Progress indicator
        when (genState) {
            is GenerationState.SegmentingSubjects -> {
                val state = genState as GenerationState.SegmentingSubjects
                val p = state.current.toFloat() / state.total.toFloat()
                ProgressBanner("AI Segmenting image ${state.current} of ${state.total}...", p)
            }
            is GenerationState.AnalyzingTiles -> {
                val p = (genState as GenerationState.AnalyzingTiles).progress
                ProgressBanner("Analyzing tile color profiles...", p)
            }
            is GenerationState.GeneratingPreview -> {
                val p = (genState as GenerationState.GeneratingPreview).progress
                ProgressBanner("Generating rapid preview...", p)
            }
            is GenerationState.GeneratingFull -> {
                val p = (genState as GenerationState.GeneratingFull).progress
                ProgressBanner("Generating high-resolution mosaic...", p)
            }
            is GenerationState.Error -> {
                Text(
                    text = (genState as GenerationState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp
                )
            }
            else -> {}
        }

        // Step 4: Preview and Full Actions
        val hasTiles = tileUris.isNotEmpty() || customStamps.isNotEmpty()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.generatePreview() },
                enabled = targetBitmap != null && hasTiles && genState == GenerationState.Idle,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Preview")
            }

            Button(
                onClick = {
                    viewModel.generateFullMosaic(title = projectTitle)
                },
                enabled = targetBitmap != null && hasTiles && genState == GenerationState.Idle,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Render Full")
            }
        }

        // Output Display Area with Direct Download/Export Button
        if (displayBmp != null) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (fullBitmap != null) "Full Mosaic Complete" else "Low-Res Quick Preview",
                            fontWeight = FontWeight.SemiBold,
                            color = if (fullBitmap != null) AccentAmber else AccentPink,
                            fontSize = 15.sp
                        )
                        Row {
                            IconButton(onClick = { viewModel.exportToGallery() }) {
                                Icon(Icons.Default.Download, contentDescription = "Export to Pictures", tint = AccentAmber)
                            }
                            IconButton(onClick = { showFullDialog = true }) {
                                Icon(Icons.Default.Fullscreen, contentDescription = "Enlarge", tint = TextPrimary)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        Image(
                            bitmap = displayBmp.asImageBitmap(),
                            contentDescription = "Mosaic Output",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.exportToGallery() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export / Download to Gallery", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Interactive Crop Dialog
    if (showCropDialog && targetBitmap != null) {
        CropSelectionDialog(
            bitmap = viewModel.rawTargetBitmap.value ?: targetBitmap!!,
            onDismiss = { showCropDialog = false },
            onApplyCrop = { left, top, right, bottom ->
                viewModel.applyCropToTarget(left, top, right, bottom)
                showCropDialog = false
            }
        )
    }

    if (showFullDialog && displayBmp != null) {
        AlertDialog(
            onDismissRequest = { showFullDialog = false },
            confirmButton = {
                Row {
                    Button(
                        onClick = { viewModel.exportToGallery() },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download", color = Color.Black)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { showFullDialog = false }) {
                        Text("Close", color = AccentPurple)
                    }
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                ) {
                    Image(
                        bitmap = displayBmp.asImageBitmap(),
                        contentDescription = "Enlarged Mosaic",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            containerColor = SurfaceDark
        )
    }
}

@Composable
fun CropSelectionDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    onApplyCrop: (Float, Float, Float, Float) -> Unit
) {
    var left by remember { mutableStateOf(0.1f) }
    var top by remember { mutableStateOf(0.1f) }
    var right by remember { mutableStateOf(0.9f) }
    var bottom by remember { mutableStateOf(0.9f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Select Mosaic Crop Region", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 18.sp)
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, SurfaceVariantDark, RoundedCornerShape(8.dp))
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Original target",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text("Left Crop Boundary: ${(left * 100).toInt()}%", fontSize = 12.sp, color = TextSecondary)
                Slider(
                    value = left,
                    onValueChange = { left = it.coerceAtMost(right - 0.1f) },
                    valueRange = 0f..0.8f,
                    colors = SliderDefaults.colors(thumbColor = AccentPurple, activeTrackColor = AccentPurple)
                )

                Text("Right Crop Boundary: ${(right * 100).toInt()}%", fontSize = 12.sp, color = TextSecondary)
                Slider(
                    value = right,
                    onValueChange = { right = it.coerceAtLeast(left + 0.1f) },
                    valueRange = 0.2f..1f,
                    colors = SliderDefaults.colors(thumbColor = AccentPurple, activeTrackColor = AccentPurple)
                )

                Text("Top Crop Boundary: ${(top * 100).toInt()}%", fontSize = 12.sp, color = TextSecondary)
                Slider(
                    value = top,
                    onValueChange = { top = it.coerceAtMost(bottom - 0.1f) },
                    valueRange = 0f..0.8f,
                    colors = SliderDefaults.colors(thumbColor = AccentPink, activeTrackColor = AccentPink)
                )

                Text("Bottom Crop Boundary: ${(bottom * 100).toInt()}%", fontSize = 12.sp, color = TextSecondary)
                Slider(
                    value = bottom,
                    onValueChange = { bottom = it.coerceAtLeast(top + 0.1f) },
                    valueRange = 0.2f..1f,
                    colors = SliderDefaults.colors(thumbColor = AccentPink, activeTrackColor = AccentPink)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onApplyCrop(left, top, right, bottom) },
                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)
            ) {
                Text("Apply Crop")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = SurfaceDark
    )
}

@Composable
fun StampsTab(
    viewModel: MainViewModel,
    isExtracting: Boolean,
    onPickImageToExtractStamps: () -> Unit
) {
    val stamps by viewModel.customTileBitmaps.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ContentCut, contentDescription = null, tint = AccentAmber)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI Multi-Subject Stamp Extractor",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Upload any photo (e.g., photo with multiple dogs, friends, or objects). On-device ML isolates and crops each item individually into distinct mosaic stamps.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onPickImageToExtractStamps,
                    enabled = !isExtracting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isExtracting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Extracting Subjects...", color = Color.Black)
                    } else {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Extract Stamps from Image", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Extracted Stamps (${stamps.size})",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = TextPrimary
            )
            if (stamps.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearTiles() }) {
                    Text("Clear All", color = TextSecondary)
                }
            }
        }

        if (stamps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ContentCut, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No stamps extracted yet", color = TextSecondary, fontSize = 14.sp)
                    Text("Pick an image above to auto-detect multiple subjects", color = TextSecondary.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(stamps.size) { index ->
                    val stampBmp = stamps[index]
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Image(
                                bitmap = stampBmp.asImageBitmap(),
                                contentDescription = "Stamp $index",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(6.dp)
                            )
                            IconButton(
                                onClick = { viewModel.removeCustomStamp(index) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(24.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTab(viewModel: MainViewModel) {
    val currentKey by viewModel.geminiApiKey.collectAsState()
    var inputKey by remember { mutableStateOf(currentKey) }
    var passwordVisible by remember { mutableStateOf(false) }
    var savedNotice by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = AccentPurple)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("API Configuration", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Configure external AI vision API keys (such as Google Gemini) for optional enhanced cloud-assisted stamp extraction and styling.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = inputKey,
                    onValueChange = {
                        inputKey = it
                        savedNotice = false
                    },
                    label = { Text("Gemini / Vision API Key") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle key visibility",
                                tint = TextSecondary
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPurple,
                        unfocusedBorderColor = SurfaceVariantDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        viewModel.saveApiKey(inputKey)
                        savedNotice = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Key")
                }

                if (savedNotice) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("API Key saved securely to app preferences.", color = AccentAmber, fontSize = 12.sp)
                }
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("About Mosaic", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Version: 1.2.0 (Play Store Production)", fontSize = 13.sp, color = TextSecondary)
                Text("Built with Jetpack Compose & Google ML Kit", fontSize = 13.sp, color = TextSecondary)
                Text("Storage: Saves exports directly to Pictures / Mosaic", fontSize = 13.sp, color = TextSecondary)
            }
        }
    }
}

@Composable
fun ProgressBanner(label: String, progress: Float) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = label, fontSize = 13.sp, color = TextPrimary)
                Text(text = "${(progress * 100).toInt()}%", fontSize = 13.sp, color = AccentPurple, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = AccentPurple,
                trackColor = SurfaceDark
            )
        }
    }
}

@Composable
fun LibraryTab(
    viewModel: MainViewModel,
    onExportProject: (MosaicProject) -> Unit
) {
    val projects by viewModel.projects.collectAsState()
    var selectedProject by remember { mutableStateOf<MosaicProject?>(null) }

    if (projects.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Collections,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(54.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No saved mosaics yet",
                    fontSize = 16.sp,
                    color = TextSecondary
                )
                Text(
                    text = "Create and render full mosaics in Studio to view them here.",
                    fontSize = 13.sp,
                    color = TextSecondary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(projects, key = { it.id }) { item ->
                ProjectCard(
                    project = item,
                    onClick = { selectedProject = item },
                    onDelete = { viewModel.deleteProject(item.id) },
                    onExport = { onExportProject(item) }
                )
            }
        }
    }

    if (selectedProject != null) {
        val proj = selectedProject!!
        val bmp = remember(proj.fullImagePath) {
            BitmapFactory.decodeFile(proj.fullImagePath)
        }

        AlertDialog(
            onDismissRequest = { selectedProject = null },
            title = {
                Text(text = proj.title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = proj.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Tiles used: ${proj.tileCount} • Columns: ${proj.columns}",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            },
            confirmButton = {
                Row {
                    Button(
                        onClick = { onExportProject(proj) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download", color = Color.Black)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { selectedProject = null }) {
                        Text("Close", color = AccentPurple)
                    }
                }
            },
            containerColor = SurfaceDark
        )
    }
}

@Composable
fun ProjectCard(
    project: MosaicProject,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    val bitmap = remember(project.previewImagePath) {
        BitmapFactory.decodeFile(project.previewImagePath)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = project.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SurfaceVariantDark)
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onExport,
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Export to Pictures",
                            tint = AccentAmber,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = project.title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    maxLines = 1
                )
                Text(
                    text = "${project.tileCount} tiles",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }
    }
}
