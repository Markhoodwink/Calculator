package com.example.calculator

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs

data class UnitItem(
    val id: String,
    val name: String,
    val factorToBase: Double = 1.0,
    val base: Int = 10
)

data class PresetItem(
    val label: String,
    val fromUnitId: String,
    val toUnitId: String
)

data class UnitCategory(
    val id: String,
    val name: String,
    val units: List<UnitItem>,
    val presets: List<PresetItem>
)

data class ConvertedUnitItem(
    val unit: UnitItem,
    val rawValue: Double,
    val formattedValue: String
)

class UnitConverterEngine {

    val categories: List<UnitCategory> = listOf(
        // 0. Системы счисления
        UnitCategory(
            id = "numeral_systems",
            name = "Системы счисления",
            units = listOf(
                UnitItem("bin", "Двоичная (2)", 1.0, 2),
                UnitItem("oct", "Восьмеричная (8)", 1.0, 8),
                UnitItem("dec", "Десятичная (10)", 1.0, 10),
                UnitItem("hex", "Шестнадцатеричная (16)", 1.0, 16),
                UnitItem("base3", "Троичная (3)", 1.0, 3),
                UnitItem("base4", "Четверичная (4)", 1.0, 4),
                UnitItem("base5", "Пятеричная (5)", 1.0, 5),
                UnitItem("base6", "Шестеричная (6)", 1.0, 6),
                UnitItem("base7", "Семеричная (7)", 1.0, 7),
                UnitItem("base9", "Девятеричная (9)", 1.0, 9),
                UnitItem("base12", "Двенадцатеричная (12)", 1.0, 12)
            ),
            presets = listOf(
                PresetItem("10 → 2 (BIN)", "dec", "bin"),
                PresetItem("10 → 16 (HEX)", "dec", "hex"),
                PresetItem("16 → 10 (DEC)", "hex", "dec"),
                PresetItem("2 → 16 (HEX)", "bin", "hex"),
                PresetItem("10 → 8 (OCT)", "dec", "oct")
            )
        ),

        // 1. Длина
        UnitCategory(
            id = "length",
            name = "Длина",
            units = listOf(
                UnitItem("mm", "мм", 0.001),
                UnitItem("cm", "см", 0.01),
                UnitItem("dm", "дм", 0.1),
                UnitItem("m", "м", 1.0),
                UnitItem("km", "км", 1000.0),
                UnitItem("in", "дюйм (in)", 0.0254),
                UnitItem("ft", "фут (ft)", 0.3048),
                UnitItem("yd", "ярд (yd)", 0.9144),
                UnitItem("mi", "миля (mi)", 1609.344),
                UnitItem("nmi", "мор. миля", 1852.0)
            ),
            presets = listOf(
                PresetItem("км → м", "km", "m"),
                PresetItem("км → миля", "km", "mi"),
                PresetItem("м → фут", "m", "ft"),
                PresetItem("дюйм → см", "in", "cm")
            )
        ),

        // 2. Площадь
        UnitCategory(
            id = "area",
            name = "Площадь",
            units = listOf(
                UnitItem("mm2", "мм²", 0.000001),
                UnitItem("cm2", "см²", 0.0001),
                UnitItem("dm2", "дм²", 0.01),
                UnitItem("m2", "м²", 1.0),
                UnitItem("sotka", "сотка (ар)", 100.0),
                UnitItem("ha", "гектар (га)", 10000.0),
                UnitItem("km2", "км²", 1000000.0),
                UnitItem("ft2", "фут² (ft²)", 0.09290304)
            ),
            presets = listOf(
                PresetItem("сотка → м²", "sotka", "m2"),
                PresetItem("га → м²", "ha", "m2"),
                PresetItem("га → сотка", "ha", "sotka")
            )
        ),

        // 3. Объём
        UnitCategory(
            id = "volume",
            name = "Объём",
            units = listOf(
                UnitItem("ml", "мл (см³)", 0.001),
                UnitItem("liter", "литр (дм³)", 1.0),
                UnitItem("m3", "м³", 1000.0),
                UnitItem("glass", "стакан (250 мл)", 0.25),
                UnitItem("gallon", "галлон (US)", 3.785411784),
                UnitItem("barrel", "баррель", 158.987294928)
            ),
            presets = listOf(
                PresetItem("м³ → литр", "m3", "liter"),
                PresetItem("литр → мл", "liter", "ml"),
                PresetItem("галлон → литр", "gallon", "liter")
            )
        ),

        // 4. Масса
        UnitCategory(
            id = "mass",
            name = "Масса",
            units = listOf(
                UnitItem("mg", "мг", 0.000001),
                UnitItem("g", "г", 0.001),
                UnitItem("kg", "кг", 1.0),
                UnitItem("centner", "центнер (ц)", 100.0),
                UnitItem("ton", "тонна (т)", 1000.0),
                UnitItem("oz", "унция (oz)", 0.028349523125),
                UnitItem("lb", "фунт (lb)", 0.45359237),
                UnitItem("carat", "карат", 0.0002)
            ),
            presets = listOf(
                PresetItem("кг → г", "kg", "g"),
                PresetItem("т → кг", "ton", "kg"),
                PresetItem("фунт → кг", "lb", "kg"),
                PresetItem("ц → кг", "centner", "kg")
            )
        ),

        // 5. Скорость
        UnitCategory(
            id = "speed",
            name = "Скорость",
            units = listOf(
                UnitItem("ms", "м/с", 1.0),
                UnitItem("kmh", "км/ч", 1.0 / 3.6),
                UnitItem("mph", "миль/ч (mph)", 0.44704),
                UnitItem("knot", "узел (kn)", 1852.0 / 3600.0),
                UnitItem("mach", "Мах (звук)", 340.29)
            ),
            presets = listOf(
                PresetItem("км/ч → м/с", "kmh", "ms"),
                PresetItem("mph → км/ч", "mph", "kmh"),
                PresetItem("узел → км/ч", "knot", "kmh")
            )
        ),

        // 6. Температура
        UnitCategory(
            id = "temperature",
            name = "Температура",
            units = listOf(
                UnitItem("celsius", "Цельсий (°C)"),
                UnitItem("kelvin", "Кельвин (K)"),
                UnitItem("fahrenheit", "Фаренгейт (°F)")
            ),
            presets = listOf(
                PresetItem("°C → K", "celsius", "kelvin"),
                PresetItem("°C → °F", "celsius", "fahrenheit"),
                PresetItem("°F → °C", "fahrenheit", "celsius")
            )
        ),

        // 7. Давление
        UnitCategory(
            id = "pressure",
            name = "Давление",
            units = listOf(
                UnitItem("pa", "Па", 1.0),
                UnitItem("kpa", "кПа", 1000.0),
                UnitItem("mpa", "МПа", 1000000.0),
                UnitItem("bar", "бар", 100000.0),
                UnitItem("atm", "атм", 101325.0),
                UnitItem("mmhg", "мм рт. ст.", 101325.0 / 760.0),
                UnitItem("psi", "psi", 6894.757293168)
            ),
            presets = listOf(
                PresetItem("мм рт. ст. → Па", "mmhg", "pa"),
                PresetItem("атм → Па", "atm", "pa"),
                PresetItem("бар → кПа", "bar", "kpa")
            )
        ),

        // 8. Энергия
        UnitCategory(
            id = "energy",
            name = "Энергия",
            units = listOf(
                UnitItem("j", "Дж", 1.0),
                UnitItem("kj", "кДж", 1000.0),
                UnitItem("mj", "МДж", 1000000.0),
                UnitItem("cal", "кал", 4.184),
                UnitItem("kcal", "ккал", 4184.0),
                UnitItem("wh", "Вт·ч", 3600.0),
                UnitItem("kwh", "кВт·ч", 3600000.0),
                UnitItem("ev", "эВ", 1.602176634e-19)
            ),
            presets = listOf(
                PresetItem("ккал → кДж", "kcal", "kj"),
                PresetItem("кВт·ч → МДж", "kwh", "mj"),
                PresetItem("эВ → Дж", "ev", "j")
            )
        ),

        // 9. Время
        UnitCategory(
            id = "time",
            name = "Время",
            units = listOf(
                UnitItem("ms", "мс", 0.001),
                UnitItem("sec", "сек", 1.0),
                UnitItem("min", "мин", 60.0),
                UnitItem("hour", "час", 3600.0),
                UnitItem("day", "сутки", 86400.0),
                UnitItem("week", "неделя", 604800.0),
                UnitItem("year", "год (365 дн)", 31536000.0)
            ),
            presets = listOf(
                PresetItem("мин → сек", "min", "sec"),
                PresetItem("час → мин", "hour", "min"),
                PresetItem("сутки → час", "day", "hour")
            )
        ),

        // 10. Данные (ЕГЭ)
        UnitCategory(
            id = "data",
            name = "Данные (ЕГЭ)",
            units = listOf(
                UnitItem("bit", "бит", 0.125),
                UnitItem("byte", "Байт", 1.0),
                UnitItem("kb", "КБ (10³)", 1000.0),
                UnitItem("mb", "МБ (10³)", 1000000.0),
                UnitItem("gb", "ГБ (10³)", 1000000000.0),
                UnitItem("tb", "ТБ (10³)", 1000000000000.0),
                UnitItem("kib", "КиБ (1024)", 1024.0),
                UnitItem("mib", "МиБ (1024)", 1048576.0),
                UnitItem("gib", "ГиБ (1024)", 1073741824.0)
            ),
            presets = listOf(
                PresetItem("МиБ → Байт", "mib", "byte"),
                PresetItem("КБ → Байт", "kb", "byte"),
                PresetItem("ГБ → МБ", "gb", "mb"),
                PresetItem("бит → Байт", "bit", "byte")
            )
        )
    )

