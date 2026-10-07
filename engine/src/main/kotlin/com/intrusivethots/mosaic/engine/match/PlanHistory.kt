package com.intrusivethots.mosaic.engine.match

/**
 * Edit history for one collage. [push] stores the plan from before a change.
 * Undo and redo walk that list. A new edit drops the redo branch.
 */
class PlanHistory(private val limit: Int = DEFAULT_LIMIT) {
    private val undo = ArrayDeque<MosaicPlan>()
    private val redo = ArrayDeque<MosaicPlan>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val undoSteps: Int get() = undo.size
    val redoSteps: Int get() = redo.size

    fun push(current: MosaicPlan) {
        undo.addLast(current)
        while (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    fun undo(current: MosaicPlan): MosaicPlan? {
        val previous = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        return previous
    }

    fun redo(current: MosaicPlan): MosaicPlan? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        return next
    }

    /** Drops a push when the edit that followed it did not finish. */
    fun discardPush() {
        undo.removeLastOrNull()
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }

    fun restore(undoPlans: List<MosaicPlan>, redoPlans: List<MosaicPlan>) {
        undo.clear()
        redo.clear()
        undoPlans.takeLast(limit).forEach { undo.addLast(it) }
        redoPlans.takeLast(limit).forEach { redo.addLast(it) }
    }

    fun undoList(): List<MosaicPlan> = undo.toList()

    fun redoList(): List<MosaicPlan> = redo.toList()

    companion object {
        const val DEFAULT_LIMIT = 16
    }
}
