package com.kimiwork.sudoku

import kotlin.random.Random

/**
 * 一局数独的运行状态：题目、玩家填写、草稿（铅笔笔记）、失误、计时、撤销栈。
 */
class Game(
    val puzzle: IntArray,
    val solution: IntArray,
    val entries: IntArray = IntArray(81),
    val notes: IntArray = IntArray(81),
    var mistakes: Int = 0,
    var seconds: Int = 0,
    var notesMode: Boolean = false,
    var autoNotes: Boolean = false
) {
    var selected = -1
    var finished = false
        private set

    private data class Snapshot(
        val entries: IntArray,
        val notes: IntArray,
        val mistakes: Int,
        val selected: Int
    )

    private val undoStack = ArrayDeque<Snapshot>()

    fun isGiven(i: Int) = puzzle[i] != 0
    fun valueAt(i: Int) = if (puzzle[i] != 0) puzzle[i] else entries[i]
    fun isWrong(i: Int) = entries[i] != 0 && entries[i] != solution[i]

    private fun pushUndo() {
        undoStack.addLast(Snapshot(entries.copyOf(), notes.copyOf(), mistakes, selected))
        if (undoStack.size > 300) undoStack.removeFirst()
    }

    fun undo(): Boolean {
        val s = undoStack.removeLastOrNull() ?: return false
        s.entries.copyInto(entries)
        s.notes.copyInto(notes)
        mistakes = s.mistakes
        selected = s.selected
        finished = false
        return true
    }

    /** 数字键输入。notesMode 下切换草稿标记，否则落子并判定对错。 */
    fun onDigit(d: Int): Boolean {
        val i = selected
        if (i < 0 || isGiven(i) || finished) return false

        if (notesMode) {
            if (entries[i] != 0) return false
            pushUndo()
            notes[i] = notes[i] xor SudokuEngine.bitOf(d)
            return true
        }

        if (entries[i] == d) return false
        pushUndo()
        entries[i] = d
        notes[i] = 0
        if (d == solution[i]) {
            // 落子后总是从同行/列/宫的草稿中移除该数字（只删不加，不会覆盖手动删除）
            removeFromPeerNotes(i, d)
            if (isComplete()) finished = true
        } else {
            mistakes++
        }
        return true
    }

    fun erase(): Boolean {
        val i = selected
        if (i < 0 || isGiven(i) || finished) return false
        if (entries[i] == 0 && notes[i] == 0) return false
        pushUndo()
        entries[i] = 0
        notes[i] = 0
        // 只重算被擦除这一格的候选，不影响其他格子上用户手动增删的标记
        if (autoNotes) recomputeCellNotes(i)
        return true
    }

    /** 指导功能：直接帮玩家落子（与手动落子走同一套维护逻辑）。 */
    fun applyHintPlacement(idx: Int, d: Int) {
        if (isGiven(idx) || finished) return
        pushUndo()
        entries[idx] = d
        notes[idx] = 0
        removeFromPeerNotes(idx, d)
        if (isComplete()) finished = true
    }

    /** 指导功能：把某一格的草稿恢复为当前盘面的完整合法候选（修正误删）。 */
    fun applyHintNotesRepair(idx: Int) {
        if (isGiven(idx) || finished) return
        pushUndo()
        val board = IntArray(81) { valueAt(it) }
        notes[idx] = if (board[idx] == 0) SudokuEngine.candidateMask(board, idx) else 0
    }

    /** 指导功能：批量删除草稿候选（数对/区块等消除技巧）。 */
    fun applyHintEliminations(elims: List<Pair<Int, Int>>) {
        if (elims.isEmpty() || finished) return
        pushUndo()
        for ((cell, d) in elims) {
            notes[cell] = notes[cell] and SudokuEngine.bitOf(d).inv()
        }
    }

    /** 自动草稿：开启时立刻为所有空格重算合法候选。 */
    fun toggleAutoNotes() {
        autoNotes = !autoNotes
        if (autoNotes) {
            pushUndo()
            recomputeNotes()
        }
    }

    /** 依据当前盘面（题目 + 已填数字）重算所有空格的合法候选草稿。仅在开启自动草稿时调用。 */
    fun recomputeNotes() {
        val board = IntArray(81) { valueAt(it) }
        for (i in 0 until 81) {
            notes[i] = if (board[i] != 0) {
                0 // 已填数字的格子不留草稿
            } else {
                SudokuEngine.candidateMask(board, i)
            }
        }
    }

    /** 落子后增量维护：仅把数字 d 从同行、同列、同宫的草稿中移除。 */
    private fun removeFromPeerNotes(idx: Int, d: Int) {
        val clearMask = SudokuEngine.bitOf(d).inv()
        val r = SudokuEngine.rowOf(idx)
        val c = SudokuEngine.colOf(idx)
        val b = SudokuEngine.boxOf(idx)
        for (i in 0 until 81) {
            if (i == idx) continue
            if (SudokuEngine.rowOf(i) == r || SudokuEngine.colOf(i) == c || SudokuEngine.boxOf(i) == b) {
                notes[i] = notes[i] and clearMask
            }
        }
    }

    /** 只重算单个空格的合法候选（用于橡皮擦除后）。 */
    private fun recomputeCellNotes(idx: Int) {
        val board = IntArray(81) { valueAt(it) }
        notes[idx] = if (board[idx] == 0) SudokuEngine.candidateMask(board, idx) else 0
    }

    fun isComplete(): Boolean {
        for (i in 0 until 81) {
            if (valueAt(i) != solution[i]) return false
        }
        return true
    }

    // ---------- 存档序列化 ----------

    fun serialize(): String {
        return listOf(
            puzzle.joinToString(","),
            solution.joinToString(","),
            entries.joinToString(","),
            notes.joinToString(","),
            mistakes.toString(),
            seconds.toString(),
            if (notesMode) "1" else "0",
            if (autoNotes) "1" else "0"
        ).joinToString(";")
    }

    companion object {
        fun deserialize(data: String): Game? {
            return try {
                val parts = data.split(";")
                require(parts.size == 8)
                fun arr(s: String): IntArray {
                    if (s.isEmpty()) return IntArray(81)
                    val a = s.split(",").map { it.toInt() }.toIntArray()
                    require(a.size == 81)
                    return a
                }
                Game(
                    puzzle = arr(parts[0]),
                    solution = arr(parts[1]),
                    entries = arr(parts[2]),
                    notes = arr(parts[3]),
                    mistakes = parts[4].toInt(),
                    seconds = parts[5].toInt(),
                    notesMode = parts[6] == "1",
                    autoNotes = parts[7] == "1"
                )
            } catch (e: Exception) {
                null
            }
        }

        fun newHard(): Game {
            val (p, s) = SudokuEngine.generateHard(Random.Default)
            return Game(p, s)
        }
    }
}