    fun getCategory(categoryId: String): UnitCategory? {
        return categories.find { it.id == categoryId }
    }

    fun convertBase(input: String, fromBase: Int, toBase: Int, maxFractionDigits: Int = 12): String {
        require(fromBase in 2..36) { "fromBase must be between 2 and 36" }
        require(toBase in 2..36) { "toBase must be between 2 and 36" }

        val rawTrimmed = input.trim()
            .replace(',', '.')
            .replace('−', '-')
            .uppercase(Locale.US)

        if (rawTrimmed.isEmpty() || rawTrimmed == "-") {
            return ""
        }

        val isNegative = rawTrimmed.startsWith("-")
        val positiveInput = if (isNegative) rawTrimmed.substring(1) else rawTrimmed

        if (positiveInput.isEmpty() || positiveInput == ".") {
            return ""
        }

        val parts = positiveInput.split('.')
        if (parts.size > 2) {
            throw IllegalArgumentException("Недопустимый символ для основания $fromBase")
        }

        val intPartStr = parts[0]
        val fracPartStr = if (parts.size == 2) parts[1] else null

        fun charToValue(c: Char): Int {
            return when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'Z' -> c - 'A' + 10
                else -> -1
            }
        }

        for (c in intPartStr) {
            val valDigit = charToValue(c)
            if (valDigit < 0 || valDigit >= fromBase) {
                throw IllegalArgumentException("Недопустимый символ для основания $fromBase")
            }
        }

