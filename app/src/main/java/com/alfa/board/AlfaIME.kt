package com.alfa.board

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class AlfaIME : InputMethodService() {

    companion object {
        const val PREFS = "alfa_prefs"
        const val PREF_DARK = "dark"
        const val PREF_GROQ = "groq_key"
        const val PREF_LOG = "keylog"
        const val PREF_NUMROW = "numrow"
    }

    enum class Mode { ENG, BN, BANGLISH, EMOJI, SYMBOLS }

    private var mode = Mode.ENG
    private var shift = false
    private var capsLock = false
    private var shiftPressTime = 0L
    private val banglishBuf = StringBuilder()
    private lateinit var prefs: SharedPreferences
    private lateinit var logMgr: LogManager
    private lateinit var clipMgr: ClipManager
    private var mediaRec: MediaRecorder? = null
    private var recording = false
    private var rootView: LinearLayout? = null

    // Delete repeat handler
    private val deleteHandler = Handler(Looper.getMainLooper())
    private var deleteRunnable: Runnable? = null

    // Theme colors
    private var cBg = 0; private var cKey = 0; private var cSp = 0
    private var cTxt = 0; private var cAcc = 0; private var cAccTxt = 0
    private var cBar = 0

    // Swipe tracking
    private var swipeStartX = 0f

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        logMgr = LogManager(this)
        clipMgr = ClipManager(this)
    }

    override fun onCreateInputView(): View {
        loadTheme()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        rootView = root
        buildKeyboard()
        return root
    }

    override fun onWindowShown() {
        super.onWindowShown()
        loadTheme()
        buildKeyboard()
    }

    private fun loadTheme() {
        val dark = prefs.getBoolean(PREF_DARK, false)
        cBg  = if (dark) Color.parseColor("#1A1A2E") else Color.parseColor("#D1D5DB")
        cKey = if (dark) Color.parseColor("#2D2D44") else Color.WHITE
        cSp  = if (dark) Color.parseColor("#4A4A6A") else Color.parseColor("#B0B8C4")
        cTxt = if (dark) Color.WHITE else Color.parseColor("#1F2937")
        cAcc = Color.parseColor("#1A73E8")
        cAccTxt = Color.WHITE
        cBar = if (dark) Color.parseColor("#12122A") else Color.parseColor("#E8ECF0")
    }

    private fun buildKeyboard() {
        val root = rootView ?: return
        root.removeAllViews()
        root.setBackgroundColor(cBg)

        if (mode == Mode.EMOJI) {
            root.addView(buildToolbar())
            root.addView(buildEmojiPanel())
            return
        }

        root.addView(buildToolbar())
        if (prefs.getBoolean(PREF_NUMROW, true)) root.addView(buildNumRow())

        when (mode) {
            Mode.ENG, Mode.BANGLISH -> buildQwerty(root)
            Mode.BN -> buildBangla(root)
            Mode.SYMBOLS -> buildSymbols(root)
            else -> buildQwerty(root)
        }

        if (mode == Mode.BANGLISH) root.addView(buildBanglishBar())
        root.addView(buildBottomRow())
    }

    // ══════════════════════════════════════════════════════════
    // TOOLBAR (emoji, clipboard, settings, mic, ...)
    // ══════════════════════════════════════════════════════════
    private fun buildToolbar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(cBar)
        bar.setPadding(dp(4), dp(2), dp(4), dp(2))
        bar.gravity = Gravity.CENTER_VERTICAL

        // Emoji button
        bar.addView(toolBtn("☺", "Emoji") {
            mode = if (mode == Mode.EMOJI) Mode.ENG else Mode.EMOJI
            buildKeyboard()
        })

        // Clipboard
        bar.addView(toolBtn("⊞", "Clipboard") { showClipboard() })

        // Spacer
        val sp = View(this)
        sp.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        bar.addView(sp)

        // Mic
        val micLabel = if (recording) "■" else "♪"
        bar.addView(toolBtn(micLabel, if (recording) "Stop" else "Voice") { toggleVoice() })

        // Settings
        bar.addView(toolBtn("⚙", "Settings") {
            val i = Intent(this, SettingsActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        })

        // More (...)
        bar.addView(toolBtn("⋯", "More") {
            Toast.makeText(this, "Swipe spacebar to switch language", Toast.LENGTH_SHORT).show()
        })

        return bar
    }

    private fun toolBtn(icon: String, hint: String, action: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = icon
        tv.textSize = 18f
        tv.gravity = Gravity.CENTER
        tv.contentDescription = hint
        tv.setTextColor(cTxt)
        tv.setPadding(dp(10), dp(6), dp(10), dp(6))
        tv.setOnClickListener {
            tv.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
        return tv
    }

    // ══════════════════════════════════════════════════════════
    // NUMBER ROW
    // ══════════════════════════════════════════════════════════
    private fun buildNumRow(): LinearLayout {
        val nums = listOf("1","2","3","4","5","6","7","8","9","0")
        val alts = listOf("!","@","#","$","%","^","&","*","(",")") // long-press alts
        return row {
            for (i in nums.indices) {
                val tv = makeKey(nums[i], 1f)
                setupLongPressAlt(tv, nums[i], alts[i])
                addView(tv)
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // QWERTY
    // ══════════════════════════════════════════════════════════
    private fun buildQwerty(root: LinearLayout) {
        val r1 = listOf("q","w","e","r","t","y","u","i","o","p")
        val r2 = listOf("a","s","d","f","g","h","j","k","l")
        val r3 = listOf("z","x","c","v","b","n","m")

        // Alt chars for long press
        val alt1 = mapOf("q" to "1","w" to "2","e" to "3","r" to "4","t" to "5",
                         "y" to "6","u" to "7","i" to "8","o" to "9","p" to "0")
        val alt2 = mapOf("a" to "@","s" to "#","d" to "$","f" to "%","g" to "&",
                         "h" to "-","j" to "+","k" to "(","l" to ")")
        val alt3 = mapOf("z" to "~","x" to "×","c" to "©","v" to "√","b" to "°",
                         "n" to "ñ","m" to "µ")

        root.addView(row {
            for (k in r1) {
                val tv = letterKey(k)
                alt1[k]?.let { setupLongPressAlt(tv, if (isUpper()) k.uppercase() else k, it) }
                addView(tv)
            }
        })

        root.addView(row {
            addView(spacerV(0.5f))
            for (k in r2) {
                val tv = letterKey(k)
                alt2[k]?.let { setupLongPressAlt(tv, if (isUpper()) k.uppercase() else k, it) }
                addView(tv)
            }
            addView(spacerV(0.5f))
        })

        root.addView(row {
            // SHIFT key
            val shiftLabel = when {
                capsLock -> "⇪"
                shift -> "⬆"
                else -> "⇧"
            }
            val shiftKey = makeKey(shiftLabel, 1.3f, special = true)
            shiftKey.setTextColor(if (capsLock) cAcc else cTxt)
            shiftKey.setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - shiftPressTime < 400) {
                    // Double tap = caps lock
                    capsLock = !capsLock
                    shift = false
                } else {
                    if (!capsLock) shift = !shift
                }
                shiftPressTime = now
                buildKeyboard()
            }
            addView(shiftKey)

            for (k in r3) {
                val tv = letterKey(k)
                // Long press C=copy, V=paste, X=cut, Z=undo
                when (k) {
                    "c" -> setupLongPressAction(tv, "Copy") { copyText() }
                    "v" -> setupLongPressAction(tv, "Paste") { pasteText() }
                    "x" -> setupLongPressAction(tv, "Cut") { cutText() }
                    "z" -> setupLongPressAction(tv, "Undo") { undoText() }
                    else -> alt3[k]?.let { setupLongPressAlt(tv, if (isUpper()) k.uppercase() else k, it) }
                }
                addView(tv)
            }

            // Backspace with hold-to-repeat
            val bsKey = makeKey("⌫", 1.3f, special = true)
            setupDeleteKey(bsKey)
            addView(bsKey)
        })
    }

    private fun letterKey(k: String): TextView {
        val display = if (isUpper()) k.uppercase() else k
        val tv = makeKey(display, 1f)
        tv.setOnClickListener {
            tv.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showKeyPreview(tv, display)
            typeChar(if (isUpper()) k.uppercase() else k)
            if (shift && !capsLock) { shift = false; buildKeyboard() }
        }
        return tv
    }

    private fun isUpper() = shift || capsLock

    // ══════════════════════════════════════════════════════════
    // BANGLA
    // ══════════════════════════════════════════════════════════
    private fun buildBangla(root: LinearLayout) {
        val rows = listOf(
            listOf("অ","আ","ই","ঈ","উ","ঊ","এ","ও","ঔ","⌫"),
            listOf("া","ি","ী","ু","ূ","ে","ো","্","ং","↵"),
            listOf("ক","খ","গ","ঘ","চ","ছ","জ","ট","ত","থ"),
            listOf("দ","ধ","ন","প","ফ","ব","ম","য","র","ল"),
            listOf("শ","ষ","স","হ","ড","ণ","ৎ","ঃ","ঁ","।")
        )
        val bnNums = listOf("০","১","২","৩","৪","৫","৬","৭","৮","৯")

        for (rowData in rows) {
            root.addView(row {
                for (ch in rowData) when (ch) {
                    "⌫" -> { val tv = makeKey(ch, 1f, special = true); setupDeleteKey(tv); addView(tv) }
                    "↵" -> addView(spKey("↵", 1f) { enter() })
                    else -> {
                        val tv = makeKey(ch, 1f)
                        tv.setOnClickListener { typeChar(ch); showKeyPreview(tv, ch) }
                        addView(tv)
                    }
                }
            })
        }

        root.addView(row {
            for (n in bnNums) {
                val tv = makeKey(n, 1f)
                tv.setOnClickListener { typeChar(n) }
                addView(tv)
            }
        })
    }

    // ══════════════════════════════════════════════════════════
    // SYMBOLS PANEL
    // ══════════════════════════════════════════════════════════
    private fun buildSymbols(root: LinearLayout) {
        val syms = listOf(
            listOf("[","]","{","}","#","%","^","*","+","="),
            listOf("_","\\","|","~","<",">","€","£","¥","•"),
            listOf("!","@","?","/","'","\"",";",":","¡","¿"),
            listOf("-","(",")",",",".","\$","&","©","®","™")
        )
        for (rowData in syms) {
            root.addView(row {
                for (s in rowData) {
                    val tv = makeKey(s, 1f)
                    tv.setOnClickListener { typeChar(s); showKeyPreview(tv, s) }
                    addView(tv)
                }
            })
        }

        root.addView(row {
            addView(spKey("ABC", 2f) { mode = Mode.ENG; buildKeyboard() })
            val sp = makeKey("Space", 4f)
            sp.setOnClickListener { typeChar(" ") }
            addView(sp)
            addView(spKey("⌫", 2f, special = true) { deleteChar() })
        })
    }

    // ══════════════════════════════════════════════════════════
    // EMOJI PANEL
    // ══════════════════════════════════════════════════════════
    private fun buildEmojiPanel(): View {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL

        // Category tabs
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setBackgroundColor(cBar)
        val categories = listOf("Smileys","Hands","Hearts","Nature","Food","Travel","Objects")
        for (cat in categories) {
            val tv = TextView(this)
            tv.text = cat.take(4)
            tv.textSize = 10f
            tv.gravity = Gravity.CENTER
            tv.setTextColor(cTxt)
            tv.setPadding(dp(6), dp(6), dp(6), dp(6))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tv.layoutParams = lp
            tabs.addView(tv)
        }
        container.addView(tabs)

        // Emoji grid scroll
        val sv = ScrollView(this)
        sv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200))
        val grid = GridLayout(this)
        grid.columnCount = 8
        grid.setPadding(dp(4), dp(4), dp(4), dp(4))

        // Safe emoji for Android 6+
        val emojiList = getSafeEmoji()
        for (em in emojiList) {
            val tv = TextView(this)
            tv.text = em
            tv.textSize = 24f
            tv.gravity = Gravity.CENTER
            val glp = GridLayout.LayoutParams()
            glp.width = dp(40); glp.height = dp(40)
            glp.setMargins(dp(2), dp(2), dp(2), dp(2))
            tv.layoutParams = glp
            tv.setOnClickListener { typeChar(em) }
            grid.addView(tv)
        }
        sv.addView(grid)
        container.addView(sv)

        // Close emoji row
        val closeRow = LinearLayout(this)
        closeRow.orientation = LinearLayout.HORIZONTAL
        closeRow.setBackgroundColor(cBar)
        closeRow.setPadding(dp(4), dp(4), dp(4), dp(4))

        val closeBtn = makeKey("ABC", 2f, special = true)
        closeBtn.setOnClickListener { mode = Mode.ENG; buildKeyboard() }
        val delBtn = makeKey("⌫", 1f, special = true)
        setupDeleteKey(delBtn)
        val spaceBtn = makeKey("Space", 5f)
        spaceBtn.setOnClickListener { typeChar(" ") }

        closeRow.addView(closeBtn)
        closeRow.addView(spaceBtn)
        closeRow.addView(delBtn)
        container.addView(closeRow)

        return container
    }

    // ══════════════════════════════════════════════════════════
    // BANGLISH CONVERT BAR
    // ══════════════════════════════════════════════════════════
    private fun buildBanglishBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(cBar)
        bar.setPadding(dp(8), dp(4), dp(8), dp(4))
        bar.gravity = Gravity.CENTER_VERTICAL

        val hint = TextView(this)
        hint.text = if (banglishBuf.isNotEmpty()) "\"${banglishBuf}\" → AI Convert" else "Type in English → AI will convert to Bangla"
        hint.textSize = 11f
        hint.setTextColor(if (prefs.getBoolean(PREF_DARK, false)) Color.LTGRAY else Color.DKGRAY)
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        hint.layoutParams = lp

        val btn = makeKey("Convert ✦", 0f, special = true)
        btn.textSize = 12f
        btn.background = roundBg(cAcc, 16)
        btn.setTextColor(Color.WHITE)
        btn.setPadding(dp(12), dp(6), dp(12), dp(6))
        val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        btn.layoutParams = blp
        btn.setOnClickListener { convertBanglish() }

        bar.addView(hint)
        bar.addView(btn)
        return bar
    }

    // ══════════════════════════════════════════════════════════
    // BOTTOM ROW — spacebar with swipe-to-switch
    // ══════════════════════════════════════════════════════════
    private fun buildBottomRow(): LinearLayout {
        return row {
            // !#1 symbols button
            val symBtn = makeKey("!#1", 1.3f, special = true)
            symBtn.textSize = 13f
            symBtn.setOnClickListener {
                mode = if (mode == Mode.SYMBOLS) Mode.ENG else Mode.SYMBOLS
                buildKeyboard()
            }
            addView(symBtn)

            // Spacebar — swipe left/right to switch language
            val spaceBtn = makeKey(modeLabel(), 3f)
            spaceBtn.textSize = 12f
            spaceBtn.setTextColor(if (prefs.getBoolean(PREF_DARK, false)) Color.LTGRAY else Color.GRAY)

            spaceBtn.setOnClickListener { typeChar(" ") }
            spaceBtn.setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { swipeStartX = ev.x; false }
                    MotionEvent.ACTION_UP -> {
                        val dx = ev.x - swipeStartX
                        if (dx > dp(60)) {
                            // swipe right = next mode
                            cycleMode(1)
                            true
                        } else if (dx < -dp(60)) {
                            // swipe left = prev mode
                            cycleMode(-1)
                            true
                        } else false
                    }
                    else -> false
                }
            }
            addView(spaceBtn)

            // Comma with long-press for apostrophe
            val commaKey = makeKey(",", 0.8f)
            commaKey.setOnClickListener { typeChar(",") }
            setupLongPressAlt(commaKey, ",", "'")
            addView(commaKey)

            // Period with long-press for ellipsis
            val dotKey = makeKey(".", 0.8f)
            dotKey.setOnClickListener { typeChar(".") }
            setupLongPressAlt(dotKey, ".", "…")
            addView(dotKey)

            // Enter
            val enterKey = makeKey("↵", 1.3f, special = true)
            enterKey.background = roundBg(cAcc, 6)
            enterKey.setTextColor(Color.WHITE)
            enterKey.setOnClickListener { enter() }
            addView(enterKey)
        }
    }

    private fun modeLabel(): String = when (mode) {
        Mode.ENG -> "English"
        Mode.BN -> "বাংলা"
        Mode.BANGLISH -> "Banglish AI"
        Mode.EMOJI -> "Emoji"
        Mode.SYMBOLS -> "Symbols"
    }

    private fun cycleMode(dir: Int) {
        val modes = listOf(Mode.ENG, Mode.BANGLISH, Mode.BN)
        val idx = modes.indexOf(mode).let { if (it < 0) 0 else it }
        val next = (idx + dir + modes.size) % modes.size
        mode = modes[next]
        banglishBuf.clear()
        buildKeyboard()
        Toast.makeText(this, modeLabel(), Toast.LENGTH_SHORT).show()
    }

    // ══════════════════════════════════════════════════════════
    // KEY PREVIEW POPUP
    // ══════════════════════════════════════════════════════════
    private var previewPopup: PopupWindow? = null

    private fun showKeyPreview(anchor: View, text: String) {
        previewPopup?.dismiss()
        if (text.length > 2) return // Don't show preview for long labels

        val tv = TextView(this)
        tv.text = text
        tv.textSize = 22f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(cTxt)
        tv.setTypeface(null, Typeface.BOLD)
        tv.setPadding(dp(12), dp(8), dp(12), dp(8))
        tv.background = roundBg(cKey, 8)
        tv.elevation = dp(4).toFloat()

        val popup = PopupWindow(tv, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        popup.isOutsideTouchable = true
        popup.isFocusable = false

        try {
            val loc = IntArray(2)
            anchor.getLocationInWindow(loc)
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, loc[0] - dp(4), loc[1] - dp(44))
            previewPopup = popup
            Handler(Looper.getMainLooper()).postDelayed({ popup.dismiss() }, 300)
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════
    // CLIPBOARD POPUP
    // ══════════════════════════════════════════════════════════
    private fun showClipboard() {
        val list = clipMgr.getAll()
        if (list.isEmpty()) {
            Toast.makeText(this, "No clipboard history yet", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setBackgroundColor(cBg)
        val sv = ScrollView(this)
        sv.layoutParams = ViewGroup.LayoutParams(dp(300), dp(300))

        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.setPadding(dp(8), dp(8), dp(8), dp(8))

        for (item in list.take(15)) {
            val tv = TextView(this)
            tv.text = if (item.length > 60) item.take(60) + "…" else item
            tv.textSize = 13f
            tv.setTextColor(cTxt)
            tv.setPadding(dp(12), dp(10), dp(12), dp(10))
            tv.background = roundBg(cKey, 6)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(3), 0, dp(3))
            tv.layoutParams = lp
            tv.setOnClickListener {
                currentInputConnection?.commitText(item, 1)
                if (prefs.getBoolean(PREF_LOG, false)) logMgr.log("[PASTE] $item")
            }
            inner.addView(tv)
        }
        sv.addView(inner)
        container.addView(sv)

        val popup = PopupWindow(container, dp(300), dp(320))
        popup.isFocusable = true
        popup.isOutsideTouchable = true
        try {
            val root = rootView ?: return
            popup.showAtLocation(root, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(220))
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════
    // ACTIONS
    // ══════════════════════════════════════════════════════════
    private fun typeChar(ch: String) {
        currentInputConnection?.commitText(ch, 1)
        if (mode == Mode.BANGLISH && ch != " ") banglishBuf.append(ch)
        else if (ch == " " && mode == Mode.BANGLISH) banglishBuf.append(" ")
        if (prefs.getBoolean(PREF_LOG, false)) logMgr.log(ch)
        // Track clipboard changes
        if (ch.length > 5) clipMgr.add(ch)
    }

    private fun deleteChar() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        if (mode == Mode.BANGLISH && banglishBuf.isNotEmpty())
            banglishBuf.deleteCharAt(banglishBuf.length - 1)
    }

    private fun enter() {
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        if (mode == Mode.BANGLISH) { banglishBuf.clear(); buildKeyboard() }
    }

    private fun copyText() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (sel != null && sel.isNotEmpty()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("copy", sel))
            clipMgr.add(sel.toString())
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
            if (prefs.getBoolean(PREF_LOG, false)) logMgr.log("[COPY] $sel")
        } else {
            Toast.makeText(this, "Select text first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteText() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this)
            currentInputConnection?.commitText(text, 1)
            if (prefs.getBoolean(PREF_LOG, false)) logMgr.log("[PASTE] $text")
        }
    }

    private fun cutText() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (sel != null && sel.isNotEmpty()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("cut", sel))
            ic.commitText("", 1)
            clipMgr.add(sel.toString())
            Toast.makeText(this, "Cut!", Toast.LENGTH_SHORT).show()
            if (prefs.getBoolean(PREF_LOG, false)) logMgr.log("[CUT] $sel")
        } else {
            Toast.makeText(this, "Select text first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun undoText() {
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z))
    }

    // ══════════════════════════════════════════════════════════
    // DELETE WITH HOLD-TO-REPEAT
    // ══════════════════════════════════════════════════════════
    private fun setupDeleteKey(tv: TextView) {
        tv.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    deleteChar()
                    // Start repeat after 400ms
                    deleteRunnable = object : Runnable {
                        override fun run() {
                            deleteChar()
                            deleteHandler.postDelayed(this, 80)
                        }
                    }
                    deleteHandler.postDelayed(deleteRunnable!!, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    deleteRunnable?.let { deleteHandler.removeCallbacks(it) }
                    deleteRunnable = null
                    true
                }
                else -> false
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // LONG PRESS HELPERS
    // ══════════════════════════════════════════════════════════
    private fun setupLongPressAlt(tv: TextView, primary: String, alt: String) {
        val handler = Handler(Looper.getMainLooper())
        var longPressTriggered = false

        tv.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    handler.postDelayed({
                        longPressTriggered = true
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        typeChar(alt)
                        showKeyPreview(v as TextView, alt)
                    }, 400)
                    false
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacksAndMessages(null)
                    if (longPressTriggered) { longPressTriggered = false; true } else false
                }
                MotionEvent.ACTION_CANCEL -> { handler.removeCallbacksAndMessages(null); false }
                else -> false
            }
        }
    }

    private fun setupLongPressAction(tv: TextView, label: String, action: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var triggered = false

        tv.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    triggered = false
                    handler.postDelayed({
                        triggered = true
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
                        action()
                    }, 400)
                    false
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacksAndMessages(null)
                    if (triggered) { triggered = false; true } else false
                }
                MotionEvent.ACTION_CANCEL -> { handler.removeCallbacksAndMessages(null); false }
                else -> false
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // BANGLISH AI
    // ══════════════════════════════════════════════════════════
    private fun convertBanglish() {
        val text = banglishBuf.toString().trim()
        if (text.isEmpty()) { Toast.makeText(this, "Type something first", Toast.LENGTH_SHORT).show(); return }
        val key = prefs.getString(PREF_GROQ, "") ?: ""
        if (key.isEmpty()) { Toast.makeText(this, "Add Groq API key in Settings", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "AI converting...", Toast.LENGTH_SHORT).show()
        GroqClient(key).banglish(text) { result ->
            Handler(Looper.getMainLooper()).post {
                currentInputConnection?.deleteSurroundingText(text.length, 0)
                currentInputConnection?.commitText(result, 1)
                if (prefs.getBoolean(PREF_LOG, false)) logMgr.log("[BANGLISH->BN] $text -> $result")
                banglishBuf.clear()
                buildKeyboard()
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // VOICE
    // ══════════════════════════════════════════════════════════
    private fun toggleVoice() {
        if (recording) stopVoice() else startVoice()
    }

    private fun startVoice() {
        try {
            val f = java.io.File(cacheDir, "ab_rec.m4a"); if (f.exists()) f.delete()
            mediaRec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
                       else @Suppress("DEPRECATION") MediaRecorder()
            mediaRec!!.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRec!!.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRec!!.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRec!!.setAudioSamplingRate(16000)
            mediaRec!!.setOutputFile(f.absolutePath)
            mediaRec!!.prepare(); mediaRec!!.start()
            recording = true; buildKeyboard()
            Toast.makeText(this, "Recording... tap ■ to stop", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Mic error. Check permissions.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVoice() {
        try {
            mediaRec?.stop(); mediaRec?.release(); mediaRec = null
            recording = false; buildKeyboard()
            val key = prefs.getString(PREF_GROQ, "") ?: ""
            if (key.isEmpty()) { Toast.makeText(this, "Add Groq API key in Settings", Toast.LENGTH_SHORT).show(); return }
            val f = java.io.File(cacheDir, "ab_rec.m4a")
            if (!f.exists() || f.length() < 500) { Toast.makeText(this, "Recording too short", Toast.LENGTH_SHORT).show(); return }
            val lang = if (mode == Mode.BN || mode == Mode.BANGLISH) "bn" else "en"
            Toast.makeText(this, "Processing...", Toast.LENGTH_SHORT).show()
            GroqClient(key).transcribe(f, lang) { text ->
                Handler(Looper.getMainLooper()).post {
                    if (text.isNotEmpty()) {
                        currentInputConnection?.commitText(text, 1)
                        if (prefs.getBoolean(PREF_LOG, false)) logMgr.log("[VOICE] $text")
                    } else Toast.makeText(this, "Could not recognize speech", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) { mediaRec = null; recording = false; buildKeyboard() }
    }

    // ══════════════════════════════════════════════════════════
    // BUILDER HELPERS
    // ══════════════════════════════════════════════════════════
    private fun row(block: LinearLayout.() -> Unit): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.setPadding(dp(3), dp(2), dp(3), dp(2))
        r.block()
        return r
    }

    private fun makeKey(label: String, flex: Float, special: Boolean = false): TextView {
        val tv = TextView(this)
        tv.setText(label)
        tv.textSize = when {
            label.length >= 5 -> 10f
            label.length >= 3 -> 12f
            label.length == 2 -> 14f
            else -> 18f
        }
        tv.gravity = Gravity.CENTER
        tv.setTextColor(cTxt)
        tv.background = roundBg(if (special) cSp else cKey, 6)
        tv.isHapticFeedbackEnabled = true
        val lp = LinearLayout.LayoutParams(
            if (flex == 0f) LinearLayout.LayoutParams.WRAP_CONTENT else 0,
            dp(46), flex
        )
        lp.setMargins(dp(2), dp(2), dp(2), dp(2))
        tv.layoutParams = lp
        return tv
    }

    private fun spKey(label: String, flex: Float, special: Boolean = true, action: () -> Unit): TextView {
        val tv = makeKey(label, flex, special)
        tv.setOnClickListener {
            tv.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
        return tv
    }

    private fun spacerV(flex: Float): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(0, dp(46), flex)
        return v
    }

    private fun roundBg(color: Int, r: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = r * resources.displayMetrics.density
        d.setColor(color)
        return d
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ══════════════════════════════════════════════════════════
    // SAFE EMOJI (Android 5.1+ compatible — Unicode < 7.0)
    // ══════════════════════════════════════════════════════════
    private fun getSafeEmoji() = listOf(
        // Smileys
        "\uD83D\uDE00","\uD83D\uDE01","\uD83D\uDE02","\uD83D\uDE03","\uD83D\uDE04",
        "\uD83D\uDE05","\uD83D\uDE06","\uD83D\uDE07","\uD83D\uDE08","\uD83D\uDE09",
        "\uD83D\uDE0A","\uD83D\uDE0B","\uD83D\uDE0C","\uD83D\uDE0D","\uD83D\uDE0E",
        "\uD83D\uDE0F","\uD83D\uDE10","\uD83D\uDE11","\uD83D\uDE12","\uD83D\uDE13",
        "\uD83D\uDE14","\uD83D\uDE15","\uD83D\uDE16","\uD83D\uDE17","\uD83D\uDE18",
        "\uD83D\uDE19","\uD83D\uDE1A","\uD83D\uDE1B","\uD83D\uDE1C","\uD83D\uDE1D",
        "\uD83D\uDE1E","\uD83D\uDE1F","\uD83D\uDE20","\uD83D\uDE21","\uD83D\uDE22",
        "\uD83D\uDE23","\uD83D\uDE24","\uD83D\uDE25","\uD83D\uDE26","\uD83D\uDE27",
        "\uD83D\uDE28","\uD83D\uDE29","\uD83D\uDE2A","\uD83D\uDE2B","\uD83D\uDE2C",
        "\uD83D\uDE2D","\uD83D\uDE2E","\uD83D\uDE2F","\uD83D\uDE30","\uD83D\uDE31",
        "\uD83D\uDE32","\uD83D\uDE33","\uD83D\uDE34","\uD83D\uDE35","\uD83D\uDE36",
        "\uD83D\uDE37","\uD83D\uDE38","\uD83D\uDE39","\uD83D\uDE3A","\uD83D\uDE3B",
        // Hands
        "\uD83D\uDC4D","\uD83D\uDC4E","\uD83D\uDC4F","\uD83D\uDC50","\uD83D\uDC46",
        "\uD83D\uDC47","\uD83D\uDC48","\uD83D\uDC49","\uD83D\uDC4A","\uD83D\uDC4B",
        "\uD83D\uDC4C","\u270A","\u270B","\u270C","\uD83D\uDC85","\uD83D\uDCAA",
        // Hearts
        "\u2764","\uD83D\uDC94","\uD83D\uDC95","\uD83D\uDC96","\uD83D\uDC97",
        "\uD83D\uDC98","\uD83D\uDC99","\uD83D\uDC9A","\uD83D\uDC9B","\uD83D\uDC9C",
        "\uD83D\uDC9D","\uD83D\uDC9E","\uD83D\uDC9F","\uD83D\uDCAF","\u2B50","\u2728",
        // Objects
        "\uD83D\uDD25","\uD83C\uDF08","\u2600","\uD83C\uDF19","\uD83C\uDF1F",
        "\uD83D\uDCF1","\uD83D\uDCBB","\uD83C\uDFA4","\uD83C\uDFA7","\uD83C\uDFB5",
        "\uD83C\uDFB6","\uD83D\uDCF7","\uD83D\uDCA1","\uD83D\uDD14","\uD83D\uDD11",
        "\uD83C\uDF81","\uD83C\uDF82","\uD83C\uDF89","\uD83C\uDF8A","\uD83C\uDFC6",
        // Flags & misc
        "\uD83C\uDDE7\uD83C\uDDE9","\uD83D\uDCAF","\u2705","\u274C","\u26A0",
        "\uD83D\uDD34","\uD83D\uDFE2","\uD83D\uDD35","\uD83D\uDFE1","\uD83D\uDD36"
    )

    override fun onDestroy() {
        super.onDestroy()
        deleteRunnable?.let { deleteHandler.removeCallbacks(it) }
        mediaRec?.release()
    }
}
