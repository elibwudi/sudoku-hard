package com.kimiwork.sudoku

/**
 * 新手指导引擎：分析当前盘面，按由易到难的顺序找出下一步逻辑，
 * 并生成中文讲解，帮助玩家理解"为什么"而不是直接报答案。
 *
 * 技巧顺序：纠错 → 唯余法 → 摒除法 → 数对占位 → 区块摒除。
 */
object SudokuTutor {

    data class Hint(
        val technique: String,                          // 技巧名称
        val explanation: String,                        // 中文讲解
        val targets: List<Int>,                       // 目标格（主高亮）
        val related: List<Int>,                       // 关联格（次高亮）
        val digit: Int,                                 // 涉及数字
        val place: Boolean,                             // true=可帮落子；false=消除候选
        val eliminations: List<Pair<Int, Int>> = emptyList() // (格子, 待删数字)
    )

    private fun rc(i: Int) = "第 ${i / 9 + 1} 行第 ${i % 9 + 1} 列"

    private fun bitOf(d: Int) = SudokuEngine.bitOf(d)

    /** 每个格子的全部关联格（同行/列/宫），惰性预计算。 */
    private val peers: List<List<Int>> by lazy {
        (0 until 81).map { i ->
            val r = SudokuEngine.rowOf(i)
            val c = SudokuEngine.colOf(i)
            val b = SudokuEngine.boxOf(i)
            (0 until 81).filter {
                it != i && (SudokuEngine.rowOf(it) == r ||
                    SudokuEngine.colOf(it) == c || SudokuEngine.boxOf(it) == b)
            }
        }
    }

    /** 27 个单位，按 宫 → 行 → 列 排序（新手先看宫更容易）。 */
    private val namedUnits: List<Pair<String, List<Int>>> by lazy {
        val list = ArrayList<Pair<String, List<Int>>>(27)
        for (b in 0 until 9) {
            val br = (b / 3) * 3
            val bc = (b % 3) * 3
            list.add("第 ${b + 1} 宫" to (0 until 9).map { (br + it / 3) * 9 + bc + it % 3 })
        }
        for (r in 0 until 9) list.add("第 ${r + 1} 行" to (0 until 9).map { r * 9 + it })
        for (c in 0 until 9) list.add("第 ${c + 1} 列" to (0 until 9).map { it * 9 + c })
        list
    }

    private val boxes: List<List<Int>> by lazy { namedUnits.take(9).map { it.second } }

    /** 找出当前最值得教的一步；null 表示没有可教的基础技巧。 */
    fun findHint(game: Game): Hint? {
        val board = IntArray(81) { game.valueAt(it) }

        findMistakeHint(game, board)?.let { return it }
        findNakedSingle(board)?.let { return it }
        findHiddenSingle(board)?.let { return it }
        findNakedPair(game, board)?.let { return it }
        findPointing(game, board)?.let { return it }
        return null
    }

    // ---------- 0. 纠错 ----------

    private fun findMistakeHint(game: Game, board: IntArray): Hint? {
        for (i in 0 until 81) {
            if (!game.isWrong(i)) continue
            val d = game.entries[i]
            val conflict = peers[i].firstOrNull { board[it] == d }
            val why = if (conflict != null) {
                "${rc(conflict)}也是 $d，同一行、列、宫内数字不能重复。"
            } else {
                "这个数字与全局答案矛盾。"
            }
            return Hint(
                technique = "先修正错误",
                explanation = "${rc(i)}填的 $d 有问题：$why\n\n" +
                    "点击「帮我填入」直接改成正确数字，或者自己擦掉重填。",
                targets = listOf(i),
                related = listOfNotNull(conflict),
                digit = game.solution[i],
                place = true
            )
        }
        return null
    }

    // ---------- 1. 唯余法（裸单元） ----------

    private fun findNakedSingle(board: IntArray): Hint? {
        for (i in 0 until 81) {
            if (board[i] != 0) continue
            val mask = SudokuEngine.candidateMask(board, i)
            if (Integer.bitCount(mask) == 1) {
                val d = Integer.numberOfTrailingZeros(mask)
                val excluded = (1..9).filter { it != d && peers[i].any { p -> board[p] == it } }
                return Hint(
                    technique = "唯余法",
                    explanation = "看${rc(i)}：它所在的行、列、宫已经出现了 " +
                        "${excluded.joinToString("、")}。\n\n" +
                        "1–9 里只剩 $d 没出现过，所以这一格只能填 $d。",
                    targets = listOf(i),
                    related = peers[i].filter { board[it] != 0 },
                    digit = d,
                    place = true
                )
            }
        }
        return null
    }

    // ---------- 2. 摒除法（隐性单元） ----------

    private fun findHiddenSingle(board: IntArray): Hint? {
        for ((unitName, unit) in namedUnits) {
            for (d in 1..9) {
                if (unit.any { board[it] == d }) continue
                val places = unit.filter {
                    board[it] == 0 && SudokuEngine.candidateMask(board, it) and bitOf(d) != 0
                }
                if (places.size == 1) {
                    val i = places[0]
                    return Hint(
                        technique = "摒除法",
                        explanation = "在$unitName 里找数字 $d：其它空格所在的行或列上已经出现了 $d，" +
                            "都不能再放。\n\n整${unitName}只有${rc(i)}能放 $d，所以它就是 $d。",
                        targets = listOf(i),
                        related = unit.filter { it != i },
                        digit = d,
                        place = true
                    )
                }
            }
        }
        return null
    }

