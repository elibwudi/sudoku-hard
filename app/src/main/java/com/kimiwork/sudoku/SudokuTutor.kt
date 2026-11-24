package com.kimiwork.sudoku

/**
 * 新手指导引擎：分析当前盘面，按由易到难的顺序找出下一步逻辑，
 * 并用「观察 → 推理 → 结论」三段式中文讲解引导新手。
 *
 * 草稿语义（保证提示永远正确）：
 * - 自动草稿开启：草稿视为完整候选集（App 填充基线 + 只删不加），提示基于草稿推理，
 *   可形成提示链；推理前先"体检"——发现非法标记或误删的正确候选时优先提示修正。
 * - 自动草稿关闭：草稿视为个人备注（可能不完整），提示只按盘面合法候选推理，
 *   绝不信任草稿内容，因此不会给出错误提示。
 * - 所有落子提示都与唯一解核对，宁缺毋错。
 *
 * 技巧顺序：纠错 → 草稿体检 → 唯余/排除收尾 → 摒除 → 数对占位 → 隐性数对 → 区块摒除 → 行列区块。
 */
object SudokuTutor {

    data class Hint(
        val technique: String,                          // 技巧名称
        val explanation: String,                        // 中文讲解
        val targets: List<Int>,                       // 目标格（主高亮）
        val related: List<Int>,                       // 关联格（次高亮）
        val digit: Int,                                 // 涉及数字
        val place: Boolean,                             // true=可帮落子；false=消除/修正
        val eliminations: List<Pair<Int, Int>> = emptyList(), // (格子, 待删数字)
        val repairCell: Int = -1                        // >=0 时：恢复该格完整候选
    )

    private fun rc(i: Int) = "第 ${i / 9 + 1} 行第 ${i % 9 + 1} 列"

    private fun bitOf(d: Int) = SudokuEngine.bitOf(d)

    private fun digitsOf(mask: Int) = (1..9).filter { mask and bitOf(it) != 0 }

    private fun notesText(game: Game, i: Int): String =
        digitsOf(game.notes[i]).joinToString("、")

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

    /**
     * 有效候选：自动草稿开启时信任草稿（完整候选语义，可链式推理）；
     * 否则只按盘面计算（手动草稿可能不完整，不可作为推理依据）。
     */
    private fun effMask(game: Game, board: IntArray, i: Int): Int = when {
        board[i] != 0 -> 0
        game.autoNotes && game.notes[i] != 0 -> game.notes[i]
        else -> SudokuEngine.candidateMask(board, i)
    }

    /** 找出当前最值得教的一步；null 表示没有可教的技巧。 */
    fun findHint(game: Game): Hint? {
        val board = IntArray(81) { game.valueAt(it) }

        findMistakeHint(game, board)?.let { return it }
        findNotesProblem(game, board)?.let { return it }
        findNakedSingle(game, board)?.let { return it }
        findHiddenSingle(game, board)?.let { return it }
        findNakedPair(game, board)?.let { return it }
        findHiddenPair(game, board)?.let { return it }
        findPointing(game, board)?.let { return it }
        findClaiming(game, board)?.let { return it }
        return null
    }

    // ---------- 0. 纠错（填错的数字） ----------

    private fun findMistakeHint(game: Game, board: IntArray): Hint? {
        for (i in 0 until 81) {
            if (!game.isWrong(i)) continue
            val d = game.entries[i]
            val conflict = peers[i].firstOrNull { board[it] == d }
            val why = if (conflict != null) {
                "${rc(conflict)}也是 $d，同一行、列、宫内数字不能重复。"
            } else {
                "它与全局答案矛盾。"
            }
            return Hint(
                technique = "先修正错误",
                explanation = "【观察】${rc(i)}填了 $d。\n" +
                    "【推理】$why\n" +
                    "【结论】这一格不是 $d。点击「帮我填入」改成正确数字，或者自己擦掉重填。",
                targets = listOf(i),
                related = listOfNotNull(conflict),
                digit = game.solution[i],
                place = true
            )
        }
        return null
    }

