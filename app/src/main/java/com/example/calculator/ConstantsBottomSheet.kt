package com.example.calculator

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
        PhysicalConstant("e", "Основание нат. логарифма", "2.71828182846", ""),
        PhysicalConstant("g", "Ускорение своб. падения", "9.80665", "м/с²"),
        PhysicalConstant("c", "Скорость света", "299792458", "м/с"),
        PhysicalConstant("G", "Гравитационная постоянная", "6.6743e-11", "Н·м²/кг²"),
        PhysicalConstant("h", "Постоянная Планка", "6.62607015e-34", "Дж·с"),
        PhysicalConstant("Nₐ", "Число Авогадро", "6.02214076e23", "моль⁻¹"),
        PhysicalConstant("k", "Постоянная Больцмана", "1.380649e-23", "Дж/К"),
        PhysicalConstant("k_B", "Постоянная Больцмана (k_B)", "1.380649e-23", "Дж/К"),
        PhysicalConstant("R", "Газовая постоянная", "8.314462618", "Дж/(моль·К)"),
        PhysicalConstant("ε₀", "Электрическая постоянная", "8.8541878128e-12", "Ф/м"),
        PhysicalConstant("e₀", "Электрическая постоянная (e₀)", "8.8541878128e-12", "Ф/м"),
        PhysicalConstant("μ₀", "Магнитная постоянная", "1.25663706212e-6", "Гн/м"),
        PhysicalConstant("qₑ", "Элементарный заряд", "1.602176634e-19", "Кл"),
        PhysicalConstant("mₑ", "Масса электрона", "9.1093837e-31", "кг"),
        PhysicalConstant("mₚ", "Масса протона", "1.67262192e-27", "кг")
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_constants, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rvConstants) ?: return
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = ConstantsAdapter(constants) { constant ->
            onConstantSelected(constant.symbol)
            dismiss()
        }
    }

    private class ConstantsAdapter(
        private val items: List<PhysicalConstant>,
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
            holder.tvSymbol.text = item.symbol
            holder.tvName.text = item.name
            holder.tvUnit.text = item.unit
            holder.tvValue.text = item.value

            holder.layoutItem.setOnClickListener {
                onClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
