package com.intrusivethots.mosaic.engine.match

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A collage plan plus the undo and redo stacks, saved so a process death can restore the edit.
 * [editX] and [editY] are NaN when the user has not tapped a piece.
 */
class CollageSession(
    val current: MosaicPlan,
    val undo: List<MosaicPlan>,
    val redo: List<MosaicPlan>,
    val targetUri: String,
    val tileUris: List<String>,
    val editX: Float,
    val editY: Float,
    val tileCount: Int
) {
    val editPoint: Pair<Float, Float>?
        get() = if (editX.isNaN() || editY.isNaN()) null else editX to editY
}

fun writeCollageSession(session: CollageSession, output: OutputStream) {
    val data = DataOutputStream(output)
    data.writeInt(MAGIC)
    data.writeInt(VERSION)
    writePlan(data, session.current)
    writePlans(data, session.undo)
    writePlans(data, session.redo)
    writeText(data, session.targetUri)
    data.writeInt(session.tileUris.size)
    session.tileUris.forEach { writeText(data, it) }
    data.writeFloat(session.editX)
    data.writeFloat(session.editY)
    data.writeInt(session.tileCount)
    data.flush()
}

fun readCollageSession(input: InputStream): CollageSession? = try {
    readSession(DataInputStream(input))
} catch (_: Exception) {
    null
}

private fun readSession(data: DataInputStream): CollageSession {
    require(data.readInt() == MAGIC)
    val version = data.readInt()
    require(version == 1 || version == VERSION)
    val current = readPlan(data, version)
    val undo = readPlans(data, version)
    val redo = readPlans(data, version)
    val targetUri = readText(data)
    val uriCount = data.readInt()
    require(uriCount in 0..10_000)
    val tileUris = List(uriCount) { readText(data) }
    return CollageSession(
        current = current,
        undo = undo,
        redo = redo,
        targetUri = targetUri,
        tileUris = tileUris,
        editX = data.readFloat(),
        editY = data.readFloat(),
        tileCount = data.readInt()
    )
}

private fun writePlans(data: DataOutputStream, plans: List<MosaicPlan>) {
    val kept = plans.takeLast(PlanHistory.DEFAULT_LIMIT)
    data.writeInt(kept.size)
    kept.forEach { writePlan(data, it) }
}

private fun readPlans(data: DataInputStream, version: Int): List<MosaicPlan> {
    val count = data.readInt()
    require(count in 0..PlanHistory.DEFAULT_LIMIT)
    return List(count) { readPlan(data, version) }
}

private fun writePlan(data: DataOutputStream, plan: MosaicPlan) {
    data.writeInt(plan.columns)
    data.writeInt(plan.rows)
    writeInts(data, plan.assignments)
    writeInts(data, plan.cellRgb)
    data.writeInt(plan.cellLab.size)
    plan.cellLab.forEach { data.writeFloat(it) }
    data.writeByte(if (plan.staggered) 1 else 0)
    writeText(data, plan.fingerprint)
    writeBytes(data, plan.orientations)
    writeInts(data, plan.anchors)
    writeBytes(data, plan.spanX)
    writeBytes(data, plan.spanY)
    data.writeInt(plan.placements.size)
    plan.placements.forEach { placement ->
        data.writeInt(placement.tileIndex)
        data.writeFloat(placement.x)
        data.writeFloat(placement.y)
        data.writeFloat(placement.angleDegrees)
        data.writeFloat(placement.scale)
        data.writeFloat(placement.targetL)
        data.writeFloat(placement.targetA)
        data.writeFloat(placement.targetB)
        data.writeByte(if (placement.pinned) 1 else 0)
        data.writeFloat(placement.cropU)
        data.writeFloat(placement.cropV)
        data.writeFloat(placement.cropSpan)
        writeMask(data, placement.mask)
    }
    data.writeFloat(plan.coverage)
}