    // ---------- 0.5 草稿体检（仅自动草稿模式：草稿必须是完整候选集） ----------

    private fun findNotesProblem(game: Game, board: IntArray): Hint? {
        if (!game.autoNotes) return null
        for (i in 0 until 81) {
            if (board[i] != 0 || game.notes[i] == 0) continue
            val legal = SudokuEngine.candidateMask(board, i)

            // (a) 草稿里标了当前盘面下非法的数字
            val illegal = game.notes[i] and legal.inv() and 0x3FE
            if (illegal != 0) {
                val d = Integer.numberOfTrailingZeros(illegal and -illegal)
                val src = peers[i].firstOrNull { board[it] == d }
                val reason = if (src != null) "${rc(src)}已经是 $d，同一行/列/宫不能重复" else "$d 与盘面冲突"
                return Hint(
                    technique = "草稿有误",
                    explanation = "【观察】${rc(i)}的草稿里标了 $d。\n" +
                        "【推理】$reason，所以 $d 不可能放在这一格。\n" +
                        "【结论】这个候选应当删掉。点击「帮我删除」去掉它。",
                    targets = listOf(i),
                    related = listOfNotNull(src),
                    digit = d,
                    place = false,
                    eliminations = listOf(i to d)
                )
            }

            // (b) 正确的候选被误删（草稿不含答案）
            if (game.notes[i] and bitOf(game.solution[i]) == 0) {
                return Hint(
                    technique = "草稿不完整",
                    explanation = "【观察】${rc(i)}的草稿只剩 ${notesText(game, i)}。\n" +
                        "【推理】这些候选里少了真正可能填的数字——之前的编辑把它误删了。" +
                        "以不完整的草稿继续推理，结论会出错。\n" +
                        "【结论】点击「帮我修正」，把这一格恢复为完整候选，再重新排除。",
                    targets = listOf(i),
                    related = emptyList(),
                    digit = game.solution[i],
                    place = false,
                    repairCell = i
                )
            }
        }
        return null
    }

    // ---------- 1. 唯余法 / 排除收尾 ----------

    private fun findNakedSingle(game: Game, board: IntArray): Hint? {
        for (i in 0 until 81) {
            if (board[i] != 0) continue
            val mask = effMask(game, board, i)
            if (Integer.bitCount(mask) != 1) continue
            val d = Integer.numberOfTrailingZeros(mask)
            if (d != game.solution[i]) continue // 宁缺毋错
            val rawMask = SudokuEngine.candidateMask(board, i)

            if (game.autoNotes && game.notes[i] != 0 && Integer.bitCount(rawMask) > 1) {
                return Hint(
                    technique = "排除收尾",
                    explanation = "【观察】${rc(i)}的草稿只剩 $d。\n" +
                        "【推理】经过前面的排除，其它候选都被证明不可能了；" +
                        "候选只剩一个，这格就确定了。\n" +
                        "【结论】${rc(i)} = $d。这就是持续删减候选的价值。",
                    targets = listOf(i),
                    related = peers[i].filter { board[it] != 0 },
                    digit = d,
                    place = true
                )
            }

            val excluded = (1..9).filter { it != d && peers[i].any { p -> board[p] == it } }
            return Hint(
                technique = "唯余法",
                explanation = "【观察】${rc(i)}还是空格，看它所在的行、列、宫。\n" +
                    "【推理】这些位置已经出现了 ${excluded.joinToString("、")}，" +
                    "而每格必须填 1–9 中不重复的数字。\n" +
                    "【结论】只剩 $d 没出现过，${rc(i)} = $d。",
                targets = listOf(i),
                related = peers[i].filter { board[it] != 0 },
                digit = d,
                place = true
            )
        }
        return null
    }

    // ---------- 2. 摒除法（隐性单元） ----------

