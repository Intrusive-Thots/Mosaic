package com.intrusivethots.mosaic.core

import com.intrusivethots.mosaic.engine.match.PlanHistory
import java.io.File

/** Undo pixels for region edits. A skip mark keeps this stack aligned with [PlanHistory]. */
internal class PixelHistory(private val directory: File) {
    private val undo = ArrayDeque<Mark>()
    private val redo = ArrayDeque<Mark>()

    val undoCount: Int get() = undo.size

    fun fileFor(mark: Mark.Window): File = File(directory, mark.fileName)

    fun pushSkip() {
        undo.addLast(Mark.Skip)
        trim(undo)
        clearMarks(redo)
        save()
    }

    fun pushWindow(backup: File, x: Int, y: Int, width: Int, height: Int, imagePath: String) {
        directory.mkdirs()
        val stored = File(directory, backup.name)
        if (backup.absolutePath != stored.absolutePath) {
            if (!backup.renameTo(stored)) backup.copyTo(stored, overwrite = true)
            if (backup.exists() && backup.absolutePath != stored.absolutePath) backup.delete()
        }
        undo.addLast(Mark.Window(x, y, width, height, stored.name, imagePath))
        trim(undo)
        clearMarks(redo)
        save()
    }

    fun undo(): Mark.Window? = step(undo, redo)

    fun redo(): Mark.Window? = step(redo, undo)

    fun load() {
        undo.clear()
        redo.clear()
        val file = File(directory, MANIFEST)
        if (!file.exists()) return
        val lines = file.readLines()
        var intoRedo = false
        for (line in lines) {
            if (line == "REDO") {
                intoRedo = true
                continue
            }
            val mark = decode(line) ?: continue
            if (intoRedo) redo.addLast(mark) else undo.addLast(mark)
        }
    }

    fun clear() {
        clearMarks(undo)
        clearMarks(redo)
        File(directory, MANIFEST).delete()
    }

    private fun step(from: ArrayDeque<Mark>, to: ArrayDeque<Mark>): Mark.Window? {
        val mark = from.removeLastOrNull() ?: return null
        if (mark is Mark.Skip) {
            to.addLast(Mark.Skip)
            save()
            return null
        }
        val window = mark as Mark.Window
        val image = File(window.imagePath)
        val captured = File(directory, "cap-${System.nanoTime()}.png")
        val copied = copyWindowPng(image, window.x, window.y, window.width, window.height, captured)
        if (!copied) {
            from.addLast(window)
            captured.delete()
            return null
        }
        to.addLast(window.copy(fileName = captured.name))
        save()
        return window
    }

    private fun trim(stack: ArrayDeque<Mark>) {
        while (stack.size > PlanHistory.DEFAULT_LIMIT) deleteMark(stack.removeFirst())
    }

    private fun clearMarks(stack: ArrayDeque<Mark>) {
        stack.forEach { deleteMark(it) }
        stack.clear()
    }

    private fun deleteMark(mark: Mark) {
        if (mark is Mark.Window) File(directory, mark.fileName).delete()
    }

    private fun save() {
        directory.mkdirs()
        val lines = ArrayList<String>(undo.size + redo.size + 1)
        undo.forEach { lines.add(encode(it)) }
        lines.add("REDO")
        redo.forEach { lines.add(encode(it)) }
        File(directory, MANIFEST).writeText(lines.joinToString("\n"))
    }

    private fun encode(mark: Mark): String {
        if (mark is Mark.Skip) return "skip"
        val window = mark as Mark.Window
        return listOf("window", window.x, window.y, window.width, window.height, window.fileName, window.imagePath)
            .joinToString("\t")
    }

    private fun decode(line: String): Mark? {
        if (line == "skip") return Mark.Skip
        val parts = line.split('\t')
        if (parts.size < 7 || parts[0] != "window") return null
        val x = parts[1].toIntOrNull() ?: return null
        val y = parts[2].toIntOrNull() ?: return null
        val width = parts[3].toIntOrNull() ?: return null
        val height = parts[4].toIntOrNull() ?: return null
        return Mark.Window(x, y, width, height, parts[5], parts[6])
    }

    internal sealed class Mark {
        data object Skip : Mark()
        data class Window(
            val x: Int,
            val y: Int,
            val width: Int,
            val height: Int,
            val fileName: String,
            val imagePath: String
        ) : Mark()
    }

    companion object {
        private const val MANIFEST = "marks.txt"
    }
}
