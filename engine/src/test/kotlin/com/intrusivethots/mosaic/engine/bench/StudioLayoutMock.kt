package com.intrusivethots.mosaic.engine.bench

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * A phone-frame drawing of the Studio hierarchy. It is a layout mock, not a device screenshot.
 */
fun writeStudioMock(files: List<File>) {
    val image = BufferedImage(440, 920, BufferedImage.TYPE_INT_RGB)
    val canvas = image.createGraphics()
    canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    canvas.color = Color(0x0D0B14)
    canvas.fillRect(0, 0, image.width, image.height)
    drawCaption(canvas)
    drawPhone(canvas)
    canvas.dispose()
    files.forEach { file ->
        try {
            file.parentFile?.mkdirs()
            ImageIO.write(image, "png", file)
        } catch (failure: java.io.IOException) {
            if (!file.path.startsWith("/opt/cursor/artifacts")) throw failure
            System.err.println("Artifact write skipped for ${file.path}: ${failure.message}")
        }
    }
}

private fun drawCaption(canvas: Graphics2D) {
    canvas.color = Color(0x94A3B8)
    canvas.font = Font("SansSerif", Font.PLAIN, 13)
    canvas.drawString("Layout mock — not a device screenshot", 24, 28)
}

private fun drawPhone(canvas: Graphics2D) {
    canvas.color = Color(0x161224)
    canvas.fillRoundRect(28, 44, 384, 848, 36, 36)
    canvas.color = Color(0xF8FAFC)
    canvas.font = Font("SansSerif", Font.BOLD, 18)
    canvas.drawString("Studio", 52, 84)
    chipRow(canvas, 108, listOf("Grid" to false, "Collage" to true))
    label(canvas, "Style", 168)
    chipRow(canvas, 188, listOf("Paper" to false, "Stamp" to false, "Dense" to true))
    label(canvas, "Stack", 248)
    chipRow(canvas, 268, listOf("Cutouts" to true, "Grid under" to false))
    label(canvas, "Quality", 328)
    chipRow(canvas, 348, listOf("Draft" to false, "Balanced" to true, "High" to false))
    card(canvas, 408, 92, "Library  ·  24 cutouts")
    card(canvas, 516, 150, "Result  ·  tap a piece to edit")
    card(canvas, 682, 48, "Advanced")
    canvas.color = Color(0xF59E0B)
    canvas.fillRoundRect(52, 800, 336, 56, 28, 28)
    canvas.color = Color(0x0D0B14)
    canvas.font = Font("SansSerif", Font.BOLD, 18)
    canvas.drawString("Generate", 168, 834)
}

private fun label(canvas: Graphics2D, text: String, y: Int) {
    canvas.color = Color(0x94A3B8)
    canvas.font = Font("SansSerif", Font.PLAIN, 13)
    canvas.drawString(text, 52, y)
}

private fun chipRow(canvas: Graphics2D, y: Int, chips: List<Pair<String, Boolean>>) {
    var x = 52
    canvas.font = Font("SansSerif", Font.PLAIN, 14)
    chips.forEach { (text, selected) ->
        val width = 24 + text.length * 9
        canvas.color = if (selected) Color(0xF59E0B) else Color(0x221C38)
        canvas.fillRoundRect(x, y, width, 44, 22, 22)
        canvas.color = if (selected) Color(0x0D0B14) else Color(0xF8FAFC)
        canvas.drawString(text, x + 12, y + 28)
        x += width + 8
    }
}

private fun card(canvas: Graphics2D, y: Int, height: Int, title: String) {
    canvas.color = Color(0x221C38)
    canvas.fillRoundRect(52, y, 336, height, 16, 16)
    canvas.color = Color(0xF8FAFC)
    canvas.font = Font("SansSerif", Font.PLAIN, 15)
    canvas.drawString(title, 68, y + 28)
}