    private fun findHiddenSingle(game: Game, board: IntArray): Hint? {
        for ((unitName, unit) in namedUnits) {
            for (d in 1..9) {
                if (unit.any { board[it] == d }) continue
                val places = unit.filter {
                    board[it] == 0 && effMask(game, board, it) and bitOf(d) != 0
                }
                if (places.size != 1) continue
                val i = places[0]
                if (d != game.solution[i]) continue // 宁缺毋错

                // 逐个说明其它空格为什么被排除
                val reasons = unit.filter { it != i && board[it] == 0 }.mapNotNull { cell ->
                    peers[cell].firstOrNull { board[it] == d }?.let { src ->
                        val rel = when {
                            SudokuEngine.rowOf(src) == SudokuEngine.rowOf(cell) -> "同行"
                            SudokuEngine.colOf(src) == SudokuEngine.colOf(cell) -> "同列"
                            else -> "同宫"
                        }
                        "${rc(cell)}不能放（${rel}的${rc(src)}已是 $d）"
                    }
                }
                val shown = reasons.take(5)
                val more = if (reasons.size > 5) "\n……等共 ${reasons.size} 处被排除" else ""
                return Hint(
                    technique = "摒除法",
                    explanation = "【观察】$unitName 里还没有 $d。\n" +
                        "【推理】逐个看空格：\n${shown.joinToString("；\n")}$more\n" +
                        "整$unitName 只有${rc(i)}能放 $d。\n" +
                        "【结论】${rc(i)} = $d。",
                    targets = listOf(i),
                    related = unit.filter { it != i },
                    digit = d,
                    place = true
                )
            }
        }
        return null
    }

    // ---------- 3. 数对占位法 ----------

    private fun findNakedPair(game: Game, board: IntArray): Hint? {
        for ((unitName, unit) in namedUnits) {
            val pairs = unit.filter {
                board[it] == 0 && Integer.bitCount(effMask(game, board, it)) == 2
            }
            for (x in pairs.indices) {
                for (y in x + 1 until pairs.size) {
                    val a = pairs[x]
                    val b = pairs[y]
                    val mask = effMask(game, board, a)
                    if (mask != effMask(game, board, b)) continue
                    val digits = digitsOf(mask)
                    // 消除必须基于完整候选语义，否则看不到效果
                    val elims = mutableListOf<Pair<Int, Int>>()
                    for (i in unit) {
                        if (i == a || i == b || board[i] != 0) continue
                        for (d in digits) {
                            if (d != game.solution[i] && game.notes[i] and bitOf(d) != 0) {
                                elims += i to d
                            }
                        }
                    }
                    if (elims.isEmpty()) continue
                    return Hint(
                        technique = "数对占位法",
                        explanation = "【观察】${rc(a)}和${rc(b)}的候选一模一样，都只有 ${digits[0]}、${digits[1]}。\n" +
                            "【推理】如果 ${digits[0]} 不在这两格，两格就都是 ${digits[1]}——矛盾。" +
                            "所以 ${digits[0]} 和 ${digits[1]} 必定占满这两格，只是顺序未定。\n" +
                            "【结论】$unitName 的其它空格都不可能是 ${digits[0]} 或 ${digits[1]}，" +
                            "可以删掉这些候选（共 ${elims.size} 个）。删完再点提示，往往能接出下一步。",
                        targets = listOf(a, b),
                        related = elims.map { it.first }.distinct(),
                        digit = digits[0],
                        place = false,
                        eliminations = elims
                    )
                }
            }
        }
        return null
    }

    // ---------- 4. 隐性数对法 ----------

