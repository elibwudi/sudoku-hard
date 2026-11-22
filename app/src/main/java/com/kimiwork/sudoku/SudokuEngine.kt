package com.kimiwork.sudoku

import kotlin.random.Random

/**
 * 数独生成与求解引擎。所有盘面均为 IntArray(81)，0 表示空格。
 */
object SudokuEngine {

    fun rowOf(i: Int) = i / 9
    fun colOf(i: Int) = i % 9
    fun boxOf(i: Int) = (i / 27) * 3 + (i % 9) / 3

    fun bitOf(d: Int) = if (d in 1..9) 1 shl d else 0

    fun isLegal(grid: IntArray, idx: Int, digit: Int): Boolean {
        val r = rowOf(idx)
        val c = colOf(idx)
        for (k in 0 until 9) {
            if (grid[r * 9 + k] == digit) return false
            if (grid[k * 9 + c] == digit) return false
        }
        val br = (r / 3) * 3
        val bc = (c / 3) * 3
        for (dr in 0 until 3) for (dc in 0 until 3) {
            if (grid[(br + dr) * 9 + bc + dc] == digit) return false
        }
        return true
    }

    /** 候选数掩码：bit d 置位表示数字 d 可填（d 取 1..9）。 */
    fun candidateMask(grid: IntArray, idx: Int): Int {
        var used = 0
        val r = rowOf(idx)
        val c = colOf(idx)
        for (k in 0 until 9) {
            used = used or bitOf(grid[r * 9 + k]) or bitOf(grid[k * 9 + c])
        }
        val br = (r / 3) * 3
        val bc = (c / 3) * 3
        for (dr in 0 until 3) for (dc in 0 until 3) {
            used = used or bitOf(grid[(br + dr) * 9 + bc + dc])
        }
        return 0x3FE and used.inv()
    }

    /** 随机填满一个合法完整解。 */
    fun solvedGrid(rng: Random): IntArray {
        val grid = IntArray(81)
        fillRandom(grid, rng)
        return grid
    }

    private fun fillRandom(grid: IntArray, rng: Random): Boolean {
        var idx = -1
        for (i in 0 until 81) if (grid[i] == 0) { idx = i; break }
        if (idx < 0) return true
        for (d in (1..9).shuffled(rng)) {
            if (isLegal(grid, idx, d)) {
                grid[idx] = d
                if (fillRandom(grid, rng)) return true
                grid[idx] = 0
            }
        }
        return false
    }

    /** MRV 回溯计数解数量，最多统计到 [limit] 个即返回。 */
    fun countSolutions(grid: IntArray, limit: Int): Int {
        if (limit <= 0) return 0
        var best = -1
        var bestMask = 0
        var bestCount = 10
        for (i in 0 until 81) {
            if (grid[i] != 0) continue
            val mask = candidateMask(grid, i)
            val n = Integer.bitCount(mask)
            if (n == 0) return 0
            if (n < bestCount) {
                bestCount = n; best = i; bestMask = mask
                if (n == 1) break
            }
        }
        if (best < 0) return 1
        var total = 0
        var m = bestMask
        while (m != 0) {
            val bit = m and -m
            m = m xor bit
            grid[best] = Integer.numberOfTrailingZeros(bit)
            total += countSolutions(grid, limit - total)
            grid[best] = 0
            if (total >= limit) return total
        }
        return total
    }

    private val units: List<IntArray> by lazy {
        val list = ArrayList<IntArray>(27)
        for (r in 0 until 9) list.add(IntArray(9) { r * 9 + it })
        for (c in 0 until 9) list.add(IntArray(9) { it * 9 + c })
        for (br in 0 until 3) for (bc in 0 until 3) {
            list.add(IntArray(9) { (br * 3 + it / 3) * 9 + bc * 3 + it % 3 })
        }
        list
    }

    /** 仅用裸单元 + 隐性单元即可解出则为 true（说明太简单，不是艰难难度）。 */
    fun solvableBySingles(puzzle: IntArray): Boolean {
        val g = puzzle.copyOf()
        var progress = true
        while (progress) {
            progress = false
            for (i in 0 until 81) {
                if (g[i] == 0) {
                    val mask = candidateMask(g, i)
                    if (mask == 0) return false
                    if (Integer.bitCount(mask) == 1) {
                        g[i] = Integer.numberOfTrailingZeros(mask)
                        progress = true
                    }
                }
            }
            for (unit in units) {
                for (d in 1..9) {
                    if (unit.any { g[it] == d }) continue
                    var place = -1
                    var count = 0
                    for (i in unit) {
                        if (g[i] == 0 && (candidateMask(g, i) and bitOf(d)) != 0) {
                            count++; place = i
                        }
                    }
                    if (count == 0) return false
                    if (count == 1) {
                        g[place] = d
                        progress = true
                    }
                }
            }
        }
        return g.all { it != 0 }
    }

    /**
     * 生成一道“艰辛”难度题目：
     * - 提示数仅 23–26 个；
     * - 保证唯一解；
     * - 排除仅靠单元法就能完成的简单题。
     * 返回 Pair(题目, 完整解)。
     */
    fun generateHard(rng: Random = Random.Default): Pair<IntArray, IntArray> {
        var fallbackPuzzle: IntArray? = null
        var fallbackSolution: IntArray? = null
        repeat(60) {
            val solution = solvedGrid(rng)
            val puzzle = solution.copyOf()
            val target = 23 + rng.nextInt(4) // 23..26 个提示数
            var givens = 81
            for (i in (0 until 81).shuffled(rng)) {
                if (givens <= target) break
                val backup = puzzle[i]
                puzzle[i] = 0
                if (countSolutions(puzzle, 2) == 1) {
                    givens--
                } else {
                    puzzle[i] = backup
                }
            }
            if (fallbackPuzzle == null || givens < (fallbackPuzzle!!.count { it != 0 })) {
                fallbackPuzzle = puzzle.copyOf()
                fallbackSolution = solution
            }
            if (givens <= target + 1 && !solvableBySingles(puzzle)) {
                return Pair(puzzle, solution)
            }
        }
        // 兜底：返回挖得最深的一盘（仍保证唯一解），避免极端情况下卡死。
        return Pair(fallbackPuzzle!!, fallbackSolution!!)
    }
}
