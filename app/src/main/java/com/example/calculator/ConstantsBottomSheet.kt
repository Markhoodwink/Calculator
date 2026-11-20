package com.example.calculator

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ConstantsBottomSheet(
    private val onConstantSelected: (String) -> Unit = {}
) : BottomSheetDialogFragment() {

    data class PhysicalConstant(
        val symbol: String,
        val name: String,
        val value: String,
        val unit: String
    )

    private val constants = listOf(
        PhysicalConstant("g", "Ускорение своб. падения", "10", "м/с²"),
        PhysicalConstant("R", "Газовая постоянная", "8.31", "Дж/(моль·К)"),
        PhysicalConstant("k", "Коэф. в законе Кулона", "9 · 10⁹", "Н·м²/Кл²"),
        PhysicalConstant("qₑ", "Элементарный заряд", "1.6 · 10⁻¹⁹", "Кл"),
        PhysicalConstant("c", "Скорость света в вакууме", "3 · 10⁸", "м/с"),
        PhysicalConstant("Nₐ", "Постоянная Авогадро", "6 · 10²³", "моль⁻¹"),
        PhysicalConstant("k_B", "Постоянная Больцмана", "1.38 · 10⁻²³", "Дж/К"),
        PhysicalConstant("h", "Постоянная Планка", "6.6 · 10⁻³⁴", "Дж·с"),
        PhysicalConstant("G", "Гравитационная постоянная", "6.7 · 10⁻¹¹", "Н·м²/кг²"),
        PhysicalConstant("mₑ", "Масса электрона", "9.1 · 10⁻³¹", "кг"),
        PhysicalConstant("mₚ", "Масса протона", "1.67 · 10⁻²⁷", "кг"),
        PhysicalConstant("ε₀", "Электрическая постоянная", "8.85 · 10⁻¹²", "Ф/м"),
        PhysicalConstant("μ₀", "Магнитная постоянная", "1.26 · 10⁻⁶", "Гн/м"),
        PhysicalConstant("e", "Число Эйлера (осн. ln)", "2.71828182846", "")
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_constants, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background = null

        val context = requireContext()
        val dp = resources.displayMetrics.density
        val isDark = ThemeManager.isDarkTheme(context)

        val rootLayout = view.findViewById<View>(R.id.layoutBottomSheetConstants) ?: view
        val dragHandle = view.findViewById<View>(R.id.dragHandleConstants)
        val tvTitle = view.findViewById<TextView>(R.id.tvConstantsTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvConstantsSubtitle)
        val divider = view.findViewById<View>(R.id.dividerConstants)
        val rv = view.findViewById<RecyclerView>(R.id.rvConstants) ?: return

        rootLayout.background = GradientDrawable().apply {
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

        tvTitle?.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)
        tvSubtitle?.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY)
        divider?.setBackgroundColor(if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)

        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = ConstantsAdapter(constants, isDark) { constant ->
            onConstantSelected(constant.symbol)
            dismiss()
        }
    }

    private class ConstantsAdapter(
        private val items: List<PhysicalConstant>,
        private val isDark: Boolean,
        private val onClick: (PhysicalConstant) -> Unit
    ) : RecyclerView.Adapter<ConstantsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvSymbol: TextView = view.findViewById(R.id.tvConstantSymbol)
            val tvName: TextView = view.findViewById(R.id.tvConstantName)
            val tvUnit: TextView = view.findViewById(R.id.tvConstantUnit)
            val tvValue: TextView = view.findViewById(R.id.tvConstantValue)
            val layoutItem: View = view.findViewById(R.id.layoutItemConstant)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_constant, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val dp = holder.itemView.context.resources.displayMetrics.density

            holder.tvSymbol.text = item.symbol
            holder.tvName.text = item.name
            holder.tvUnit.text = item.unit
            holder.tvValue.text = item.value

            holder.layoutItem.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * dp
                setColor(if (isDark) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC"))
                setStroke((1 * dp).toInt(), if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)
            }

            holder.tvSymbol.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            holder.tvName.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)
            holder.tvUnit.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY)
            holder.tvValue.setTextColor(if (isDark) Color.parseColor("#34D399") else Color.parseColor("#059669"))

            holder.layoutItem.setOnClickListener {
                onClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