    private fun findHiddenPair(game: Game, board: IntArray): Hint? {
        for ((unitName, unit) in namedUnits) {
            val pos = HashMap<Int, List<Int>>()
            for (d in 1..9) {
                if (unit.any { board[it] == d }) continue
                val cells = unit.filter {
                    board[it] == 0 && effMask(game, board, it) and bitOf(d) != 0
                }
                if (cells.size == 2) pos[d] = cells
            }
            val digits = pos.keys.toList()
            for (x in digits.indices) {
                for (y in x + 1 until digits.size) {
                    val d1 = digits[x]
                    val d2 = digits[y]
                    if (pos[d1] != pos[d2]) continue
                    val (a, b) = pos[d1]!!
                    val elims = mutableListOf<Pair<Int, Int>>()
                    for (i in listOf(a, b)) {
                        for (d in 1..9) {
                            if (d != d1 && d != d2 && d != game.solution[i] &&
                                game.notes[i] and bitOf(d) != 0
                            ) {
                                elims += i to d
                            }
                        }
                    }
                    if (elims.isEmpty()) continue
                    return Hint(
                        technique = "隐性数对法",
                        explanation = "【观察】在$unitName 里，$d1 只能放在${rc(a)}或${rc(b)}，$d2 也一样。\n" +
                            "【推理】$d1、$d2 必定占满这两格，其它数字挤不进来。\n" +
                            "【结论】这两格里的其它候选（${notesText(game, a)} / ${notesText(game, b)}）" +
                            "都不是 $d1、$d2 的都可以删掉（共 ${elims.size} 个）。删完再点提示接着看。",
                        targets = listOf(a, b),
                        related = unit.filter { it != a && it != b && board[it] == 0 },
                        digit = d1,
                        place = false,
                        eliminations = elims
                    )
                }
            }
        }
        return null
    }

    // ---------- 5. 区块摒除法（宫内候选锁定到一行/列） ----------

