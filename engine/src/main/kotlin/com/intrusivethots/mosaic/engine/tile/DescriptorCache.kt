package com.intrusivethots.mosaic.engine.tile

import com.intrusivethots.mosaic.engine.color.HISTOGRAM_BIN_COUNT
import com.intrusivethots.mosaic.engine.color.SPATIAL_FLOATS
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

interface DescriptorCache {
    fun get(key: DescriptorKey): TileDescriptor?
    fun put(key: DescriptorKey, descriptor: TileDescriptor)
    fun remove(key: DescriptorKey)
    fun retain(keys: Set<DescriptorKey>)
    fun flush()
}

class MemoryDescriptorCache : DescriptorCache {
    private val entries = LinkedHashMap<DescriptorKey, TileDescriptor>()

    override fun get(key: DescriptorKey): TileDescriptor? = entries[key]

    override fun put(key: DescriptorKey, descriptor: TileDescriptor) {
        entries[key] = descriptor
    }

    override fun remove(key: DescriptorKey) {
        entries.remove(key)
    }

    override fun retain(keys: Set<DescriptorKey>) {
        entries.keys.retainAll(keys)
    }

    override fun flush() = Unit

    fun size(): Int = entries.size
}

/**
 * Versioned binary cache. A corrupt file is ignored and replaced on the next successful flush
 * so a bad write cannot block generation.
 */
class FileDescriptorCache(private val file: File) : DescriptorCache {
    private val entries = LinkedHashMap<DescriptorKey, TileDescriptor>()
    private var dirty = false

    init {
        load()
    }

    override fun get(key: DescriptorKey): TileDescriptor? = synchronized(entries) { entries[key] }

    override fun put(key: DescriptorKey, descriptor: TileDescriptor) = synchronized(entries) {
        entries[key] = descriptor
        dirty = true
    }

    override fun remove(key: DescriptorKey) = synchronized(entries) {
        if (entries.remove(key) != null) dirty = true
    }

    override fun retain(keys: Set<DescriptorKey>) = synchronized(entries) {
        val before = entries.size
        entries.keys.retainAll(keys)
        if (entries.size != before) dirty = true
    }

    override fun flush() = synchronized(entries) {
        if (!dirty) return
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            DataOutputStream(FileOutputStream(temporary).buffered()).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FORMAT)
                output.writeInt(entries.size)
                entries.values.forEach { descriptor -> writeDescriptor(output, descriptor) }
            }
            if (file.exists() && !file.delete()) {
                temporary.delete()
                return
            }
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
            dirty = false
        } catch (exception: Exception) {
            temporary.delete()
            throw exception
        }
    }

    fun size(): Int = synchronized(entries) { entries.size }

    private fun load() {
        if (!file.exists()) return
        try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != FORMAT) return
                val count = input.readInt()
                repeat(count) {
                    val descriptor = readDescriptor(input)
                    entries[descriptor.key] = descriptor
                }
            }
        } catch (exception: Exception) {
            entries.clear()
        }
    }

    private fun writeDescriptor(output: DataOutputStream, descriptor: TileDescriptor) {
        val key = descriptor.key
        output.writeUTF(key.uri)
        output.writeInt(key.width)
        output.writeInt(key.height)
        output.writeLong(key.byteSize)
        output.writeLong(key.modifiedTimeMs)
        output.writeInt(key.algorithmVersion)
        output.writeInt(descriptor.sourceWidth)
        output.writeInt(descriptor.sourceHeight)
        output.writeFloat(descriptor.aspectRatio)
        output.writeFloat(descriptor.labL)
        output.writeFloat(descriptor.labA)
        output.writeFloat(descriptor.labB)
        output.writeFloat(descriptor.luminance)
        output.writeFloat(descriptor.saturation)
        output.writeFloat(descriptor.edgeDensity)
        output.writeFloat(descriptor.alphaCoverage)
        descriptor.histogram.forEach { output.writeFloat(it) }
        descriptor.spatial.forEach { output.writeFloat(it) }
    }

    private fun readDescriptor(input: DataInputStream): TileDescriptor {
        val key = DescriptorKey(
            uri = input.readUTF(),
            width = input.readInt(),
            height = input.readInt(),
            byteSize = input.readLong(),
            modifiedTimeMs = input.readLong(),
            algorithmVersion = input.readInt()
        )
        return TileDescriptor(
            key = key,
            sourceWidth = input.readInt(),
            sourceHeight = input.readInt(),
            aspectRatio = input.readFloat(),
            labL = input.readFloat(),
            labA = input.readFloat(),
            labB = input.readFloat(),
            luminance = input.readFloat(),
            saturation = input.readFloat(),
            edgeDensity = input.readFloat(),
            alphaCoverage = input.readFloat(),
            histogram = FloatArray(HISTOGRAM_BIN_COUNT) { input.readFloat() },
            spatial = FloatArray(SPATIAL_FLOATS) { input.readFloat() }
        )
    }

    companion object {
        private const val MAGIC = 0x4D4F5344
        private const val FORMAT = 1
    }
}
