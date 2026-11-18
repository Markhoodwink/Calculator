package com.example.calculator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MatrixModeManager(
    private val rootLayout: View,
    private val casEngine: CasEngine,
    private val onHaptic: (View) -> Unit = {}
) {

    enum class MatId { A, B }

    val layoutMatrixMode: View = rootLayout.findViewById(R.id.layoutMatrixMode)
    val btnMatrixBack: TextView = rootLayout.findViewById(R.id.btnMatrixBack)
    val btnMatrixClear: TextView = rootLayout.findViewById(R.id.btnMatrixClear)
    val btnMatSize2x2: Button = rootLayout.findViewById(R.id.btnMatSize2x2)
    val btnMatSize3x3: Button = rootLayout.findViewById(R.id.btnMatSize3x3)
    val tabMatA: Button = rootLayout.findViewById(R.id.tabMatA)
    val tabMatB: Button = rootLayout.findViewById(R.id.tabMatB)
    val tvMatrixInputTitle: TextView = rootLayout.findViewById(R.id.tvMatrixInputTitle)
    val containerMatrixGrid: LinearLayout = rootLayout.findViewById(R.id.containerMatrixGrid)
    val cardMatrixResult: View = rootLayout.findViewById(R.id.cardMatrixResult)
    val tvMatrixResultTitle: TextView = rootLayout.findViewById(R.id.tvMatrixResultTitle)
    val btnUseResultAsA: Button = rootLayout.findViewById(R.id.btnUseResultAsA)
    val btnCopyMatrixResult: Button = rootLayout.findViewById(R.id.btnCopyMatrixResult)
    val containerMatrixResult: LinearLayout = rootLayout.findViewById(R.id.containerMatrixResult)

    // Операции
    val btnMatDet: Button = rootLayout.findViewById(R.id.btnMatDet)
    val btnMatInv: Button = rootLayout.findViewById(R.id.btnMatInv)
    val btnMatTrans: Button = rootLayout.findViewById(R.id.btnMatTrans)
    val btnMatAdd: Button = rootLayout.findViewById(R.id.btnMatAdd)
    val btnMatSub: Button = rootLayout.findViewById(R.id.btnMatSub)
    val btnMatMul: Button = rootLayout.findViewById(R.id.btnMatMul)
    val btnMatKeyDet: Button = rootLayout.findViewById(R.id.btnMatKeyDet)

    var activeMatrix: MatId = MatId.A
        private set
    var activeSize: Int = 2
        private set

    private val dataA2x2 = Array(2) { Array(2) { "" } }
    private val dataB2x2 = Array(2) { Array(2) { "" } }
    private val dataA3x3 = Array(3) { Array(3) { "" } }
    private val dataB3x3 = Array(3) { Array(3) { "" } }

    private val cells = mutableListOf<EditText>()
    private var activeCell: EditText? = null
    private var lastResult: MatrixResult? = null

    init {
        initKeypad()
        initOperations()

        btnMatSize2x2.setOnClickListener {
            onHaptic(it)
            if (activeSize != 2) {
                saveCurrentGridToMemory()
                activeSize = 2
                updateTabStyles()
                rebuildGrid()
            }
        }

        btnMatSize3x3.setOnClickListener {
            onHaptic(it)
            if (activeSize != 3) {
                saveCurrentGridToMemory()
                activeSize = 3
                updateTabStyles()
                rebuildGrid()
            }
        }

        tabMatA.setOnClickListener {
            onHaptic(it)
            if (activeMatrix != MatId.A) {
                saveCurrentGridToMemory()
                activeMatrix = MatId.A
                updateTabStyles()
                rebuildGrid()
            }
        }

        tabMatB.setOnClickListener {
            onHaptic(it)
            if (activeMatrix != MatId.B) {
                saveCurrentGridToMemory()
                activeMatrix = MatId.B
                updateTabStyles()
                rebuildGrid()
            }
        }

        btnMatrixClear.setOnClickListener {
            onHaptic(it)
            val data = getData(activeMatrix, activeSize)
            for (r in 0 until activeSize) {
                for (c in 0 until activeSize) {
                    data[r][c] = ""
                }
            }
            cells.forEach { c -> c.setText("") }
            if (cells.isNotEmpty()) setActiveCell(cells[0])
            lastResult = null
            containerMatrixResult.removeAllViews()
            tvMatrixResultTitle.text = "Результат:"
        }

        btnUseResultAsA.setOnClickListener {
            onHaptic(it)
            val res = lastResult
            if (res is MatrixResult.MatrixValue) {
                val n = res.rows.size
                activeSize = n
                val targetData = getData(MatId.A, n)
                for (r in 0 until n) {
                    for (c in 0 until n) {
                        targetData[r][c] = res.formattedRows[r][c]
                    }
                }
                activeMatrix = MatId.A
                updateTabStyles()
                rebuildGrid()
                Toast.makeText(rootLayout.context, "Результат записан в Матрицу A", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(rootLayout.context, "Результат не является матрицей", Toast.LENGTH_SHORT).show()
            }
        }

        btnCopyMatrixResult.setOnClickListener {
            onHaptic(it)
            val res = lastResult
            if (res != null) {
                val textToCopy = when (res) {
                    is MatrixResult.Scalar -> "${res.label} = ${res.formatted}"
                    is MatrixResult.MatrixValue -> res.formattedRows.joinToString("\n") { row ->
                        "[ " + row.joinToString("\t") + " ]"
                    }
                    is MatrixResult.Error -> res.message
                }
                val clipboard = rootLayout.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Результат матриц", textToCopy))
                Toast.makeText(rootLayout.context, "Ответ скопирован", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(rootLayout.context, "Нет результата для копирования", Toast.LENGTH_SHORT).show()
            }
        }

        updateTabStyles()
        rebuildGrid()
    }

    private fun getData(id: MatId, size: Int): Array<Array<String>> {
        return when {
            id == MatId.A && size == 2 -> dataA2x2
            id == MatId.B && size == 2 -> dataB2x2
            id == MatId.A && size == 3 -> dataA3x3
            else -> dataB3x3
        }
    }

    fun getMatrixValues(id: MatId, size: Int = activeSize): List<List<String>> {
        val data = getData(id, size)
        return (0 until size).map { r ->
            (0 until size).map { c ->
                data[r][c]
            }
        }
    }

    fun saveCurrentGridToMemory() {
        val data = getData(activeMatrix, activeSize)
        for (r in 0 until activeSize) {
            for (c in 0 until activeSize) {
                val idx = r * activeSize + c
                if (idx < cells.size) {
                    data[r][c] = cells[idx].text.toString().trim()
                }
            }
        }
    }

    private fun updateTabStyles() {
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        val activeBg = ColorStateList.valueOf(Color.parseColor("#0284C7"))
        val inactiveBg = ColorStateList.valueOf(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"))
        val purpleBg = ColorStateList.valueOf(Color.parseColor("#7C3AED"))
        val whiteText = Color.parseColor("#FFFFFF")
        val dimText = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#475569")

        btnMatSize2x2.backgroundTintList = if (activeSize == 2) activeBg else inactiveBg
        btnMatSize2x2.setTextColor(if (activeSize == 2) whiteText else dimText)

        btnMatSize3x3.backgroundTintList = if (activeSize == 3) activeBg else inactiveBg
        btnMatSize3x3.setTextColor(if (activeSize == 3) whiteText else dimText)

        tabMatA.backgroundTintList = if (activeMatrix == MatId.A) purpleBg else inactiveBg
        tabMatA.setTextColor(if (activeMatrix == MatId.A) whiteText else dimText)

        tabMatB.backgroundTintList = if (activeMatrix == MatId.B) purpleBg else inactiveBg
        tabMatB.setTextColor(if (activeMatrix == MatId.B) whiteText else dimText)
    }

    fun refreshTheme() {
        updateTabStyles()
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        for (cell in cells) {
            val isFocused = (activeCell == cell)
            cell.background = getCellBackground(isFocused)
            cell.setTextColor(if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
            cell.setHintTextColor(if (isDark) Color.parseColor("#475569") else Color.parseColor("#94A3B8"))
        }
    }

    private fun rebuildGrid() {
        containerMatrixGrid.removeAllViews()
        cells.clear()
        activeCell = null

        tvMatrixInputTitle.text = "Матрица ${activeMatrix.name} (${activeSize}×${activeSize})"

        val context = rootLayout.context
        val density = rootLayout.resources.displayMetrics.density
        val dp = { value: Float -> (value * density).toInt() }
        val data = getData(activeMatrix, activeSize)

        for (r in 0 until activeSize) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, dp(3f), 0, dp(3f))
                }
                gravity = Gravity.CENTER_VERTICAL
            }

            for (c in 0 until activeSize) {
                val cell = createCell(r, c, data[r][c])
                rowLayout.addView(cell)
                cells.add(cell)
            }

            containerMatrixGrid.addView(rowLayout)
        }

        if (cells.isNotEmpty()) {
            setActiveCell(cells[0])
        }
    }

    private fun createCell(r: Int, c: Int, initialValue: String): EditText {
        val context = rootLayout.context
        val density = rootLayout.resources.displayMetrics.density
        val dp = { value: Float -> (value * density).toInt() }
        val isDark = ThemeManager.isDarkTheme(context)

        val cell = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42f), 1f).apply {
                setMargins(dp(3f), 0, dp(3f), 0)
            }
            background = getCellBackground(false)
            setTextColor(if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            hint = "0"
            setHintTextColor(if (isDark) Color.parseColor("#475569") else Color.parseColor("#94A3B8"))
            isSingleLine = true
            maxLines = 1
            showSoftInputOnFocus = false
            isCursorVisible = true
            tag = "mat_cell_${r}_${c}"
            contentDescription = "mat_cell_${r}_${c}"
            setText(initialValue)

            setOnTouchListener { _, _ ->
                setActiveCell(this)
                false
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    setActiveCell(this)
                }
            }
            setOnClickListener {
                setActiveCell(this)
            }
        }
        return cell
    }

    fun setActiveCell(cell: EditText) {
        if (activeCell == cell && cell.isFocused) return
        activeCell?.background = getCellBackground(false)
        activeCell = cell
        activeCell?.background = getCellBackground(true)
        activeCell?.requestFocus()
        activeCell?.setSelection(activeCell?.text?.length ?: 0)
    }

    private fun getCellBackground(isFocused: Boolean): Drawable {
        val density = rootLayout.resources.displayMetrics.density
        val gd = GradientDrawable()
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        gd.shape = GradientDrawable.RECTANGLE
        gd.setColor(if (isDark) Color.parseColor("#0F172A") else Color.parseColor("#E2E8F0"))
        gd.cornerRadius = 8f * density
        if (isFocused) {
            gd.setStroke((2f * density).toInt(), Color.parseColor("#A855F7"))
        } else {
            gd.setStroke((1f * density).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
        }
        return gd
    }

    private fun initOperations() {
        btnMatDet.setOnClickListener {
            onHaptic(it)
            computeDeterminant()
        }

        btnMatInv.setOnClickListener {
            onHaptic(it)
            computeInverse()
        }

        btnMatTrans.setOnClickListener {
            onHaptic(it)
            computeTranspose()
        }

        btnMatAdd.setOnClickListener {
            onHaptic(it)
            computeBinaryOp("+")
        }

        btnMatSub.setOnClickListener {
            onHaptic(it)
            computeBinaryOp("-")
        }

        btnMatMul.setOnClickListener {
            onHaptic(it)
            computeBinaryOp("*")
        }

        btnMatKeyDet.setOnClickListener {
            onHaptic(it)
            computeDeterminant()
        }
    }

    fun computeDeterminant() {
        saveCurrentGridToMemory()
        val matrixA = getMatrixValues(MatId.A, activeSize)
        val res = casEngine.matrixDeterminant(matrixA)
        lastResult = res
        renderResult(res)
    }

    fun computeInverse() {
        saveCurrentGridToMemory()
        val matrixA = getMatrixValues(MatId.A, activeSize)
        val res = casEngine.matrixInverse(matrixA)
        lastResult = res
        renderResult(res)
    }

    fun computeTranspose() {
        saveCurrentGridToMemory()
        val matrixA = getMatrixValues(MatId.A, activeSize)
        val res = casEngine.matrixTranspose(matrixA)
        lastResult = res
        renderResult(res)
    }

    fun computeBinaryOp(op: String) {
        saveCurrentGridToMemory()
        val matrixA = getMatrixValues(MatId.A, activeSize)
        val matrixB = getMatrixValues(MatId.B, activeSize)
        val res = casEngine.matrixBinaryOp(matrixA, matrixB, op)
        lastResult = res
        renderResult(res)
    }

    private fun renderResult(result: MatrixResult) {
        containerMatrixResult.removeAllViews()
        val context = rootLayout.context
        val density = rootLayout.resources.displayMetrics.density
        val dp = { value: Float -> (value * density).toInt() }

        when (result) {
            is MatrixResult.Scalar -> {
                tvMatrixResultTitle.text = "${result.label}:"
                val tv = TextView(context).apply {
                    text = result.formatted
                    textSize = 20f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#F8FAFC"))
                    setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
                }
                containerMatrixResult.addView(tv)
            }
            is MatrixResult.MatrixValue -> {
                tvMatrixResultTitle.text = "${result.label}:"
                for (row in result.formattedRows) {
                    val rowLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, dp(2f), 0, dp(2f))
                        }
                        gravity = Gravity.CENTER
                    }
                    for (cellText in row) {
                        val cellTv = TextView(context).apply {
                            text = cellText
                            textSize = 15f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.parseColor("#F8FAFC"))
                            gravity = Gravity.CENTER
                            background = GradientDrawable().apply {
                              shape = GradientDrawable.RECTANGLE
                              setColor(Color.parseColor("#0F172A"))
                              cornerRadius = 6f * density
                              setStroke((1f * density).toInt(), Color.parseColor("#475569"))
                            }
                            layoutParams = LinearLayout.LayoutParams(0, dp(36f), 1f).apply {
                                setMargins(dp(2f), 0, dp(2f), 0)
                            }
                        }
                        rowLayout.addView(cellTv)
                    }
                    containerMatrixResult.addView(rowLayout)
                }
            }
            is MatrixResult.Error -> {
                tvMatrixResultTitle.text = "Ошибка:"
                val tv = TextView(context).apply {
                    text = result.message
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#EF4444"))
                    setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
                }
                containerMatrixResult.addView(tv)
            }
        }
    }

    private fun initKeypad() {
        val digitMap = mapOf(
            R.id.btnMat0 to "0", R.id.btnMat1 to "1", R.id.btnMat2 to "2",
            R.id.btnMat3 to "3", R.id.btnMat4 to "4", R.id.btnMat5 to "5",
            R.id.btnMat6 to "6", R.id.btnMat7 to "7", R.id.btnMat8 to "8",
            R.id.btnMat9 to "9", R.id.btnMatDot to ".", R.id.btnMatDiv to "/"
        )

        digitMap.forEach { (id, str) ->
            rootLayout.findViewById<Button>(id)?.setOnClickListener {
                onHaptic(it)
                handleInput(str)
            }
        }

        rootLayout.findViewById<Button>(R.id.btnMatMinus)?.setOnClickListener {
            onHaptic(it)
            handleMinus()
        }

        rootLayout.findViewById<Button>(R.id.btnMatDel)?.setOnClickListener {
            onHaptic(it)
            handleDelete()
        }

        rootLayout.findViewById<Button>(R.id.btnMatNext)?.setOnClickListener {
            onHaptic(it)
            handleNext()
        }
    }

    private fun handleInput(str: String) {
        val cell = activeCell ?: cells.firstOrNull() ?: return
        val start = maxOf(0, cell.selectionStart)
        val end = maxOf(0, cell.selectionEnd)
        val minPos = minOf(start, end)
        val maxPos = maxOf(start, end)

        cell.text.replace(minPos, maxPos, str)
        val newPos = minPos + str.length
        if (newPos <= cell.text.length) {
            cell.setSelection(newPos)
        }
    }

    private fun handleMinus() {
        val cell = activeCell ?: cells.firstOrNull() ?: return
        val text = cell.text.toString()
        val start = maxOf(0, cell.selectionStart)
        val end = maxOf(0, cell.selectionEnd)

        if (start == 0 && end == 0 && text.startsWith("-")) {
            cell.text.delete(0, 1)
            cell.setSelection(0)
        } else {
            handleInput("-")
        }
    }

    private fun handleDelete() {
        val cell = activeCell ?: cells.firstOrNull() ?: return
        val start = maxOf(0, cell.selectionStart)
        val end = maxOf(0, cell.selectionEnd)
        val minPos = minOf(start, end)
        val maxPos = maxOf(start, end)

        if (minPos != maxPos) {
            cell.text.delete(minPos, maxPos)
        } else if (minPos > 0) {
            cell.text.delete(minPos - 1, minPos)
        }
    }

    private fun handleNext() {
        if (cells.isEmpty()) return
        val currentIndex = cells.indexOf(activeCell)
        if (currentIndex == -1 || currentIndex == cells.lastIndex) {
            computeDeterminant()
        } else {
            setActiveCell(cells[currentIndex + 1])
        }
    }
}