    private fun findPointing(game: Game, board: IntArray): Hint? {
        for (b in 0 until 9) {
            val cells = boxes[b]
            for (d in 1..9) {
                if (cells.any { board[it] == d }) continue
                val places = cells.filter {
                    board[it] == 0 && effMask(game, board, it) and bitOf(d) != 0
                }
                if (places.size !in 2..3) continue

                val rows = places.map { SudokuEngine.rowOf(it) }.distinct()
                if (rows.size == 1) {
                    val r = rows[0]
                    val elims = (0 until 9)
                        .map { r * 9 + it }
                        .filter { SudokuEngine.boxOf(it) != b && board[it] == 0 }
                        .filter { d != game.solution[it] && game.notes[it] and bitOf(d) != 0 }
                        .map { it to d }
                    if (elims.isNotEmpty()) {
                        return Hint(
                            technique = "区块摒除法",
                            explanation = "【观察】第 ${b + 1} 宫里，$d 的候选全部落在第 ${r + 1} 行" +
                                "（${places.joinToString("、") { rc(it) }}）。\n" +
                                "【推理】这一宫的 $d 不管最终在哪格，都占掉了第 ${r + 1} 行的 $d。\n" +
                                "【结论】第 ${r + 1} 行其它宫的格子都不可能是 $d，" +
                                "删掉这些候选（共 ${elims.size} 个）。",
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
                        .filter { d != game.solution[it] && game.notes[it] and bitOf(d) != 0 }
                        .map { it to d }
                    if (elims.isNotEmpty()) {
                        return Hint(
                            technique = "区块摒除法",
                            explanation = "【观察】第 ${b + 1} 宫里，$d 的候选全部落在第 ${c + 1} 列" +
                                "（${places.joinToString("、") { rc(it) }}）。\n" +
                                "【推理】这一宫的 $d 不管最终在哪格，都占掉了第 ${c + 1} 列的 $d。\n" +
                                "【结论】第 ${c + 1} 列其它宫的格子都不可能是 $d，" +
                                "删掉这些候选（共 ${elims.size} 个）。",
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

    // ---------- 6. 行列区块法（行/列候选锁定到一宫） ----------

    private fun findClaiming(game: Game, board: IntArray): Hint? {
        for (r in 0 until 9) {
            val rowCells = (0 until 9).map { r * 9 + it }
            for (d in 1..9) {
                if (rowCells.any { board[it] == d }) continue
                val places = rowCells.filter {
                    board[it] == 0 && effMask(game, board, it) and bitOf(d) != 0
                }
                if (places.size !in 2..3) continue
                val bs = places.map { SudokuEngine.boxOf(it) }.distinct()
                if (bs.size != 1) continue
                val b = bs[0]
                val elims = boxes[b]
                    .filter { SudokuEngine.rowOf(it) != r && board[it] == 0 }
                    .filter { d != game.solution[it] && game.notes[it] and bitOf(d) != 0 }
                    .map { it to d }
                if (elims.isNotEmpty()) {
                    return Hint(
                        technique = "行列区块法",
                        explanation = "【观察】第 ${r + 1} 行里，$d 的候选全部落在第 ${b + 1} 宫" +
                            "（${places.joinToString("、") { rc(it) }}）。\n" +
                            "【推理】这一行的 $d 一定在这一宫，所以这一宫其它行放不下 $d。\n" +
                            "【结论】第 ${b + 1} 宫其它行的格子删掉候选 $d（共 ${elims.size} 个）。",
                        targets = places,
                        related = elims.map { it.first }.distinct(),
                        digit = d,
                        place = false,
                        eliminations = elims
                    )
                }
            }
        }
        for (c in 0 until 9) {
            val colCells = (0 until 9).map { it * 9 + c }
            for (d in 1..9) {
                if (colCells.any { board[it] == d }) continue
                val places = colCells.filter {
                    board[it] == 0 && effMask(game, board, it) and bitOf(d) != 0
                }
                if (places.size !in 2..3) continue
                val bs = places.map { SudokuEngine.boxOf(it) }.distinct()
                if (bs.size != 1) continue
                val b = bs[0]
                val elims = boxes[b]
                    .filter { SudokuEngine.colOf(it) != c && board[it] == 0 }
                    .filter { d != game.solution[it] && game.notes[it] and bitOf(d) != 0 }
                    .map { it to d }
                if (elims.isNotEmpty()) {
                    return Hint(
                        technique = "行列区块法",
                        explanation = "【观察】第 ${c + 1} 列里，$d 的候选全部落在第 ${b + 1} 宫" +
                            "（${places.joinToString("、") { rc(it) }}）。\n" +
                            "【推理】这一列的 $d 一定在这一宫，所以这一宫其它列放不下 $d。\n" +
                            "【结论】第 ${b + 1} 宫其它列的格子删掉候选 $d（共 ${elims.size} 个）。",
                        targets = places,
                        related = elims.map { it.first }.distinct(),
                        digit = d,
                        place = false,
                        eliminations = elims
                    )
                }
            }
        }
        return null
    }

    /** 没有可教步骤时的技巧图鉴文案。 */
    const val GUIDE_TEXT =
        "当前局面没有能一步看穿的技巧了，可以：\n" +
            "1. 确认「⚡ 自动草稿」已开启——消除类技巧都要在完整草稿上进行；\n" +
            "2. 对照下面的技巧图鉴自己找一找，实在不行就试填一个候选，错了用撤销回退。\n\n" +
            "📖 技巧图鉴（由易到难）\n" +
            "• 唯余法：一格所在行/列/宫已出现 8 个不同数字，只能填剩下的那个。\n" +
            "• 排除收尾：候选被删到只剩一个，即可落子。\n" +
            "• 摒除法：某行/列/宫中，一个数字只剩一个可放的位置。\n" +
            "• 数对占位：两格候选完全相同且只有两个数，可删除同单位其它格中的这两个数。\n" +
            "• 隐性数对：两个数字在某单位里只能放在同样两格，这两格的其它候选都可删。\n" +
            "• 区块摒除：宫内某数字候选全在一行/列，可删该行/列其它宫的候选。\n" +
            "• 行列区块：行/列内某数字候选全在一宫，可删该宫其它行/列的候选。"
}
