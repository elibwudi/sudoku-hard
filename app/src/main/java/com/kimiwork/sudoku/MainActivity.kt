package com.kimiwork.sudoku

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var boardView: SudokuBoardView
    private lateinit var timerText: TextView
    private lateinit var mistakesText: TextView
    private lateinit var loadingText: TextView
    private lateinit var undoButton: Button
    private lateinit var eraseButton: Button
    private lateinit var notesButton: Button
    private lateinit var autoButton: Button
    private lateinit var newGameButton: Button
    private lateinit var padButtons: List<Button>

    private var game: Game? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("sudoku_save", MODE_PRIVATE) }

    private val ticker = object : Runnable {
        override fun run() {
            val g = game
            if (g != null && !g.finished) {
                g.seconds++
                timerText.text = formatTime(g.seconds)
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        boardView = findViewById(R.id.boardView)
        timerText = findViewById(R.id.timerText)
        mistakesText = findViewById(R.id.mistakesText)
        loadingText = findViewById(R.id.loadingText)
        undoButton = findViewById(R.id.btnUndo)
        eraseButton = findViewById(R.id.btnErase)
        notesButton = findViewById(R.id.btnNotes)
        autoButton = findViewById(R.id.btnAuto)
        newGameButton = findViewById(R.id.btnNewGame)

        padButtons = (1..9).map { d ->
            findViewById<Button>(resources.getIdentifier("btnPad$d", "id", packageName)).apply {
                text = d.toString()
                setOnClickListener { onDigit(d) }
            }
        }

        boardView.onCellSelected = { idx ->
            game?.let {
                it.selected = idx
                boardView.invalidate()
            }
        }

        undoButton.setOnClickListener { if (game?.undo() == true) refresh() }
        eraseButton.setOnClickListener { if (game?.erase() == true) refresh() }
        notesButton.setOnClickListener {
            game?.let {
                it.notesMode = !it.notesMode
                refresh()
            }
        }
        autoButton.setOnClickListener {
            game?.let {
                it.toggleAutoNotes()
                refresh()
            }
        }
        newGameButton.setOnClickListener { confirmNewGame() }

        val saved = prefs.getString(KEY_SAVE, null)
        val restored = saved?.let { Game.deserialize(it) }
        if (restored != null) {
            game = restored
            refresh()
        } else {
            startNewGame()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        saveGame()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    // ---------- 游戏流程 ----------

    private fun onDigit(d: Int) {
        val g = game ?: return
        if (g.onDigit(d)) {
            refresh()
            if (g.finished) onWin()
        }
    }

    private fun startNewGame() {
        setControlsEnabled(false)
        loadingText.visibility = View.VISIBLE
        boardView.game = null
        boardView.invalidate()
        // 艰难题生成需要回溯验证唯一解，放到后台线程
        Thread {
            val g = Game.newHard()
            runOnUiThread {
                game = g
                loadingText.visibility = View.GONE
                setControlsEnabled(true)
                refresh()
            }
        }.start()
    }

    private fun confirmNewGame() {
        AlertDialog.Builder(this)
            .setTitle(R.string.new_game_title)
            .setMessage(R.string.new_game_message)
            .setPositiveButton(R.string.new_game_confirm) { _, _ -> startNewGame() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onWin() {
        val g = game ?: return
        prefs.edit().remove(KEY_SAVE).apply() // 通关后清档，下次启动开新局
        AlertDialog.Builder(this)
            .setTitle(R.string.win_title)
            .setMessage(getString(R.string.win_message, formatTime(g.seconds), g.mistakes))
            .setPositiveButton(R.string.win_next) { _, _ -> startNewGame() }
            .setNegativeButton(R.string.win_stay, null)
            .setCancelable(false)
            .show()
    }

    // ---------- 界面刷新 ----------

    private fun refresh() {
        val g = game ?: return
        boardView.game = g
        boardView.invalidate()
        timerText.text = formatTime(g.seconds)
        mistakesText.text = getString(R.string.mistakes_fmt, g.mistakes)
        setToggleState(notesButton, g.notesMode)
        setToggleState(autoButton, g.autoNotes)
    }

    private fun setToggleState(button: Button, active: Boolean) {
        button.setBackgroundResource(
            if (active) R.drawable.bg_control_active else R.drawable.bg_control
        )
        button.setTextColor(
            if (active) 0xFFFFFFFF.toInt() else 0xFF212433.toInt()
        )
    }

    private fun setControlsEnabled(enabled: Boolean) {
        undoButton.isEnabled = enabled
        eraseButton.isEnabled = enabled
        notesButton.isEnabled = enabled
        autoButton.isEnabled = enabled
        newGameButton.isEnabled = enabled
        padButtons.forEach { it.isEnabled = enabled }
    }

    private fun formatTime(sec: Int): String =
        String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)

    private fun saveGame() {
        val g = game ?: return
        if (g.finished) return
        prefs.edit().putString(KEY_SAVE, g.serialize()).apply()
    }

    companion object {
        private const val KEY_SAVE = "current_game"
    }
}
