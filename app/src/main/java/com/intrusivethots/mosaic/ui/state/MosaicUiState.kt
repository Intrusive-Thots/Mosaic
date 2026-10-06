package com.intrusivethots.mosaic.ui.state

import android.graphics.Bitmap
import android.net.Uri
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.QualityPreset
import com.intrusivethots.mosaic.engine.config.applyTo
import com.intrusivethots.mosaic.engine.progress.GenerationStage
import com.intrusivethots.mosaic.data.MosaicProject

data class MosaicUiState(
    val targetUri: Uri? = null,
    val tileUris: List<Uri> = emptyList(),
    val tileQuarterTurns: List<Int> = emptyList(),
    val tileThumbs: List<Bitmap?> = emptyList(),
    val targetBitmap: Bitmap? = null,
    val rawTargetBitmap: Bitmap? = null,
    val customStamps: List<Bitmap> = emptyList(),
    val previewBitmap: Bitmap? = null,
    val displayBitmap: Bitmap? = null,
    val hasFullRender: Boolean = false,
    val fullImagePath: String? = null,
    val config: MosaicConfig = QualityPreset.BALANCED.applyTo(MosaicConfig()),
    val generation: GenerationUiState = GenerationUiState.Idle,
    val projects: List<MosaicProject> = emptyList(),
    val apiKey: String = "",
    val keystoreAvailable: Boolean = true
) {
    val hasTiles: Boolean get() = tileUris.isNotEmpty() || customStamps.isNotEmpty()
    val outputBitmap: Bitmap? get() = displayBitmap ?: previewBitmap
}

sealed interface GenerationUiState {
    data object Idle : GenerationUiState
    data class Running(val stage: GenerationStage, val fraction: Float, val label: String) : GenerationUiState
    data object Complete : GenerationUiState
    data class Failed(val message: String) : GenerationUiState
}

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
}
