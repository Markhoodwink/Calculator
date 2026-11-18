package com.example.calculator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class HistoryRepository(context: Context) {
    private val prefs = context.getSharedPreferences("mb_cal_history_prefs", Context.MODE_PRIVATE)
    private val _records = mutableListOf<CalculationRecord>()
    val records: List<CalculationRecord> get() = _records

    init {
        load()
    }

    fun addRecord(expression: String, result: String) {
        val expr = expression.trim()
        val res = result.trim()
        if (expr.isEmpty() || res.isEmpty() || res.startsWith("Ошибка")) return
        // Do not add duplicate of immediate previous record
        if (_records.isNotEmpty() && _records[0].expression == expr && _records[0].result == res) {
            return
        }
        _records.add(0, CalculationRecord(expr, res, System.currentTimeMillis()))
        while (_records.size > 50) {
            _records.removeAt(_records.size - 1)
        }
        save()
    }

    fun clear() {
        _records.clear()
        save()
    }

    private fun save() {
        try {
            val jsonArr = JSONArray()
            for (rec in _records) {
                val obj = JSONObject().apply {
                    put("expr", rec.expression)
                    put("res", rec.result)
                    put("time", rec.timestamp)
                }
                jsonArr.put(obj)
            }
            prefs.edit().putString("history_data", jsonArr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun load() {
        try {
            val raw = prefs.getString("history_data", null) ?: return
            val jsonArr = JSONArray(raw)
            _records.clear()
            for (i in 0 until jsonArr.length()) {
                val obj = jsonArr.getJSONObject(i)
                val expr = obj.getString("expr")
                val res = obj.getString("res")
                val time = obj.optLong("time", System.currentTimeMillis())
                _records.add(CalculationRecord(expr, res, time))
            }
        } catch (_: Exception) {}
    }
}