    // ---------- 3. 数对占位法 ----------

    private fun findNakedPair(game: Game, board: IntArray): Hint? {
        for ((unitName, unit) in namedUnits) {
            val pairs = unit.filter {
                board[it] == 0 && Integer.bitCount(SudokuEngine.candidateMask(board, it)) == 2
            }
            for (x in pairs.indices) {
                for (y in x + 1 until pairs.size) {
                    val a = pairs[x]
                    val b = pairs[y]
                    val mask = SudokuEngine.candidateMask(board, a)
                    if (mask != SudokuEngine.candidateMask(board, b)) continue
                    val digits = (1..9).filter { mask and bitOf(it) != 0 }
                    // 只有草稿里确实存在可删标记时，这个技巧才"看得见效果"
                    val elims = mutableListOf<Pair<Int, Int>>()
                    for (i in unit) {
                        if (i == a || i == b || board[i] != 0) continue
                        for (d in digits) {
                            if (game.notes[i] and bitOf(d) != 0) elims += i to d
                        }
                    }
                    if (elims.isNotEmpty()) {
                        return Hint(
                            technique = "数对占位法",
                            explanation = "${rc(a)}和${rc(b)}的候选数都只有 ${digits[0]} 和 ${digits[1]}。" +
                                "无论谁是谁，这两个数字必定占满这两格。\n\n" +
                                "所以$unitName 的其它空格都不可能是 ${digits[0]} 或 ${digits[1]}，" +
                                "可以把这些候选删掉（共 ${elims.size} 个）。",
                            targets = listOf(a, b),
                            related = elims.map { it.first }.distinct(),
                            digit = digits[0],
                            place = false,
                            eliminations = elims
                        )
                    }
                }
            }
        }
        return null
    }

    // ---------- 4. 区块摒除法（宫内候选锁定到一行/列） ----------

    private fun findPointing(game: Game, board: IntArray): Hint? {
        for (b in 0 until 9) {
            val cells = boxes[b]
            for (d in 1..9) {
                if (cells.any { board[it] == d }) continue
                val places = cells.filter {
                    board[it] == 0 && SudokuEngine.candidateMask(board, it) and bitOf(d) != 0
                }
                if (places.size !in 2..3) continue

                val rows = places.map { SudokuEngine.rowOf(it) }.distinct()
                if (rows.size == 1) {
                    val r = rows[0]
                    val elims = (0 until 9)
                        .map { r * 9 + it }
                        .filter { SudokuEngine.boxOf(it) != b && board[it] == 0 }
                        .filter { game.notes[it] and bitOf(d) != 0 }
                        .map { it to d }
                    if (elims.isNotEmpty()) {
                        return Hint(
                            technique = "区块摒除法",
                            explanation = "第 ${b + 1} 宫里，$d 的候选位置全部落在第 ${r + 1} 行，" +
                                "说明这一宫的 $d 一定在这一行。\n\n" +
                                "所以第 ${r + 1} 行其它宫的格子都不可能是 $d，" +
                                "可以删掉这些候选（共 ${elims.size} 个）。",
                            targets = places,
                            related = elims.map { it.first }.distinct(),
                            digit = d,
                            place = false,
                            eliminations = elims
                        )
                    }
                }

                val cols = places.map { SudokuEngine.colOf(it) }.distinct()
                if (cols.size == 1) {
                    val c = cols[0]
                    val elims = (0 until 9)
                        .map { it * 9 + c }
                        .filter { SudokuEngine.boxOf(it) != b && board[it] == 0 }
                        .filter { game.notes[it] and bitOf(d) != 0 }
                        .map { it to d }
                    if (elims.isNotEmpty()) {
                        return Hint(
                            technique = "区块摒除法",
                            explanation = "第 ${b + 1} 宫里，$d 的候选位置全部落在第 ${c + 1} 列，" +
                                "说明这一宫的 $d 一定在这一列。\n\n" +
                                "所以第 ${c + 1} 列其它宫的格子都不可能是 $d，" +
                                "可以删掉这些候选（共 ${elims.size} 个）。",
                            targets = places,
                            related = elims.map { it.first }.distinct(),
                            digit = d,
                            place = false,
                            eliminations = elims
                        )
                    }
                }
            }
        }
        return null
    }

    /** 没有可教步骤时的技巧图鉴文案。 */
    const val GUIDE_TEXT =
        "当前局面没有能一步看穿的基础技巧，可以：\n" +
            "1. 开启「⚡ 自动草稿」后再点提示，就能学到数对、区块等消除技巧；\n" +
            "2. 对照下面的技巧图鉴自己找一找。\n\n" +
            "📖 技巧图鉴（由易到难）\n" +
            "• 唯余法：一格所在行/列/宫已出现 8 个不同数字，只能填剩下的那个。\n" +
            "• 摒除法：某行/列/宫中，一个数字只剩一个可放的位置。\n" +
            "• 数对占位：两格候选完全相同且只有两个数，这两数必占这两格，可删除同单位其它格中的它们。\n" +
            "• 区块摒除：宫内某数字的候选全在一行/列，可删除该行/列其它宫中的该数字。"
}