        if (fracPartStr != null) {
            for (c in fracPartStr) {
                val valDigit = charToValue(c)
                if (valDigit < 0 || valDigit >= fromBase) {
                    throw IllegalArgumentException("Недопустимый символ для основания $fromBase")
                }
            }
        }

        val intPartBig = if (intPartStr.isEmpty()) {
            BigInteger.ZERO
        } else {
            BigInteger(intPartStr, fromBase)
        }
        val intPartConverted = intPartBig.toString(toBase).uppercase(Locale.US)

        var fracPartConverted = ""
        if (fracPartStr != null && fracPartStr.isNotEmpty()) {
            val fromBaseBig = BigInteger.valueOf(fromBase.toLong())
            var num = BigInteger.ZERO
            for (c in fracPartStr) {
                val d = charToValue(c)
                num = num.multiply(fromBaseBig).add(BigInteger.valueOf(d.toLong()))
            }
            val den = fromBaseBig.pow(fracPartStr.length)

            if (num > BigInteger.ZERO) {
                val toBaseBig = BigInteger.valueOf(toBase.toLong())
                val sb = StringBuilder()
                while (num > BigInteger.ZERO && sb.length < maxFractionDigits) {
                    num = num.multiply(toBaseBig)
                    val qr = num.divideAndRemainder(den)
                    val q = qr[0].toInt()
                    num = qr[1]

                    val digitChar = if (q < 10) ('0' + q) else ('A' + (q - 10))
                    sb.append(digitChar)
                }
                var endIdx = sb.length
                while (endIdx > 0 && sb[endIdx - 1] == '0') {
                    endIdx--
                }
                fracPartConverted = sb.substring(0, endIdx)
            }
        }

