package com.intrusivethots.mosaic.ui.screens

import com.intrusivethots.mosaic.engine.config.HybridStack
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.QualityPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigActionsTest {
    private class Holder : ConfigActions {
        var config = MosaicConfig()
        override fun editConfig(transform: (MosaicConfig) -> MosaicConfig) {
            config = transform(config)
        }
    }

    @Test
    fun gridSizeIsClampedAndMarksCustom() {
        val holder = Holder()
        holder.updateGridColumns(500)
        holder.updateGridRows(1)
        assertEquals(120, holder.config.gridColumns)
        assertEquals(8, holder.config.gridRows)
        assertEquals(QualityPreset.CUSTOM, holder.config.qualityPreset)
    }

    @Test
    fun kindAndStackStayInStep() {
        val holder = Holder()
        holder.applyStack(HybridStack.GRID)
        assertEquals(MosaicKind.GRID, holder.config.mosaicKind)
        holder.updateMosaicKind(MosaicKind.COLLAGE)
        assertEquals(HybridStack.CUTOUTS, holder.config.collage.stack)
    }

    @Test
    fun repetitionDistanceIsClamped() {
        val holder = Holder()
        holder.updateRepetition(allow = true, distance = 99)
        assertEquals(12, holder.config.maxRepetitionDistance)
    }
}
