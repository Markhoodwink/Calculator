package com.example.calculator

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryBottomSheet(
    context: Context,
    private val historyRepository: HistoryRepository,
    private val onFormulaClick: (String) -> Unit,
    private val onResultClick: (String) -> Unit
) : BottomSheetDialog(context) {

    private lateinit var adapter: HistoryAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var rvHistory: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.bottom_sheet_history)

        val context = context
        val dp = context.resources.displayMetrics.density
        val isDark = ThemeManager.isDarkTheme(context)

        // Clear default bottom sheet container background for smooth rounded top corners
        window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background = null

        val rootLayout = findViewById<View>(R.id.layoutBottomSheetHistory)
        val dragHandle = findViewById<View>(R.id.dragHandleHistory)
        val tvTitle = findViewById<TextView>(R.id.tvHistoryTitle)
        val btnClear = findViewById<Button>(R.id.btnClearHistory)
        val divider = findViewById<View>(R.id.dividerHistory)
        tvEmpty = findViewById(R.id.tvEmptyHistory) ?: return
        rvHistory = findViewById(R.id.rvHistory) ?: return

        rootLayout?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                18f * dp, 18f * dp,
                18f * dp, 18f * dp,
                0f, 0f, 0f, 0f
            )
            setColor(if (isDark) ThemeManager.COLOR_DARK_PANEL else ThemeManager.COLOR_LIGHT_PANEL)
        }

        dragHandle?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 2f * dp
            setColor(if (isDark) Color.parseColor("#475569") else Color.parseColor("#CBD5E1"))
        }

        tvTitle?.text = "История вычислений"
        tvTitle?.setTextColor(if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))

        btnClear?.text = "ОЧИСТИТЬ"
        btnClear?.setTextColor(if (isDark) Color.parseColor("#EF4444") else Color.parseColor("#DC2626"))

        divider?.setBackgroundColor(if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)

        tvEmpty.text = "История вычислений пуста"
        tvEmpty.setTextColor(if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B"))

        rvHistory.layoutManager = LinearLayoutManager(context)
        adapter = HistoryAdapter(
            historyRepository.records,
            onFormulaClick = { expr ->
                onFormulaClick(expr)
                dismiss()
            },
            onResultClick = { res ->
                onResultClick(res)
                dismiss()
            }
        )
        rvHistory.adapter = adapter

        updateVisibility()

        btnClear?.setOnClickListener {
            historyRepository.clear()
            adapter.updateItems(historyRepository.records)
            updateVisibility()
        }
    }

    private fun updateVisibility() {
        val isEmpty = historyRepository.records.isEmpty()
        tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvHistory.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private class HistoryAdapter(
        private var items: List<CalculationRecord>,
        private val onFormulaClick: (String) -> Unit,
        private val onResultClick: (String) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardView: View = view.findViewById(R.id.cardHistoryItem)
            val tvTimestamp: TextView = view.findViewById(R.id.tvItemTimestamp)
            val tvLabelExpression: TextView = view.findViewById(R.id.tvLabelExpression)
            val tvExpression: TextView = view.findViewById(R.id.tvItemExpression)
            val tvLabelResult: TextView = view.findViewById(R.id.tvLabelResult)
            val tvResult: TextView = view.findViewById(R.id.tvItemResult)
            val layoutExpression: View = view.findViewById(R.id.layoutItemExpression)
            val layoutResult: View = view.findViewById(R.id.layoutItemResult)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val context = holder.itemView.context
            val dp = context.resources.displayMetrics.density
            val isDark = ThemeManager.isDarkTheme(context)

            holder.cardView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * dp
                setColor(if (isDark) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC"))
                setStroke((1 * dp).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
            }

            val secColor = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")
            val exprColor = if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")
            val resColor = if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")

            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            holder.tvTimestamp.text = sdf.format(Date(item.timestamp))
            holder.tvTimestamp.setTextColor(secColor)

            holder.tvLabelExpression.setTextColor(secColor)
            holder.tvExpression.text = formatExpressionForDisplay(item.expression)
            holder.tvExpression.setTextColor(exprColor)

            holder.tvLabelResult.setTextColor(secColor)
            holder.tvResult.text = item.result
            holder.tvResult.setTextColor(resColor)

            holder.layoutExpression.setOnClickListener {
                onFormulaClick(item.expression)
            }
            holder.layoutResult.setOnClickListener {
                onResultClick(item.result)
            }
        }

        override fun getItemCount(): Int = items.size

        fun updateItems(newItems: List<CalculationRecord>) {
            items = newItems
            notifyDataSetChanged()
        }

        private fun formatExpressionForDisplay(raw: String): String {
            if (raw.isBlank()) return raw

            // 1. Смешанные дроби: ((w)+(n)/(d)) -> w n/d
            val mixedPattern = """\(\(\s*([^()]+)\s*\)\+\(\s*([^()]+)\s*\)/\(\s*([^()]+)\s*\)\)""".toRegex()
            var s = mixedPattern.replace(raw) { m ->
                "${m.groupValues[1]} ${m.groupValues[2]}/${m.groupValues[3]}"
            }

            // 2. Обыкновенные дроби: ((n)/(d)) -> n/d
            val fracPattern = """\(\(\s*([^()]+)\s*\)/\(\s*([^()]+)\s*\)\)""".toRegex()
            s = fracPattern.replace(s) { m ->
                "${m.groupValues[1]}/${m.groupValues[2]}"
            }

            // 3. Дробь вида (n)/(d) -> n/d
            val altFracPattern = """\(\s*([^()]+)\s*\)/\(\s*([^()]+)\s*\)""".toRegex()
            s = altFracPattern.replace(s) { m ->
                "${m.groupValues[1]}/${m.groupValues[2]}"
            }

            // 4. Операторы и степени
            return s
                .replace("*", "·")
                .replace("^2", "²")
                .replace("^3", "³")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }
    }
}
