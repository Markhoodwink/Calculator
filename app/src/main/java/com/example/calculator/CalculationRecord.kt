package com.example.calculator

data class CalculationRecord(
    val expression: String,
    val result: String,
    val timestamp: Long = System.currentTimeMillis()
)
