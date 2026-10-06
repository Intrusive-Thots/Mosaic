package com.intrusivethots.mosaic.data

fun encodeTileRotations(uris: List<String>, turns: List<Int>): String = buildString {
    uris.forEachIndexed { index, uri ->
        val turn = turns.getOrElse(index) { 0 } and 3
        if (turn != 0 && uri.isNotEmpty()) {
            append(turn).append('\t').append(uri).append('\n')
        }
    }
}

fun decodeTileRotations(raw: String?): Map<String, Int> {
    if (raw.isNullOrBlank()) return emptyMap()
    val decoded = LinkedHashMap<String, Int>()
    raw.lineSequence().forEach { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0 || tab >= line.lastIndex) return@forEach
        val turn = line.substring(0, tab).toIntOrNull() ?: return@forEach
        decoded[line.substring(tab + 1)] = turn and 3
    }
    return decoded
}