        val rawResult = if (fracPartConverted.isNotEmpty()) {
            "$intPartConverted.$fracPartConverted"
        } else if (positiveInput.endsWith(".") && parts.size == 2 && parts[1].isEmpty()) {
            "$intPartConverted."
        } else {
            intPartConverted
        }

        val isZeroResult = rawResult == "0" || rawResult == "0."
        return if (isNegative && !isZeroResult) "-$rawResult" else rawResult
    }

    fun convertAllBase(input: String, fromUnitId: String): List<ConvertedUnitItem> {
        val cat = getCategory("numeral_systems") ?: return emptyList()
        val fromUnit = cat.units.find { it.id == fromUnitId } ?: cat.units.first()
        return cat.units.map { unit ->
            val formatted = try {
                convertBase(input, fromUnit.base, unit.base)
            } catch (_: IllegalArgumentException) {
                "Символы вне основания ${fromUnit.base}"
            }
            ConvertedUnitItem(
                unit = unit,
                rawValue = 0.0,
                formattedValue = formatted
            )
        }
    }

    fun convert(value: Double, fromUnitId: String, toUnitId: String, categoryId: String): Double {
        if (value.isNaN() || value.isInfinite()) return value
        val cat = getCategory(categoryId) ?: return value
        if (fromUnitId == toUnitId) return value

        if (cat.id == "numeral_systems") {
            val fromUnit = cat.units.find { it.id == fromUnitId } ?: cat.units.first()
            val toUnit = cat.units.find { it.id == toUnitId } ?: cat.units.first()
            val inputStr = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            val resStr = try {
                convertBase(inputStr, fromUnit.base, toUnit.base)
            } catch (_: Exception) {
                "0"
            }
            return resStr.toDoubleOrNull() ?: 0.0
        } else if (cat.id == "temperature") {
            // Affine conversions
            val celsius = when (fromUnitId) {
                "celsius" -> value
                "kelvin" -> value - 273.15
                "fahrenheit" -> (value - 32.0) * 5.0 / 9.0
                else -> value
            }
            return when (toUnitId) {
                "celsius" -> celsius
                "kelvin" -> celsius + 273.15
                "fahrenheit" -> celsius * 9.0 / 5.0 + 32.0
                else -> celsius
            }
        } else {
            val fromUnit = cat.units.find { it.id == fromUnitId } ?: return value
            val toUnit = cat.units.find { it.id == toUnitId } ?: return value

            val baseValue = value * fromUnit.factorToBase
            return baseValue / toUnit.factorToBase
        }
    }

    fun convertAll(value: Double, fromUnitId: String, categoryId: String): List<ConvertedUnitItem> {
        val cat = getCategory(categoryId) ?: return emptyList()
        if (cat.id == "numeral_systems") {
            val fromUnit = cat.units.find { it.id == fromUnitId } ?: cat.units.first()
            val inputStr = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            return cat.units.map { unit ->
                val formatted = try {
                    convertBase(inputStr, fromUnit.base, unit.base)
                } catch (_: Exception) {
                    "0"
                }
                ConvertedUnitItem(
                    unit = unit,
                    rawValue = formatted.toDoubleOrNull() ?: 0.0,
                    formattedValue = formatted
                )
            }
        }
        return cat.units.map { unit ->
            val raw = convert(value, fromUnitId, unit.id, categoryId)
            ConvertedUnitItem(
                unit = unit,
                rawValue = raw,
                formattedValue = formatValue(raw)
            )
        }
    }

    fun formatValue(value: Double): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
        if (value == 0.0) return "0"

        val absVal = abs(value)

        // Extremely small or large numbers: use scientific notation
        if ((absVal < 1e-6 && absVal > 0) || absVal >= 1e9) {
            val formatted = String.format(Locale.US, "%.6e", value)
            return formatted.replace("e+", "e").replace("e-0", "e-").replace("e0", "e")
        }

        // Standard range: use BigDecimal to clean up floating point artifacts
        return try {
            val bd = BigDecimal.valueOf(value)
                .setScale(8, RoundingMode.HALF_UP)
                .stripTrailingZeros()
            bd.toPlainString()
        } catch (_: Exception) {
            value.toString()
        }
    }
}