private fun readPlan(data: DataInputStream, version: Int): MosaicPlan {
    val columns = data.readInt()
    val rows = data.readInt()
    require(columns in 1..200 && rows in 1..200)
    val assignments = readInts(data)
    val cellRgb = readInts(data)
    val labCount = data.readInt()
    require(labCount == columns * rows * 3)
    val cellLab = FloatArray(labCount) { data.readFloat() }
    val staggered = data.readByte().toInt() != 0
    val fingerprint = readText(data)
    return MosaicPlan(
        columns = columns,
        rows = rows,
        assignments = assignments,
        cellRgb = cellRgb,
        cellLab = cellLab,
        staggered = staggered,
        fingerprint = fingerprint,
        orientations = readBytes(data),
        anchors = readInts(data),
        spanX = readBytes(data),
        spanY = readBytes(data),
        placements = List(readPlacementCount(data)) { readPlacement(data, version) },
        coverage = data.readFloat()
    )
}

private fun readPlacement(data: DataInputStream, version: Int): CutoutPlacement {
    val tileIndex = data.readInt()
    val x = data.readFloat()
    val y = data.readFloat()
    val angle = data.readFloat()
    val scale = data.readFloat()
    val targetL = data.readFloat()
    val targetA = data.readFloat()
    val targetB = data.readFloat()
    val pinned = data.readByte().toInt() != 0
    if (version < VERSION) {
        return CutoutPlacement(tileIndex, x, y, angle, scale, targetL, targetA, targetB, pinned)
    }
    val cropU = data.readFloat()
    val cropV = data.readFloat()
    val cropSpan = data.readFloat()
    return CutoutPlacement(
        tileIndex, x, y, angle, scale, targetL, targetA, targetB, pinned, readMask(data), cropU, cropV, cropSpan
    )
}

private fun writeMask(data: DataOutputStream, mask: PieceMask?) {
    if (mask == null) {
        data.writeByte(0)
        return
    }
    data.writeByte(1)
    data.writeFloat(mask.left)
    data.writeFloat(mask.top)
    data.writeFloat(mask.right)
    data.writeFloat(mask.bottom)
    data.writeInt(mask.width)
    data.writeInt(mask.height)
    data.write(mask.alpha)
}

private fun readMask(data: DataInputStream): PieceMask? {
    if (data.readByte().toInt() == 0) return null
    val left = data.readFloat()
    val top = data.readFloat()
    val right = data.readFloat()
    val bottom = data.readFloat()
    val width = data.readInt()
    val height = data.readInt()
    require(width in 1..PieceMask.MAX_EDGE && height in 1..PieceMask.MAX_EDGE)
    val alpha = ByteArray(width * height)
    data.readFully(alpha)
    return PieceMask(left, top, right, bottom, width, height, alpha)
}

private fun readPlacementCount(data: DataInputStream): Int {
    val count = data.readInt()
    require(count in 0..4_000)
    return count
}

private fun writeInts(data: DataOutputStream, values: IntArray) {
    data.writeInt(values.size)
    values.forEach { data.writeInt(it) }
}

private fun readInts(data: DataInputStream): IntArray {
    val count = data.readInt()
    require(count in 0..MAX_CELLS)
    return IntArray(count) { data.readInt() }
}

private fun writeBytes(data: DataOutputStream, values: ByteArray) {
    data.writeInt(values.size)
    data.write(values)
}

private fun readBytes(data: DataInputStream): ByteArray {
    val count = data.readInt()
    require(count in 0..MAX_CELLS)
    val values = ByteArray(count)
    if (count > 0) data.readFully(values)
    return values
}

private fun writeText(data: DataOutputStream, value: String) {
    val bytes = value.encodeToByteArray()
    data.writeInt(bytes.size)
    data.write(bytes)
}

private fun readText(data: DataInputStream): String {
    val count = data.readInt()
    require(count in 0..MAX_TEXT)
    val bytes = ByteArray(count)
    if (count > 0) data.readFully(bytes)
    return bytes.decodeToString()
}

private const val MAGIC = 0x4D504C4E
private const val VERSION = 2
private const val MAX_CELLS = 200 * 200
private const val MAX_TEXT = 1_000_000
