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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.intrusivethots.mosaic.core.AspectRatioPreset
import com.intrusivethots.mosaic.core.MosaicStyle
import com.intrusivethots.mosaic.data.MosaicProject
import com.intrusivethots.mosaic.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) } // 0: Studio, 1: Library

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

    // Multiple photo picker for tiles
    val tilesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addTileImages(uris)
        }
    }

    Scaffold(
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
                    onClick = {
                        selectedTab = 1
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
            }
        },
        containerColor = DeepBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectedTab == 0) {
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
            } else {
                LibraryTab(
                    viewModel = viewModel
                )
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
    val genState by viewModel.generationState.collectAsState()
    val config by viewModel.config.collectAsState()

    var projectTitle by remember { mutableStateOf("") }
    var showFullDialog by remember { mutableStateOf(false) }
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
                            contentScale = ContentScale.Crop,
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

        // Step 2: Tile Images Card
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
                            text = "2. Smaller Tile Images",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "${tileUris.size} tile images loaded",
                            fontSize = 13.sp,
                            color = if (tileUris.isEmpty()) TextSecondary else AccentPink
                        )
                    }
                    if (tileUris.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearTiles() }) {
                            Text("Clear", color = TextSecondary)
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
                            Text("AI Subject Segmentation", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPrimary)
                        }
                        Text("Extract individual people, pets & objects from photos to form varied tile shapes", fontSize = 12.sp, color = TextSecondary)
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.generatePreview() },
                enabled = targetBitmap != null && tileUris.isNotEmpty() && genState == GenerationState.Idle,
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
                enabled = targetBitmap != null && tileUris.isNotEmpty() && genState == GenerationState.Idle,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Render Full")
            }
        }

        // Output Display Area
        val displayBmp = fullBitmap ?: previewBitmap
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
                        IconButton(onClick = { showFullDialog = true }) {
                            Icon(Icons.Default.Fullscreen, contentDescription = "Enlarge", tint = TextPrimary)
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
                }
            }
        }
    }

    if (showFullDialog && displayBmp != null) {
        AlertDialog(
            onDismissRequest = { showFullDialog = false },
            confirmButton = {
                TextButton(onClick = { showFullDialog = false }) {
                    Text("Close", color = AccentPurple)
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
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
fun LibraryTab(viewModel: MainViewModel) {
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
                    onDelete = { viewModel.deleteProject(item.id) }
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
                TextButton(onClick = { selectedProject = null }) {
                    Text("Close", color = AccentPurple)
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
    onDelete: () -> Unit
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

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
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
