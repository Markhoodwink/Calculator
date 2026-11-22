package com.example.calculator

import com.example.calculator.CasEngine.Fraction
import com.example.calculator.CasEngine.Node
import kotlin.math.*

/**
 * Символьный и численный движок для решения тригонометрических уравнений
 * по спецификации ЕГЭ (Задание 13 / C3) с отбором корней на отрезке [a, b].
 */
object TrigSolverEngine {

    data class SingleSeries(
        val baseAngleRad: Double,
        val periodRad: Double,
        val baseFracPi: Fraction?,
        val periodFracPi: Fraction?,
        val symbolicFormula: String? = null
    ) {
        fun circleAngles(): List<Double> {
            val angles = mutableListOf<Double>()
            val p = periodRad
            if (p <= 1e-9) return listOf(normalizeAngle(baseAngleRad))
            val count = (2 * Math.PI / p).roundToInt().coerceAtLeast(1)
            for (i in 0 until count) {
                val ang = normalizeAngle(baseAngleRad + i * p)
                if (angles.none { abs(it - ang) < 1e-5 }) {
                    angles.add(ang)
                }
            }
            return angles.sorted()
        }

        companion object {
            fun normalizeAngle(rad: Double): Double {
                var a = rad % (2 * Math.PI)
                if (a < -1e-9) a += 2 * Math.PI
                if (abs(a - 2 * Math.PI) < 1e-9 || abs(a) < 1e-9) a = 0.0
                return a
            }
        }
    }

    data class TrigRootSeries(
        val displayFormula: String,
        val seriesList: List<SingleSeries>
    )

    data class IntervalRoot(
        val value: Double,
        val symbolic: String,
        val angleOnCircle: Double
    )

    data class TrigStep(
        val section: String,
        val title: String,
        val formula: String,
        val explanation: String
    )

    data class TrigSolveResult(
        val generalSolutions: List<TrigRootSeries>,
        val rootsOnInterval: List<IntervalRoot> = emptyList(),
        val steps: List<String> = emptyList(),
        val detailedSteps: List<TrigStep> = emptyList(),
        val errorMessage: String? = null
    )

    data class LinearArg(
        val k: Fraction,
        val b: Fraction = Fraction(0, 1),
        val isPiMultipleB: Boolean = false
    )

    /**
     * Основная точка входа для решения тригонометрического уравнения.
     */
    fun solve(
        equation: String,
        intervalStartStr: String? = null,
        intervalEndStr: String? = null,
        casEngine: CasEngine = CasEngine()
    ): TrigSolveResult {
        val trimmed = equation.trim()
        if (trimmed.isEmpty()) {
            return TrigSolveResult(emptyList(), emptyList(), emptyList(), emptyList(), "Введите уравнение")
        }

        casEngine.isDegreeMode = false
        val steps = mutableListOf<String>()
        val detailedSteps = mutableListOf<TrigStep>()
        steps.add("Исходное уравнение: $trimmed")

        return try {
            // 1. Нормализация строки уравнения
            val normalized = normalizeEquationString(trimmed)
            val parts = normalized.split("=")
            val leftStr = parts[0].trim()
            val rightStr = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].trim() else "0"

            val leftAst = CasEngine.Parser(leftStr).parse()
            val rightAst = CasEngine.Parser(rightStr).parse()
            val diffAst = casEngine.simplify(Node.Sub(leftAst, rightAst))

            steps.add("Приведение к виду f(x) = 0: ${diffAst.toExprString()} = 0")

            // ОДЗ
            detectAndAddOdzSteps(trimmed, leftAst, rightAst, detailedSteps)

            // 2. Решение уравнения (символьное + универсальный сканирующий solver с ОДЗ)
            var generalSeries = solveDiffAst(diffAst, casEngine, steps, detailedSteps)
            if (generalSeries.isEmpty()) {
                generalSeries = solveByPeriodAnalysis(leftAst, rightAst, casEngine, steps, detailedSteps)
            }

            if (generalSeries.isEmpty()) {
                return TrigSolveResult(emptyList(), emptyList(), steps, deduplicateTrigSteps(detailedSteps), "Нет действительных корней")
            }

            // 3. Отбор корней на отрезке [a, b], если задан
            var intervalRoots = emptyList<IntervalRoot>()
            val allSingleSeries = generalSeries.flatMap { it.seriesList }

            if (!intervalStartStr.isNullOrBlank() && !intervalEndStr.isNullOrBlank()) {
                val startParsed = parseIntervalBound(intervalStartStr, casEngine)
                val endParsed = parseIntervalBound(intervalEndStr, casEngine)
                if (startParsed != null && endParsed != null) {
                    val a = minOf(startParsed.first, endParsed.first)
                    val b = maxOf(startParsed.first, endParsed.first)
                    steps.add("Отбор корней на отрезке [${startParsed.second?.let { formatFracPi(it) } ?: startParsed.first}; ${endParsed.second?.let { formatFracPi(it) } ?: endParsed.first}]")
                    intervalRoots = filterRootsOnInterval(allSingleSeries, a, b)

                    val pBSteps = generateIntervalSteps(generalSeries, a, b, startParsed, endParsed)
                    detailedSteps.addAll(pBSteps)
                }
            }

            TrigSolveResult(generalSeries, intervalRoots, steps, deduplicateTrigSteps(detailedSteps))
        } catch (e: Exception) {
            TrigSolveResult(emptyList(), emptyList(), steps, deduplicateTrigSteps(detailedSteps), "Ошибка: ${e.message ?: "не удалось решить"}")
        }
    }

    /**
     * Препроцессинг строки уравнения: замена Cyrillic, tg, ctg, sin^2(x) -> (sin(x))^2, степеней и пробелов.
     */
    fun normalizeEquationString(raw: String): String {
        var s = raw.trim()
            .replace(" ", "")
            .replace("−", "-")
            .replace("·", "*")
            .replace("×", "*")
            .replace("÷", "/")
            .replace("п", "π")
            .replace("П", "π")
            .replace("с", "c")
            .replace("С", "c")
            .replace("х", "x")
            .replace("Х", "x")
            .replace("ctg(", "cot(")
            .replace("tg(", "tan(")
            .replace("ctg", "cot")
            .replace("tg", "tan")

        // sin²(x) или sin^2(x) -> (sin(x))^2
        val supMap = mapOf('⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5')
        for ((sup, dig) in supMap) {
            s = s.replace("sin$sup(", "(sin(").replace("cos$sup(", "(cos(").replace("tan$sup(", "(tan(").replace("cot$sup(", "(cot(")
        }

        // Regex для sin^2(arg) -> (sin(arg))^2
        val powRegex = Regex("""(sin|cos|tan|cot)\^([0-9]+)\(([^)]+)\)""")
        s = powRegex.replace(s) { m ->
            val fn = m.groupValues[1]
            val p = m.groupValues[2]
            val arg = m.groupValues[3]
            "($fn($arg))^$p"
        }

        // Regex для sin²(arg) -> (sin(arg))^2
        val unicodePowRegex = Regex("""(sin|cos|tan|cot)[²³]\(([^)]+)\)""")
        s = unicodePowRegex.replace(s) { m ->
            val fn = m.groupValues[1]
            val arg = m.groupValues[2]
            "($fn($arg))^2"
        }

        // Также sin^2 x -> (sin(x))^2
        val powVarRegex = Regex("""(sin|cos|tan|cot)\^([0-9]+)([a-zA-Z])""")
        s = powVarRegex.replace(s) { m ->
            val fn = m.groupValues[1]
            val p = m.groupValues[2]
            val arg = m.groupValues[3]
            "($fn($arg))^$p"
        }

        s = replaceCotAndDivisions(s)
        return s
    }

    private fun replaceCotAndDivisions(input: String): String {
        var s = input
        var changed = true
        while (changed) {
            changed = false

            // 1. Check for 1/tan(arg) or (1)/(tan(arg)) or 1/(tan(arg))
            var idxTan = s.indexOf("tan(")
            while (idxTan != -1) {
                val endTan = findMatchingParen(s, idxTan + 3)
                if (endTan != -1) {
                    val arg = s.substring(idxTan + 4, endTan)
                    val prefix = s.substring(0, idxTan).trimEnd()
                    val isDiv = prefix.endsWith("/") || prefix.endsWith("/(") || prefix.endsWith("/((")
                    if (isDiv) {
                        val slashIdx = prefix.lastIndexOf('/')
                        val numPart = prefix.substring(0, slashIdx).trim()
                        if (numPart == "1" || numPart == "(1)" || numPart == "((1))" || numPart.endsWith("1") || numPart.endsWith("1)")) {
                            val startIdx = maxOf(0, prefix.lastIndexOf('1'))
                            val realStart = if (startIdx > 0 && prefix[startIdx - 1] == '(') {
                                if (startIdx > 1 && prefix[startIdx - 2] == '(') startIdx - 2 else startIdx - 1
                            } else startIdx
                            var endIdx = endTan
                            if (endIdx + 1 < s.length && s[endIdx + 1] == ')') {
                                endIdx++
                                if (endIdx + 1 < s.length && s[endIdx + 1] == ')') {
                                    endIdx++
                                }
                            }
                            s = s.substring(0, realStart) + "(cos($arg)/sin($arg))" + s.substring(endIdx + 1)
                            changed = true
                            break
                        }
                    }
                }
                idxTan = s.indexOf("tan(", idxTan + 4)
            }
            if (changed) continue

            // 2. Check for 1/cot(arg) or (1)/(cot(arg))
            var idxCot = s.indexOf("cot(")
            while (idxCot != -1) {
                val endCot = findMatchingParen(s, idxCot + 3)
                if (endCot != -1) {
                    val arg = s.substring(idxCot + 4, endCot)
                    val prefix = s.substring(0, idxCot).trimEnd()
                    val isDiv = prefix.endsWith("/") || prefix.endsWith("/(") || prefix.endsWith("/((")
                    if (isDiv) {
                        val slashIdx = prefix.lastIndexOf('/')
                        val numPart = prefix.substring(0, slashIdx).trim()
                        if (numPart == "1" || numPart == "(1)" || numPart == "((1))" || numPart.endsWith("1") || numPart.endsWith("1)")) {
                            val startIdx = maxOf(0, prefix.lastIndexOf('1'))
                            val realStart = if (startIdx > 0 && prefix[startIdx - 1] == '(') {
                                if (startIdx > 1 && prefix[startIdx - 2] == '(') startIdx - 2 else startIdx - 1
                            } else startIdx
                            var endIdx = endCot
                            if (endIdx + 1 < s.length && s[endIdx + 1] == ')') {
                                endIdx++
                                if (endIdx + 1 < s.length && s[endIdx + 1] == ')') {
                                    endIdx++
                                }
                            }
                            s = s.substring(0, realStart) + "(sin($arg)/cos($arg))" + s.substring(endIdx + 1)
                            changed = true
                            break
                        }
                    }
                }
                idxCot = s.indexOf("cot(", idxCot + 4)
            }
            if (changed) continue

            // 3. Check for bare cot(arg)
            idxCot = s.indexOf("cot(")
            if (idxCot != -1) {
                val endCot = findMatchingParen(s, idxCot + 3)
                if (endCot != -1) {
                    val arg = s.substring(idxCot + 4, endCot)
                    s = s.substring(0, idxCot) + "(cos($arg)/sin($arg))" + s.substring(endCot + 1)
                    changed = true
                    continue
                }
            }
        }
        return s
    }

    private fun findMatchingParen(s: String, startIdx: Int): Int {
        if (startIdx >= s.length || s[startIdx] != '(') return -1
        var depth = 0
        for (i in startIdx until s.length) {
            if (s[i] == '(') depth++
            else if (s[i] == ')') {
                depth--
                if (depth == 0) return i
            }
        }
        return -1
    }

    /**
     * Разбор границы отрезка (например, -2π, -2.5п, -pi/2, 5π/2, 2.5π, 0).
     */
    fun parseIntervalBound(raw: String, casEngine: CasEngine): Pair<Double, Fraction?>? {
        val trimmed = raw.trim()
            .replace(" ", "")
            .replace("−", "-")
            .replace("·", "*")
            .replace("×", "*")
            .replace("п", "π")
            .replace("П", "π")
            .replace("pi", "π", ignoreCase = true)

        if (trimmed.isEmpty()) return null

        try {
            var frac: Fraction? = null
            var dVal: Double? = null

            if (trimmed.contains("π")) {
                if (trimmed.contains("π/")) {
                    val parts = trimmed.split("π/")
                    val numStr = parts[0].removeSuffix("*")
                    val denStr = parts.getOrNull(1) ?: "1"

                    val numFrac = when (numStr) {
                        "", "+" -> Fraction(1, 1)
                        "-" -> Fraction(-1, 1)
                        else -> {
                            val dec = Fraction.fromDecimalString(numStr)
                            if (dec.num == 0L && numStr != "0" && numStr != "0.0") {
                                val d = casEngine.evalNodeAtX(CasEngine.Parser(numStr).parse(), 0.0)
                                Fraction.fromDouble(d)
                            } else dec
                        }
                    }

                    val denVal = denStr.toDoubleOrNull() ?: casEngine.evalNodeAtX(CasEngine.Parser(denStr).parse(), 0.0)
                    if (denVal == 0.0 || denVal.isNaN() || denVal.isInfinite()) return null

                    val f = (numFrac / Fraction.fromDouble(denVal)).simplify()
                    frac = f
                    dVal = f.toDouble() * Math.PI
                } else if (trimmed.endsWith("π")) {
                    val prefix = trimmed.dropLast(1).removeSuffix("*")
                    val f = when (prefix) {
                        "", "+" -> Fraction(1, 1)
                        "-" -> Fraction(-1, 1)
                        else -> {
                            val dec = Fraction.fromDecimalString(prefix)
                            if (dec.num == 0L && prefix != "0" && prefix != "0.0") {
                                val d = casEngine.evalNodeAtX(CasEngine.Parser(prefix).parse(), 0.0)
                                Fraction.fromDouble(d)
                            } else dec
                        }
                    }.simplify()
                    frac = f
                    dVal = f.toDouble() * Math.PI
                } else {
                    val exprNoPi = trimmed.replace("π", "*(3.1415926535897933)")
                    val ast = CasEngine.Parser(exprNoPi).parse()
                    val d = casEngine.evalNodeAtX(ast, 0.0)
                    if (d.isNaN() || d.isInfinite()) return null
                    dVal = d
                    val f = Fraction.fromDouble(d / Math.PI)
                    if (abs(f.toDouble() * Math.PI - d) < 1e-6 && f.den <= 24L) {
                        frac = f
                    }
                }
            } else {
                val ast = CasEngine.Parser(trimmed).parse()
                val d = casEngine.evalNodeAtX(ast, 0.0)
                if (d.isNaN() || d.isInfinite()) return null
                dVal = d
                val f = Fraction.fromDouble(d / Math.PI)
                if (abs(f.toDouble() * Math.PI - d) < 1e-6 && f.den <= 24L) {
                    frac = f
                }
            }

            val finalD = dVal ?: return null
            if (finalD.isNaN() || finalD.isInfinite()) return null
            return Pair(finalD, frac)
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Форматирует дробь от π в красивую строку:
     * 0 -> "0", 1 -> "π", -1 -> "-π", 2 -> "2π", 1/6 -> "π/6", -5/6 -> "-5π/6".
     */
    fun formatFracPi(f: Fraction): String {
        val s = f.simplify()
        if (s.num == 0L) return "0"
        val sign = if (s.num < 0) "-" else ""
        val absNum = abs(s.num)
        val den = s.den
        return when {
            absNum == 1L && den == 1L -> "${sign}π"
            den == 1L -> "${sign}${absNum}π"
            absNum == 1L -> "${sign}π/$den"
            else -> "${sign}${absNum}π/$den"
        }
    }

    /**
     * Отбор корней на отрезке [a, b].
     */
    fun filterRootsOnInterval(
        seriesList: List<SingleSeries>,
        a: Double,
        b: Double
    ): List<IntervalRoot> {
        val minB = minOf(a, b)
        val maxB = maxOf(a, b)
        val roots = mutableListOf<IntervalRoot>()

        for (s in seriesList) {
            val base = s.baseAngleRad
            val t = s.periodRad
            if (t <= 1e-9) {
                if (base in (minB - 1e-7)..(maxB + 1e-7)) {
                    val sym = s.baseFracPi?.let { formatFracPi(it) } ?: CasEngine.Fraction.formatDoubleNorm1(base)
                    roots.add(IntervalRoot(base, sym, SingleSeries.normalizeAngle(base)))
                }
                continue
            }

            val kMin = floor((minB - base - 1e-7) / t).toInt() - 1
            val kMax = ceil((maxB - base + 1e-7) / t).toInt() + 1

            for (k in kMin..kMax) {
                val x = base + k * t
                if (x in (minB - 1e-7)..(maxB + 1e-7)) {
                    val sym = if (s.baseFracPi != null && s.periodFracPi != null) {
                        val totalFrac = s.baseFracPi + Fraction(k.toLong()) * s.periodFracPi
                        formatFracPi(totalFrac)
                    } else {
                        CasEngine.Fraction.formatDoubleNorm1(x)
                    }
                    val ang = SingleSeries.normalizeAngle(x)
                    roots.add(IntervalRoot(x, sym, ang))
                }
            }
        }

        val sorted = roots.sortedBy { it.value }
        val distinct = mutableListOf<IntervalRoot>()
        for (r in sorted) {
            if (distinct.none { abs(it.value - r.value) < 1e-6 }) {
                distinct.add(r)
            }
        }
        return distinct
    }

    fun formatFormulaUnicode(expr: String): String {
        var s = expr
            .replace("<=", " ≤ ")
            .replace(">=", " ≥ ")
            .replace("!=", " ≠ ")
            .replace("=>", " ⇒ ")
            .replace("->", " ⇒ ")
            .replace("sqrt", "√")
            .replace("^2", "²")
            .replace("^3", "³")
            .replace("pi", "π", ignoreCase = true)
            .replace("in Z", "∈ ℤ")

        s = s.replace("*", " · ")
        while (s.contains("  ")) {
            s = s.replace("  ", " ")
        }
        return s.trim()
    }

    private fun formatQuadPoly(a: Double, b: Double, c: Double): String {
        val sb = StringBuilder()
        if (a == 1.0) sb.append("t²")
        else if (a == -1.0) sb.append("-t²")
        else sb.append("${CasEngine.Fraction.formatDoubleNorm1(a)}t²")

        if (b > 0) {
            sb.append(if (b == 1.0) " + t" else " + ${CasEngine.Fraction.formatDoubleNorm1(b)}t")
        } else if (b < 0) {
            sb.append(if (b == -1.0) " - t" else " - ${CasEngine.Fraction.formatDoubleNorm1(abs(b))}t")
        }

        if (c > 0) {
            sb.append(" + ${CasEngine.Fraction.formatDoubleNorm1(c)}")
        } else if (c < 0) {
            sb.append(" - ${CasEngine.Fraction.formatDoubleNorm1(abs(c))}")
        }
        sb.append(" = 0")
        return sb.toString()
    }

    private fun formatKVal(v: Double): String {
        val roundedInt = v.roundToInt()
        if (abs(v - roundedInt) < 1e-6) return roundedInt.toString()
        val s = String.format(java.util.Locale.US, "%.2f", v)
        return s.removeSuffix("0").removeSuffix(".")
    }

    private fun detectAndAddOdzSteps(
        rawEq: String,
        leftAst: Node,
        rightAst: Node,
        detailedSteps: MutableList<TrigStep>
    ) {
        val lower = rawEq.lowercase()
        val hasCot = lower.contains("cot") || lower.contains("ctg") || lower.contains("/sin") || lower.contains("/ sin")
        val hasTan = lower.contains("tan") || lower.contains("tg") || lower.contains("/cos") || lower.contains("/ cos")

        val denNodes = mutableListOf<Node>()
        fun findDenominators(n: Node) {
            when (n) {
                is Node.Div -> {
                    denNodes.add(n.r)
                    findDenominators(n.l)
                    findDenominators(n.r)
                }
                is Node.Func -> {
                    if (n.fn == "tan") denNodes.add(Node.Func("cos", n.arg))
                    if (n.fn == "cot") denNodes.add(Node.Func("sin", n.arg))
                    findDenominators(n.arg)
                }
                is Node.Add -> { findDenominators(n.l); findDenominators(n.r) }
                is Node.Sub -> { findDenominators(n.l); findDenominators(n.r) }
                is Node.Mul -> { findDenominators(n.l); findDenominators(n.r) }
                is Node.Pow -> { findDenominators(n.base); findDenominators(n.exp) }
                is Node.Sqrt -> findDenominators(n.inner)
                else -> {}
            }
        }
        findDenominators(leftAst)
        findDenominators(rightAst)

        val hasSinDen = hasCot || denNodes.any { it is Node.Func && it.fn == "sin" }
        val hasCosDen = hasTan || denNodes.any { it is Node.Func && it.fn == "cos" }
        val otherDens = denNodes.filter { !(it is Node.Func && (it.fn == "sin" || it.fn == "cos")) && it.containsVar("x") }

        if (hasSinDen) {
            detailedSteps.add(
                TrigStep(
                    section = "ОДЗ",
                    title = "Шаг 1. Область допустимых значений (ОДЗ)",
                    formula = "sin(x) ≠ 0  ⇒  x ≠ πk, k ∈ ℤ",
                    explanation = "Выражение содержит ctg(x) или деление на sin(x), поэтому знаменатель sin(x) не должен равняться нулю."
                )
            )
        }

        if (hasCosDen) {
            detailedSteps.add(
                TrigStep(
                    section = "ОДЗ",
                    title = "Шаг 1. Область допустимых значений (ОДЗ)",
                    formula = "cos(x) ≠ 0  ⇒  x ≠ π/2 + πk, k ∈ ℤ",
                    explanation = "Выражение содержит tg(x) или деление на cos(x), поэтому знаменатель cos(x) не должен равняться нулю."
                )
            )
        }

        for (den in otherDens) {
            val exprStr = formatFormulaUnicode(den.toExprString())
            detailedSteps.add(
                TrigStep(
                    section = "ОДЗ",
                    title = "Шаг 1. Область допустимых значений (ОДЗ)",
                    formula = "$exprStr ≠ 0",
                    explanation = "Знаменатель дроби не может быть равен нулю."
                )
            )
        }
    }

    private fun generateIntervalSteps(
        generalSeries: List<TrigRootSeries>,
        a: Double,
        b: Double,
        startParsed: Pair<Double, Fraction?>,
        endParsed: Pair<Double, Fraction?>
    ): List<TrigStep> {
        val intervalSteps = mutableListOf<TrigStep>()
        val aStr = startParsed.second?.let { formatFracPi(it) } ?: CasEngine.Fraction.formatDoubleNorm1(a)
        val bStr = endParsed.second?.let { formatFracPi(it) } ?: CasEngine.Fraction.formatDoubleNorm1(b)

        val minB = minOf(a, b)
        val maxB = maxOf(a, b)

        val allSingle = generalSeries.flatMap { it.seriesList }
        for (s in allSingle) {
            val baseRad = s.baseAngleRad
            val periodRad = s.periodRad

            val seriesExpr = s.symbolicFormula ?: run {
                val bStrFrac = s.baseFracPi?.let { formatFracPi(it) } ?: formatExactAngle(baseRad)
                val pStrFrac = s.periodFracPi?.let {
                    when {
                        it == Fraction(1, 1) -> "πk"
                        it == Fraction(2, 1) -> "2πk"
                        it.den == 1L -> "${it.num}πk"
                        it.num == 1L -> "πk/${it.den}"
                        else -> "${it.num}πk/${it.den}"
                    }
                } ?: "${CasEngine.Fraction.formatDoubleNorm1(periodRad)}k"

                if (bStrFrac == "0") pStrFrac
                else "$bStrFrac + $pStrFrac"
            }

            if (periodRad <= 1e-9) {
                val inRange = baseRad in (minB - 1e-7)..(maxB + 1e-7)
                val sym = s.baseFracPi?.let { formatFracPi(it) } ?: formatExactAngle(baseRad)
                intervalSteps.add(
                    TrigStep(
                        section = "Пункт б",
                        title = "Отбор корней на отрезке",
                        formula = "$aStr ≤ $sym ≤ $bStr",
                        explanation = if (inRange) "Значение x = $sym входит в отрезок [$aStr; $bStr]." else "Значение x = $sym не входит в отрезок [$aStr; $bStr]."
                    )
                )
                continue
            }

            val kMinExact = (minB - baseRad) / periodRad
            val kMaxExact = (maxB - baseRad) / periodRad

            val kLower = minOf(kMinExact, kMaxExact)
            val kUpper = maxOf(kMinExact, kMaxExact)

            val kIntMin = ceil(kLower - 1e-7).toInt()
            val kIntMax = floor(kUpper + 1e-7).toInt()

            val kBoundsStr = "${formatKVal(kLower)} ≤ k ≤ ${formatKVal(kUpper)}"

            val validKList = if (kIntMin <= kIntMax) (kIntMin..kIntMax).toList() else emptyList()
            val matchingKStr = if (validKList.isNotEmpty()) {
                "k ∈ {${validKList.joinToString(", ")}}"
            } else {
                "Подходящих целых значений k нет"
            }

            val doubleInequalityStr = "$aStr ≤ $seriesExpr ≤ $bStr"
            val fullFormula = "$doubleInequalityStr  ⇒  $kBoundsStr  ⇒  $matchingKStr"

            val explanationStr = if (validKList.isNotEmpty()) {
                val subList = validKList.map { k ->
                    val xVal = baseRad + k * periodRad
                    val xSym = if (s.baseFracPi != null && s.periodFracPi != null) {
                        val totalFrac = s.baseFracPi + Fraction(k.toLong()) * s.periodFracPi
                        formatFracPi(totalFrac)
                    } else {
                        formatExactAngle(xVal)
                    }
                    "k = $k ⇒ x = $xSym"
                }
                subList.joinToString("; ")
            } else {
                "Подходящих целых значений k нет"
            }

            intervalSteps.add(
                TrigStep(
                    section = "Пункт б",
                    title = "Отбор корней на отрезке",
                    formula = fullFormula,
                    explanation = explanationStr
                )
            )
        }

        return intervalSteps
    }

    private fun deduplicateTrigSteps(steps: List<TrigStep>): List<TrigStep> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<TrigStep>()
        for (s in steps) {
            val key = "${s.section}|${s.title}|${s.formula}|${s.explanation}"
            if (seen.add(key)) {
                result.add(s)
            }
        }
        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // УНИВЕРСАЛЬНЫЙ СКАННИРУЮЩИЙ SOLVER (SOLVE BY PERIOD ANALYSIS) С ОДЗ
    // ─────────────────────────────────────────────────────────────────────────

    fun solveByPeriodAnalysis(
        leftAst: Node,
        rightAst: Node,
        casEngine: CasEngine,
        steps: MutableList<String>,
        detailedSteps: MutableList<TrigStep> = mutableListOf()
    ): List<TrigRootSeries> {
        val diffAst = casEngine.simplify(Node.Sub(leftAst, rightAst))

        fun evalLHS(x: Double): Double = casEngine.evalNodeAtX(leftAst, x)
        fun evalRHS(x: Double): Double = casEngine.evalNodeAtX(rightAst, x)

        // Сбор знаменателей для проверки ОДЗ
        val denNodes = mutableListOf<Node>()
        fun findDenominators(n: Node) {
            when (n) {
                is Node.Div -> {
                    denNodes.add(n.r)
                    findDenominators(n.l)
                    findDenominators(n.r)
                }
                is Node.Func -> {
                    if (n.fn == "tan") denNodes.add(Node.Func("cos", n.arg))
                    if (n.fn == "cot") denNodes.add(Node.Func("sin", n.arg))
                    findDenominators(n.arg)
                }
                is Node.Add -> { findDenominators(n.l); findDenominators(n.r) }
                is Node.Sub -> { findDenominators(n.l); findDenominators(n.r) }
                is Node.Mul -> { findDenominators(n.l); findDenominators(n.r) }
                is Node.Pow -> { findDenominators(n.base); findDenominators(n.exp) }
                is Node.Sqrt -> findDenominators(n.inner)
                else -> {}
            }
        }
        findDenominators(leftAst)
        findDenominators(rightAst)

        var numAst: Node = diffAst
        for (den in denNodes) {
            numAst = Node.Mul(numAst, den)
        }
        val simplifiedNum = casEngine.simplify(numAst)

        fun evalF(x: Double): Double {
            val d = casEngine.evalNodeAtX(simplifiedNum, x)
            if (!d.isNaN() && !d.isInfinite() && abs(d) < 100.0) return d
            return casEngine.evalNodeAtX(diffAst, x)
        }

        fun checkOdz(x: Double): Boolean {
            val lhsVal = evalLHS(x)
            val rhsVal = evalRHS(x)
            if (lhsVal.isNaN() || lhsVal.isInfinite() || rhsVal.isNaN() || rhsVal.isInfinite()) return false
            if (abs(lhsVal - rhsVal) > 1e-5) return false

            for (den in denNodes) {
                val dVal = casEngine.evalNodeAtX(den, x)
                if (dVal.isNaN() || dVal.isInfinite() || abs(dVal) <= 1e-6) return false
            }
            return true
        }

        val N = 4096
        val eps = 1e-7
        val xVals = DoubleArray(N + 1)
        val yVals = DoubleArray(N + 1)
        val step = 2 * Math.PI / N
        for (i in 0..N) {
            val x = -eps + i * step
            xVals[i] = x
            yVals[i] = evalF(x)
        }

        val rawCandidateRoots = mutableListOf<Double>()

        for (i in 0 until N) {
            val x1 = xVals[i]
            val x2 = xVals[i + 1]
            val y1 = yVals[i]
            val y2 = yVals[i + 1]

            // Исключение полюсов / асимптот
            if (y1.isNaN() || y2.isNaN() || abs(y1) > 100.0 || abs(y2) > 100.0 || abs(y2 - y1) > 50.0) {
                continue
            }

            if (y1 * y2 <= 0.0) {
                var a = x1
                var b = x2
                var fa = y1
                var fb = y2
                for (iter in 0 until 60) {
                    val mid = (a + b) / 2.0
                    val fmid = evalF(mid)
                    if (abs(fmid) < 1e-12 || (b - a) < 1e-12) {
                        a = mid
                        break
                    }
                    if (fa * fmid <= 0) {
                        b = mid
                        fb = fmid
                    } else {
                        a = mid
                        fa = fmid
                    }
                }
                rawCandidateRoots.add((a + b) / 2.0)
            } else if (abs(y1) < 1e-4) {
                val yPrev = if (i > 0) yVals[i - 1] else yVals[N - 1]
                val yNext = y2
                if (!yPrev.isNaN() && !yNext.isNaN() && abs(y1) <= abs(yPrev) && abs(y1) <= abs(yNext)) {
                    rawCandidateRoots.add(x1)
                }
            }
        }

        val validRoots = mutableListOf<Double>()
        val rejectedRoots = mutableListOf<Double>()
        for (rawR in rawCandidateRoots) {
            var r = rawR % (2 * Math.PI)
            if (r < -1e-9) r += 2 * Math.PI
            if (abs(r - 2 * Math.PI) < 1e-8 || abs(r) < 1e-8) r = 0.0

            if (checkOdz(r)) {
                if (validRoots.none { abs(it - r) < 1e-5 }) {
                    validRoots.add(r)
                }
            } else {
                if (rejectedRoots.none { abs(it - r) < 1e-5 }) {
                    rejectedRoots.add(r)
                }
            }
        }

        // Проверка точек устранимых разрывов / нулей числителя, не входящих в ОДЗ (0, π/2, π, 3π/2)
        val odzCandidates = listOf(0.0, Math.PI / 2.0, Math.PI, 3.0 * Math.PI / 2.0)
        for (cand in odzCandidates) {
            if (!checkOdz(cand)) {
                val numVal = casEngine.evalNodeAtX(simplifiedNum, cand)
                if (!numVal.isNaN() && !numVal.isInfinite() && abs(numVal) < 1e-4) {
                    if (rejectedRoots.none { abs(it - cand) < 1e-5 }) {
                        rejectedRoots.add(cand)
                    }
                }
            }
        }

        for (rej in rejectedRoots) {
            val angleStr = formatExactAngle(rej)
            val funcStr = if (abs(sin(rej)) < 1e-5) "sin($angleStr) = 0"
            else if (abs(cos(rej)) < 1e-5) "cos($angleStr) = 0"
            else "знаменатель равен 0"

            detailedSteps.add(
                TrigStep(
                    section = "Пункт а",
                    title = "Исключение постороннего корня по ОДЗ",
                    formula = "x = $angleStr  ⇒  посторонний корень ($funcStr)",
                    explanation = "Значение x = $angleStr не принадлежит области допустимых значений ($funcStr) и исключается."
                )
            )
        }

        validRoots.sort()
        if (validRoots.isEmpty()) return emptyList()

        detailedSteps.add(
            TrigStep(
                section = "Пункт а",
                title = "Корни на основном периоде [0; 2π)",
                formula = "x ∈ {${validRoots.joinToString { formatExactAngle(it) }}}",
                explanation = "Значения на отрезке [0; 2π), удовлетворяющие области допустимых значений."
            )
        )

        steps.add("Численный отбор с ОДЗ на [0, 2π): ${validRoots.joinToString { formatExactAngle(it) }}")
        val generalSeries = groupAndFormatRoots(validRoots, steps)
        if (generalSeries.isNotEmpty()) {
            detailedSteps.add(
                TrigStep(
                    section = "Пункт а",
                    title = "Общее решение (объединение в серии)",
                    formula = generalSeries.joinToString("; ") { it.displayFormula },
                    explanation = "Корни объединены в тригонометрические серии с периодами πk и 2πk."
                )
            )
        }

        return generalSeries
    }

    fun recognizeRationalPi(rad: Double): Fraction? {
        val fracPi = Fraction.fromDouble(rad / Math.PI).simplify()
        if (fracPi.den <= 24L && abs(fracPi.toDouble() * Math.PI - rad) < 1e-6) {
            return fracPi
        }
        for (q in listOf(1L, 2L, 3L, 4L, 6L, 8L, 12L, 24L)) {
            val p = (rad / Math.PI * q).roundToLong()
            if (abs((p.toDouble() / q) * Math.PI - rad) < 1e-6) {
                return Fraction(p, q).simplify()
            }
        }
        return null
    }

    fun formatExactAngle(rad: Double): String {
        val frac = recognizeRationalPi(rad)
        if (frac != null) {
            return formatFracPi(frac)
        }
        return CasEngine.Fraction.formatDoubleNorm1(rad)
    }

    private fun groupAndFormatRoots(
        roots: List<Double>,
        steps: MutableList<String>
    ): List<TrigRootSeries> {
        val n = roots.size
        val used = BooleanArray(n)
        val result = mutableListOf<TrigRootSeries>()

        // 0. πk/2 merging: 4 roots r_i, r_i + π/2, r_i + π, r_i + 3π/2
        for (i in 0 until n) {
            if (used[i]) continue
            val rI = roots[i]
            var matchHalf1 = -1
            var matchPi = -1
            var matchHalf3 = -1
            for (j in 0 until n) {
                if (j == i || used[j]) continue
                val diff = SingleSeries.normalizeAngle(roots[j] - rI)
                if (abs(diff - Math.PI / 2.0) < 1e-5) matchHalf1 = j
                else if (abs(diff - Math.PI) < 1e-5) matchPi = j
                else if (abs(diff - 3.0 * Math.PI / 2.0) < 1e-5) matchHalf3 = j
            }
            if (matchHalf1 != -1 && matchPi != -1 && matchHalf3 != -1) {
                used[i] = true
                used[matchHalf1] = true
                used[matchPi] = true
                used[matchHalf3] = true

                val baseFrac = recognizeRationalPi(rI)
                val formula = if (baseFrac != null) {
                    if (baseFrac.num == 0L) "x = πk/2, k ∈ ℤ"
                    else "x = ${formatFracPi(baseFrac)} + πk/2, k ∈ ℤ"
                } else {
                    "x = ${formatExactAngle(rI)} + πk/2, k ∈ ℤ"
                }
                val s = SingleSeries(rI, Math.PI / 2.0, baseFrac, Fraction(1, 2))
                result.add(TrigRootSeries(formula, listOf(s)))
            }
        }

        // 1. πk merging: pairs r_j ≈ r_i + π
        for (i in 0 until n) {
            if (used[i]) continue
            val rI = roots[i]
            var matchJ = -1
            for (j in i + 1 until n) {
                if (!used[j] && abs((roots[j] - rI) - Math.PI) < 1e-5) {
                    matchJ = j
                    break
                }
            }
            if (matchJ != -1) {
                used[i] = true
                used[matchJ] = true
                val fracI = recognizeRationalPi(rI)
                val formula = if (fracI != null) {
                    if (fracI.num == 0L) "x = πk, k ∈ ℤ"
                    else "x = ${formatFracPi(fracI)} + πk, k ∈ ℤ"
                } else {
                    "x = ${formatExactAngle(rI)} + πk, k ∈ ℤ"
                }
                val s = SingleSeries(rI, Math.PI, fracI, Fraction(1, 1))
                result.add(TrigRootSeries(formula, listOf(s)))
            }
        }

        // 2. ± symmetry merging: pairs r_j ≈ 2π - r_i
        for (i in 0 until n) {
            if (used[i]) continue
            val rI = roots[i]
            var matchJ = -1
            for (j in i + 1 until n) {
                if (!used[j] && abs((roots[i] + roots[j]) - 2 * Math.PI) < 1e-5) {
                    matchJ = j
                    break
                }
            }
            if (matchJ != -1) {
                used[i] = true
                used[matchJ] = true
                val fracI = recognizeRationalPi(rI)
                val formula = if (fracI != null) {
                    "x = ±${formatFracPi(fracI)} + 2πk, k ∈ ℤ"
                } else {
                    "x = ±${formatExactAngle(rI)} + 2πk, k ∈ ℤ"
                }
                val s1 = SingleSeries(rI, 2 * Math.PI, fracI, Fraction(2, 1))
                val s2 = SingleSeries(-rI, 2 * Math.PI, fracI?.negate(), Fraction(2, 1))
                result.add(TrigRootSeries(formula, listOf(s1, s2)))
            }
        }

        // 3. Single unmerged series
        for (i in 0 until n) {
            if (used[i]) continue
            used[i] = true
            val rI = roots[i]
            val fracI = recognizeRationalPi(rI)
            val formula = if (fracI != null) {
                if (fracI.num == 0L) "x = 2πk, k ∈ ℤ"
                else "x = ${formatFracPi(fracI)} + 2πk, k ∈ ℤ"
            } else {
                "x = ${formatExactAngle(rI)} + 2πk, k ∈ ℤ"
            }
            val s = SingleSeries(rI, 2 * Math.PI, fracI, Fraction(2, 1))
            result.add(TrigRootSeries(formula, listOf(s)))
        }

        return deduplicateSeries(result)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // СИМВОЛЬНОЕ РЕШЕНИЕ И РЕДУКЦИЯ
    // ─────────────────────────────────────────────────────────────────────────

    private fun solveDiffAst(
        rawNode: Node,
        casEngine: CasEngine,
        steps: MutableList<String>,
        detailedSteps: MutableList<TrigStep>
    ): List<TrigRootSeries> {
        val reducedNode = applyReductionFormulas(rawNode, casEngine, steps, detailedSteps)
        if (reducedNode.toExprString() != rawNode.toExprString()) {
            steps.add("После применения формул приведения: ${reducedNode.toExprString()} = 0")
        }
        var node = reducedNode

        val directTrig = trySolveDirectTrigEquals(node, casEngine, steps, detailedSteps)
        if (directTrig != null) return directTrig

        val args = collectTrigArgs(node)

        val has2x = args.any { isMultipleOfArg(it, 2) }
        val has1x = args.any { isSimpleX(it) }

        if (has2x && has1x) {
            node = expandDoubleAngles(node, casEngine)
            steps.add("Раскрытие двойного угла sin(2x) = 2sin(x)cos(x): ${node.toExprString()} = 0")
            detailedSteps.add(
                TrigStep(
                    section = "Пункт а",
                    title = "Раскрытие двойного угла",
                    formula = "sin(2x) = 2sin(x)cos(x)  ⇒  ${formatFormulaUnicode(node.toExprString())} = 0",
                    explanation = "Применяем формулу синуса двойного угла sin(2x) = 2sin(x)cos(x)."
                )
            )
        }

        val factors = splitFactors(node)
        if (factors.size > 1) {
            steps.add("Разложение на множители: ${factors.joinToString(" · ") { "(${it.toExprString()})" }} = 0")
            detailedSteps.add(
                TrigStep(
                    section = "Пункт а",
                    title = "Разложение на множители",
                    formula = "${factors.joinToString(" · ") { "(${formatFormulaUnicode(it.toExprString())})" }} = 0",
                    explanation = "Раскладываем тригонометрическое выражение на множители."
                )
            )

            val splitEqs = factors.filter { it.containsVar("x") }
            val splitStr = splitEqs.joinToString("  или  ") { "${formatFormulaUnicode(it.toExprString())} = 0" }
            detailedSteps.add(
                TrigStep(
                    section = "Пункт а",
                    title = "Расщепление на простейшие уравнения",
                    formula = splitStr,
                    explanation = "Произведение равно нулю, когда хотя бы один из множителей равен нулю."
                )
            )

            val results = mutableListOf<TrigRootSeries>()
            for (f in factors) {
                if (f.containsVar("x")) {
                    results.addAll(solveSingleFactor(f, casEngine, steps, detailedSteps))
                }
            }
            return deduplicateSeries(results)
        }

        return solveSingleFactor(node, casEngine, steps, detailedSteps)
    }

    private fun solveSingleFactor(
        node: Node,
        casEngine: CasEngine,
        steps: MutableList<String>,
        detailedSteps: MutableList<TrigStep>
    ): List<TrigRootSeries> {
        val trigTypes = collectTrigTypes(node)
        val linearArg = detectPrimaryArg(node) ?: LinearArg(Fraction(1, 1), Fraction(0, 1))

        if (trigTypes.contains("sin") && !trigTypes.contains("cos") && !trigTypes.contains("tan")) {
            return solvePolyInTrig("sin", node, linearArg, casEngine, steps, detailedSteps)
        }

        if (trigTypes.contains("cos") && !trigTypes.contains("sin") && !trigTypes.contains("tan")) {
            return solvePolyInTrig("cos", node, linearArg, casEngine, steps, detailedSteps)
        }

        if (trigTypes.contains("tan") && !trigTypes.contains("sin") && !trigTypes.contains("cos")) {
            return solvePolyInTrig("tan", node, linearArg, casEngine, steps, detailedSteps)
        }

        if (trigTypes.contains("sin") && trigTypes.contains("cos")) {
            if (isOnlyEvenPowerOfCos(node)) {
                val replaced = replaceCos2WithSin2(node, casEngine)
                steps.add("Замена cos²(x) = 1 - sin²(x): ${replaced.toExprString()} = 0")
                detailedSteps.add(
                    TrigStep(
                        section = "Пункт а",
                        title = "Замена по основному тождеству",
                        formula = "cos²(x) = 1 - sin²(x)  ⇒  ${formatFormulaUnicode(replaced.toExprString())} = 0",
                        explanation = "Используем основное тригонометрическое тождество sin²(x) + cos²(x) = 1."
                    )
                )
                return solvePolyInTrig("sin", replaced, linearArg, casEngine, steps, detailedSteps)
            }

            if (isOnlyEvenPowerOfSin(node)) {
                val replaced = replaceSin2WithCos2(node, casEngine)
                steps.add("Замена sin²(x) = 1 - cos²(x): ${replaced.toExprString()} = 0")
                detailedSteps.add(
                    TrigStep(
                        section = "Пункт а",
                        title = "Замена по основному тождеству",
                        formula = "sin²(x) = 1 - cos²(x)  ⇒  ${formatFormulaUnicode(replaced.toExprString())} = 0",
                        explanation = "Используем основное тригонометрическое тождество sin²(x) + cos²(x) = 1."
                    )
                )
                return solvePolyInTrig("cos", replaced, linearArg, casEngine, steps, detailedSteps)
            }

            val factored = tryFactorCommonTrig(node, casEngine)
            if (factored != null) {
                val (f1, f2) = factored
                steps.add("Вынесение общего множителя: (${f1.toExprString()}) · (${f2.toExprString()}) = 0")
                detailedSteps.add(
                    TrigStep(
                        section = "Пункт а",
                        title = "Вынесение общего множителя",
                        formula = "(${formatFormulaUnicode(f1.toExprString())}) · (${formatFormulaUnicode(f2.toExprString())}) = 0",
                        explanation = "Выносим общий тригонометрический множитель за скобки."
                    )
                )
                val r1 = solveSingleFactor(f1, casEngine, steps, detailedSteps)
                val r2 = solveSingleFactor(f2, casEngine, steps, detailedSteps)
                return deduplicateSeries(r1 + r2)
            }

            val hom1 = trySolveHomogeneous1(node, linearArg, casEngine, steps, detailedSteps)
            if (hom1 != null) return hom1
        }

        val trySin = solvePolyInTrig("sin", node, linearArg, casEngine, steps, detailedSteps)
        if (trySin.isNotEmpty()) return trySin

        val tryCos = solvePolyInTrig("cos", node, linearArg, casEngine, steps, detailedSteps)
        if (tryCos.isNotEmpty()) return tryCos

        return emptyList()
    }

    private fun solvePolyInTrig(
        fn: String,
        node: Node,
        arg: LinearArg,
        casEngine: CasEngine,
        steps: MutableList<String>,
        detailedSteps: MutableList<TrigStep>
    ): List<TrigRootSeries> {
        val subbed = substituteTrigWithT(node, fn, casEngine)
        if (subbed.containsVar("x")) {
            return emptyList()
        }
        val poly = toPolynomial(subbed, "t") ?: return emptyList()

        val a2Node = poly[2]
        val a1Node = poly[1]
        val a0Node = poly[0]

        val a2D = a2Node?.let { casEngine.evalNodeAtX(it, 0.0) } ?: 0.0
        val a1D = a1Node?.let { casEngine.evalNodeAtX(it, 0.0) } ?: 0.0
        val a0D = a0Node?.let { casEngine.evalNodeAtX(it, 0.0) } ?: 0.0

        val a2Frac = (a2Node as? Node.Num)?.value
        val a1Frac = (a1Node as? Node.Num)?.value
        val a0Frac = (a0Node as? Node.Num)?.value

        data class RootT(val d: Double, val frac: Fraction?, val desc: String)
        val rootsT = mutableListOf<RootT>()

        if (abs(a2D) < 1e-9 && abs(a1D) < 1e-9) {
            return emptyList()
        }

        if (abs(a2D) < 1e-9 && abs(a1D) > 1e-9) {
            val rD = -a0D / a1D
            val rFrac = if (a0Frac != null && a1Frac != null) {
                (a0Frac.negate() / a1Frac).simplify()
            } else {
                val f = Fraction.fromDouble(rD)
                if (abs(f.toDouble() - rD) < 1e-7 && f.den <= 24L) f else null
            }
            val desc = rFrac?.toString() ?: CasEngine.Fraction.formatDoubleNorm1(rD)
            rootsT.add(RootT(rD, rFrac, desc))
            steps.add("Уравнение относительно t = $fn: t = $desc")
        }

        if (abs(a2D) > 1e-9) {
            val quadStr = formatQuadPoly(a2D, a1D, a0D)
            steps.add("Квадратное уравнение относительно t = $fn: $quadStr")
            detailedSteps.add(
                TrigStep(
                    section = "Пункт а",
                    title = "Квадратное уравнение относительно t = $fn(x)",
                    formula = quadStr,
                    explanation = "Вводим замену t = $fn(x) и решаем квадратное уравнение."
                )
            )

            if (abs(a0D) < 1e-9) {
                rootsT.add(RootT(0.0, Fraction(0, 1), "0"))
                val r2D = -a1D / a2D
                val r2Frac = if (a1Frac != null && a2Frac != null) {
                    (a1Frac.negate() / a2Frac).simplify()
                } else {
                    val f = Fraction.fromDouble(r2D)
                    if (abs(f.toDouble() - r2D) < 1e-7 && f.den <= 24L) f else null
                }
                val desc = r2Frac?.toString() ?: CasEngine.Fraction.formatDoubleNorm1(r2D)
                if (abs(r2D) > 1e-9) rootsT.add(RootT(r2D, r2Frac, desc))
                steps.add("Разложение на множители: t · (${CasEngine.Fraction.formatDoubleNorm1(a2D)}t + ${CasEngine.Fraction.formatDoubleNorm1(a1D)}) = 0  =>  t₁ = 0, t₂ = $desc")
            } else {
                val discD = a1D * a1D - 4.0 * a2D * a0D
                if (discD < -1e-9) {
                    steps.add("Дискриминант D < 0: действительных корней нет")
                    detailedSteps.add(
                        TrigStep(
                            section = "Пункт а",
                            title = "Дискриминант квадратного уравнения",
                            formula = "D < 0  ⇒  ∅",
                            explanation = "Дискриминант отрицателен, квадратное уравнение не имеет действительных корней."
                        )
                    )
                    return emptyList()
                }
                val sqrtD = sqrt(maxOf(0.0, discD))
                val twoA = 2.0 * a2D
                if (abs(discD) < 1e-9) {
                    val rD = -a1D / twoA
                    val rFrac = Fraction.fromDouble(rD).let { if (abs(it.toDouble() - rD) < 1e-7 && it.den <= 24L) it else null }
                    val desc = rFrac?.toString() ?: CasEngine.Fraction.formatDoubleNorm1(rD)
                    rootsT.add(RootT(rD, rFrac, desc))
                    steps.add("D = 0  =>  t = $desc")
                } else {
                    val r1D = (-a1D - sqrtD) / twoA
                    val r2D = (-a1D + sqrtD) / twoA
                    val r1Frac = Fraction.fromDouble(r1D).let { if (abs(it.toDouble() - r1D) < 1e-7 && it.den <= 24L) it else null }
                    val r2Frac = Fraction.fromDouble(r2D).let { if (abs(it.toDouble() - r2D) < 1e-7 && it.den <= 24L) it else null }
                    val desc1 = r1Frac?.toString() ?: CasEngine.Fraction.formatDoubleNorm1(r1D)
                    val desc2 = r2Frac?.toString() ?: CasEngine.Fraction.formatDoubleNorm1(r2D)
                    rootsT.add(RootT(r1D, r1Frac, desc1))
                    rootsT.add(RootT(r2D, r2Frac, desc2))
                    steps.add("D = ${CasEngine.Fraction.formatDoubleNorm1(discD)}  =>  t₁ = $desc1, t₂ = $desc2")
                }
            }
        }

        val allSeries = mutableListOf<TrigRootSeries>()
        for (rootT in rootsT) {
            val rD = rootT.d
            if (fn != "tan" && (rD < -1.000001 || rD > 1.000001)) {
                steps.add("Для t = ${rootT.desc} ($fn = ${rootT.desc}): корней нет, так как |t| > 1")
                detailedSteps.add(
                    TrigStep(
                        section = "Пункт а",
                        title = "Проверка ограничений для $fn(x)",
                        formula = "$fn(x) = ${rootT.desc}  ⇒  ∅",
                        explanation = "Уравнение $fn(x) = ${rootT.desc} не имеет действительных корней, так как -1 ≤ $fn(x) ≤ 1."
                    )
                )
                continue
            }
            steps.add("Решение простейшего уравнения $fn(...) = ${rootT.desc}:")
            val series = solveElementary(fn, rD, rootT.frac, arg, steps)
            for (s in series) {
                detailedSteps.add(
                    TrigStep(
                        section = "Пункт а",
                        title = "Решение простейшего уравнения $fn(x) = ${rootT.desc}",
                        formula = s.displayFormula,
                        explanation = "Решение простейшего тригонометрического уравнения $fn(x) = ${rootT.desc}."
                    )
                )
            }
            allSeries.addAll(series)
        }

        return deduplicateSeries(allSeries)
    }

    private fun solveElementary(
        fn: String,
        cD: Double,
        cFrac: Fraction?,
        arg: LinearArg,
        steps: MutableList<String>
    ): List<TrigRootSeries> {
        val series = when (fn) {
            "sin" -> solveSinElementary(cD)
            "cos" -> solveCosElementary(cD)
            "tan" -> solveTanElementary(cD)
            else -> emptyList()
        }

        return series.map { s ->
            transformSeriesArg(s, arg)
        }
    }

    private fun solveSinElementary(cD: Double): List<TrigRootSeries> {
        if (cD < -1.0 - 1e-12 || cD > 1.0 + 1e-12) return emptyList()
        val eps = 1e-5
        return when {
            abs(cD - 0.0) < eps -> {
                val s = SingleSeries(0.0, Math.PI, Fraction(0, 1), Fraction(1, 1))
                listOf(TrigRootSeries("x = πk, k ∈ ℤ", listOf(s)))
            }
            abs(cD - 1.0) < eps -> {
                val s = SingleSeries(Math.PI / 2.0, 2 * Math.PI, Fraction(1, 2), Fraction(2, 1))
                listOf(TrigRootSeries("x = π/2 + 2πk, k ∈ ℤ", listOf(s)))
            }
            abs(cD - (-1.0)) < eps -> {
                val s = SingleSeries(-Math.PI / 2.0, 2 * Math.PI, Fraction(-1, 2), Fraction(2, 1))
                listOf(TrigRootSeries("x = -π/2 + 2πk, k ∈ ℤ", listOf(s)))
            }
            abs(cD - 0.5) < eps -> {
                val s1 = SingleSeries(Math.PI / 6.0, 2 * Math.PI, Fraction(1, 6), Fraction(2, 1))
                val s2 = SingleSeries(5 * Math.PI / 6.0, 2 * Math.PI, Fraction(5, 6), Fraction(2, 1))
                listOf(TrigRootSeries("x = π/6 + 2πk, k ∈ ℤ;  x = 5π/6 + 2πk, k ∈ ℤ", listOf(s1, s2)))
            }
            abs(cD - (-0.5)) < eps -> {
                val s1 = SingleSeries(-Math.PI / 6.0, 2 * Math.PI, Fraction(-1, 6), Fraction(2, 1))
                val s2 = SingleSeries(-5 * Math.PI / 6.0, 2 * Math.PI, Fraction(-5, 6), Fraction(2, 1))
                listOf(TrigRootSeries("x = -π/6 + 2πk, k ∈ ℤ;  x = -5π/6 + 2πk, k ∈ ℤ", listOf(s1, s2)))
            }
            abs(cD - (sqrt(2.0) / 2.0)) < eps -> {
                val s1 = SingleSeries(Math.PI / 4.0, 2 * Math.PI, Fraction(1, 4), Fraction(2, 1))
                val s2 = SingleSeries(3 * Math.PI / 4.0, 2 * Math.PI, Fraction(3, 4), Fraction(2, 1))
                listOf(TrigRootSeries("x = π/4 + 2πk, k ∈ ℤ;  x = 3π/4 + 2πk, k ∈ ℤ", listOf(s1, s2)))
            }
            abs(cD - (-sqrt(2.0) / 2.0)) < eps -> {
                val s1 = SingleSeries(-Math.PI / 4.0, 2 * Math.PI, Fraction(-1, 4), Fraction(2, 1))
                val s2 = SingleSeries(-3 * Math.PI / 4.0, 2 * Math.PI, Fraction(-3, 4), Fraction(2, 1))
                listOf(TrigRootSeries("x = -π/4 + 2πk, k ∈ ℤ;  x = -3π/4 + 2πk, k ∈ ℤ", listOf(s1, s2)))
            }
            abs(cD - (sqrt(3.0) / 2.0)) < eps -> {
                val s1 = SingleSeries(Math.PI / 3.0, 2 * Math.PI, Fraction(1, 3), Fraction(2, 1))
                val s2 = SingleSeries(2 * Math.PI / 3.0, 2 * Math.PI, Fraction(2, 3), Fraction(2, 1))
                listOf(TrigRootSeries("x = π/3 + 2πk, k ∈ ℤ;  x = 2π/3 + 2πk, k ∈ ℤ", listOf(s1, s2)))
            }
            abs(cD - (-sqrt(3.0) / 2.0)) < eps -> {
                val s1 = SingleSeries(-Math.PI / 3.0, 2 * Math.PI, Fraction(-1, 3), Fraction(2, 1))
                val s2 = SingleSeries(-2 * Math.PI / 3.0, 2 * Math.PI, Fraction(-2, 3), Fraction(2, 1))
                listOf(TrigRootSeries("x = -π/3 + 2πk, k ∈ ℤ;  x = -2π/3 + 2πk, k ∈ ℤ", listOf(s1, s2)))
            }
            else -> {
                val a = asin(cD)
                val s1 = SingleSeries(a, 2 * Math.PI, null, Fraction(2, 1))
                val s2 = SingleSeries(Math.PI - a, 2 * Math.PI, null, Fraction(2, 1))
                listOf(TrigRootSeries("x = arcsin($cD) + 2πk, k ∈ ℤ;  x = π - arcsin($cD) + 2πk, k ∈ ℤ", listOf(s1, s2)))
            }
        }
    }

    private fun solveCosElementary(cD: Double): List<TrigRootSeries> {
        if (cD < -1.0 - 1e-12 || cD > 1.0 + 1e-12) return emptyList()
        val eps = 1e-5
        return when {
            abs(cD - 0.0) < eps -> {
                val s = SingleSeries(Math.PI / 2.0, Math.PI, Fraction(1, 2), Fraction(1, 1))
                listOf(TrigRootSeries("x = π/2 + πk, k ∈ ℤ", listOf(s)))
            }
            abs(cD - 1.0) < eps -> {
                val s = SingleSeries(0.0, 2 * Math.PI, Fraction(0, 1), Fraction(2, 1))
                listOf(TrigRootSeries("x = 2πk, k ∈ ℤ", listOf(s)))
            }
            abs(cD - (-1.0)) < eps -> {
                val s = SingleSeries(Math.PI, 2 * Math.PI, Fraction(1, 1), Fraction(2, 1))
                listOf(TrigRootSeries("x = π + 2πk, k ∈ ℤ", listOf(s)))
            }
            abs(cD - 0.5) < eps -> {
                val s1 = SingleSeries(Math.PI / 3.0, 2 * Math.PI, Fraction(1, 3), Fraction(2, 1))
                val s2 = SingleSeries(-Math.PI / 3.0, 2 * Math.PI, Fraction(-1, 3), Fraction(2, 1))
                listOf(TrigRootSeries("x = ±π/3 + 2πk, k ∈ ℤ", listOf(s1, s2)))
            }
            abs(cD - (-0.5)) < eps -> {
                val s1 = SingleSeries(2 * Math.PI / 3.0, 2 * Math.PI, Fraction(2, 3), Fraction(2, 1))
                val s2 = SingleSeries(-2 * Math.PI / 3.0, 2 * Math.PI, Fraction(-2, 3), Fraction(2, 1))
                listOf(TrigRootSeries("x = ±2π/3 + 2πk, k ∈ ℤ", listOf(s1, s2)))
            }
            abs(cD - (sqrt(2.0) / 2.0)) < eps -> {
                val s1 = SingleSeries(Math.PI / 4.0, 2 * Math.PI, Fraction(1, 4), Fraction(2, 1))
                val s2 = SingleSeries(-Math.PI / 4.0, 2 * Math.PI, Fraction(-1, 4), Fraction(2, 1))
                listOf(TrigRootSeries("x = ±π/4 + 2πk, k ∈ ℤ", listOf(s1, s2)))
            }
            abs(cD - (-sqrt(2.0) / 2.0)) < eps -> {
                val s1 = SingleSeries(3 * Math.PI / 4.0, 2 * Math.PI, Fraction(3, 4), Fraction(2, 1))
                val s2 = SingleSeries(-3 * Math.PI / 4.0, 2 * Math.PI, Fraction(-3, 4), Fraction(2, 1))
                listOf(TrigRootSeries("x = ±3π/4 + 2πk, k ∈ ℤ", listOf(s1, s2)))
            }
            abs(cD - (sqrt(3.0) / 2.0)) < eps -> {
                val s1 = SingleSeries(Math.PI / 6.0, 2 * Math.PI, Fraction(1, 6), Fraction(2, 1))
                val s2 = SingleSeries(-Math.PI / 6.0, 2 * Math.PI, Fraction(-1, 6), Fraction(2, 1))
                listOf(TrigRootSeries("x = ±π/6 + 2πk, k ∈ ℤ", listOf(s1, s2)))
            }
            abs(cD - (-sqrt(3.0) / 2.0)) < eps -> {
                val s1 = SingleSeries(5 * Math.PI / 6.0, 2 * Math.PI, Fraction(5, 6), Fraction(2, 1))
                val s2 = SingleSeries(-5 * Math.PI / 6.0, 2 * Math.PI, Fraction(-5, 6), Fraction(2, 1))
                listOf(TrigRootSeries("x = ±5π/6 + 2πk, k ∈ ℤ", listOf(s1, s2)))
            }
            else -> {
                val a = acos(cD)
                val s1 = SingleSeries(a, 2 * Math.PI, null, Fraction(2, 1))
                val s2 = SingleSeries(-a, 2 * Math.PI, null, Fraction(2, 1))
                listOf(TrigRootSeries("x = ±arccos($cD) + 2πk, k ∈ ℤ", listOf(s1, s2)))
            }
        }
    }

    private fun solveTanElementary(cD: Double): List<TrigRootSeries> {
        val eps = 1e-5
        return when {
            abs(cD - 0.0) < eps -> {
                val s = SingleSeries(0.0, Math.PI, Fraction(0, 1), Fraction(1, 1))
                listOf(TrigRootSeries("x = πk, k ∈ ℤ", listOf(s)))
            }
            abs(cD - 1.0) < eps -> {
                val s = SingleSeries(Math.PI / 4.0, Math.PI, Fraction(1, 4), Fraction(1, 1))
                listOf(TrigRootSeries("x = π/4 + πk, k ∈ ℤ", listOf(s)))
            }
            abs(cD - (-1.0)) < eps -> {
                val s = SingleSeries(-Math.PI / 4.0, Math.PI, Fraction(-1, 4), Fraction(1, 1))
                listOf(TrigRootSeries("x = -π/4 + πk, k ∈ ℤ", listOf(s)))
            }
            abs(cD - sqrt(3.0)) < eps -> {
                val s = SingleSeries(Math.PI / 3.0, Math.PI, Fraction(1, 3), Fraction(1, 1))
                listOf(TrigRootSeries("x = π/3 + πk, k ∈ ℤ", listOf(s)))
            }
            abs(cD - (-sqrt(3.0))) < eps -> {
                val s = SingleSeries(-Math.PI / 3.0, Math.PI, Fraction(-1, 3), Fraction(1, 1))
                listOf(TrigRootSeries("x = -π/3 + πk, k ∈ ℤ", listOf(s)))
            }
            abs(cD - (sqrt(3.0) / 3.0)) < eps || abs(cD - (1.0 / sqrt(3.0))) < eps -> {
                val s = SingleSeries(Math.PI / 6.0, Math.PI, Fraction(1, 6), Fraction(1, 1))
                listOf(TrigRootSeries("x = π/6 + πk, k ∈ ℤ", listOf(s)))
            }
            abs(cD - (-sqrt(3.0) / 3.0)) < eps || abs(cD - (-1.0 / sqrt(3.0))) < eps -> {
                val s = SingleSeries(-Math.PI / 6.0, Math.PI, Fraction(-1, 6), Fraction(1, 1))
                listOf(TrigRootSeries("x = -π/6 + πk, k ∈ ℤ", listOf(s)))
            }
            else -> {
                val a = atan(cD)
                val s = SingleSeries(a, Math.PI, null, Fraction(1, 1))
                listOf(TrigRootSeries("x = arctan($cD) + πk, k ∈ ℤ", listOf(s)))
            }
        }
    }

    private fun transformSeriesArg(series: TrigRootSeries, arg: LinearArg): TrigRootSeries {
        if (arg.k == Fraction(1, 1) && arg.b == Fraction(0, 1)) {
            return series
        }

        val kFrac = arg.k
        val bFrac = arg.b

        val newSeriesList = series.seriesList.map { s ->
            val newPeriodRad = s.periodRad / kFrac.toDouble()
            val newPeriodFracPi = s.periodFracPi?.let { (it / kFrac).simplify() }

            val newBaseRad = (s.baseAngleRad - bFrac.toDouble() * (if (arg.isPiMultipleB) Math.PI else 1.0)) / kFrac.toDouble()
            val newBaseFracPi = if (s.baseFracPi != null && arg.isPiMultipleB) {
                ((s.baseFracPi - bFrac) / kFrac).simplify()
            } else if (s.baseFracPi != null && bFrac == Fraction(0, 1)) {
                (s.baseFracPi / kFrac).simplify()
            } else null

            SingleSeries(newBaseRad, newPeriodRad, newBaseFracPi, newPeriodFracPi)
        }

        val formula = formatSeriesList(newSeriesList)
        return TrigRootSeries(formula, newSeriesList)
    }

    private fun formatSeriesList(list: List<SingleSeries>): String {
        return list.joinToString(";  ") { s ->
            val pStr = s.periodFracPi?.let {
                when {
                    it == Fraction(1, 1) -> "πk"
                    it == Fraction(2, 1) -> "2πk"
                    it.den == 1L -> "${it.num}πk"
                    it.num == 1L -> "πk/${it.den}"
                    else -> "${it.num}πk/${it.den}"
                }
            } ?: "${CasEngine.Fraction.formatDoubleNorm1(s.periodRad)}k"

            val bStr = s.baseFracPi?.let { formatFracPi(it) } ?: CasEngine.Fraction.formatDoubleNorm1(s.baseAngleRad)

            if (bStr == "0") "x = $pStr, k ∈ ℤ"
            else "x = $bStr + $pStr, k ∈ ℤ"
        }
    }

    private fun deduplicateSeries(list: List<TrigRootSeries>): List<TrigRootSeries> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<TrigRootSeries>()
        for (item in list) {
            if (seen.add(item.displayFormula)) {
                result.add(item)
            }
        }
        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AST Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun collectTrigTypes(node: Node): Set<String> {
        val types = mutableSetOf<String>()
        fun walk(n: Node) {
            when (n) {
                is Node.Func -> {
                    if (n.fn in listOf("sin", "cos", "tan")) types.add(n.fn)
                    walk(n.arg)
                }
                is Node.Add -> { walk(n.l); walk(n.r) }
                is Node.Sub -> { walk(n.l); walk(n.r) }
                is Node.Mul -> { walk(n.l); walk(n.r) }
                is Node.Div -> { walk(n.l); walk(n.r) }
                is Node.Pow -> { walk(n.base); walk(n.exp) }
                is Node.Sqrt -> walk(n.inner)
                else -> {}
            }
        }
        walk(node)
        return types
    }

    private fun collectTrigArgs(node: Node): List<Node> {
        val args = mutableListOf<Node>()
        fun walk(n: Node) {
            when (n) {
                is Node.Func -> {
                    if (n.fn in listOf("sin", "cos", "tan")) args.add(n.arg)
                    walk(n.arg)
                }
                is Node.Add -> { walk(n.l); walk(n.r) }
                is Node.Sub -> { walk(n.l); walk(n.r) }
                is Node.Mul -> { walk(n.l); walk(n.r) }
                is Node.Div -> { walk(n.l); walk(n.r) }
                is Node.Pow -> { walk(n.base); walk(n.exp) }
                is Node.Sqrt -> walk(n.inner)
                else -> {}
            }
        }
        walk(node)
        return args
    }

    private fun isSimpleX(n: Node): Boolean {
        return n is Node.Variable && n.name == "x"
    }

    private fun isMultipleOfArg(n: Node, factor: Int): Boolean {
        if (n is Node.Mul) {
            if (n.l is Node.Num && n.l.value.num == factor.toLong() && n.l.value.den == 1L && isSimpleX(n.r)) return true
            if (n.r is Node.Num && n.r.value.num == factor.toLong() && n.r.value.den == 1L && isSimpleX(n.l)) return true
        }
        return false
    }

    private fun detectPrimaryArg(node: Node): LinearArg? {
        val args = collectTrigArgs(node)
        val first = args.firstOrNull() ?: return null

        if (isSimpleX(first)) return LinearArg(Fraction(1, 1), Fraction(0, 1))

        if (first is Node.Mul) {
            if (first.l is Node.Num && isSimpleX(first.r)) return LinearArg(first.l.value, Fraction(0, 1))
            if (first.r is Node.Num && isSimpleX(first.l)) return LinearArg(first.r.value, Fraction(0, 1))
        }

        if (first is Node.Div && isSimpleX(first.l) && first.r is Node.Num) {
            return LinearArg(Fraction(1, 1) / first.r.value, Fraction(0, 1))
        }

        return LinearArg(Fraction(1, 1), Fraction(0, 1))
    }

    private fun expandDoubleAngles(node: Node, casEngine: CasEngine): Node {
        fun transform(n: Node): Node {
            return when (n) {
                is Node.Func -> {
                    if (n.fn == "sin" && isMultipleOfArg(n.arg, 2)) {
                        val x = Node.Variable("x")
                        Node.Mul(
                            Node.Num(Fraction(2, 1)),
                            Node.Mul(Node.Func("sin", x), Node.Func("cos", x))
                        )
                    } else if (n.fn == "cos" && isMultipleOfArg(n.arg, 2)) {
                        val x = Node.Variable("x")
                        Node.Sub(
                            Node.Mul(Node.Num(Fraction(2, 1)), Node.Pow(Node.Func("cos", x), Node.Num(Fraction(2, 1)))),
                            Node.Num(Fraction(1, 1))
                        )
                    } else {
                        Node.Func(n.fn, transform(n.arg))
                    }
                }
                is Node.Add -> Node.Add(transform(n.l), transform(n.r))
                is Node.Sub -> Node.Sub(transform(n.l), transform(n.r))
                is Node.Mul -> Node.Mul(transform(n.l), transform(n.r))
                is Node.Div -> Node.Div(transform(n.l), transform(n.r))
                is Node.Pow -> Node.Pow(transform(n.base), transform(n.exp))
                is Node.Sqrt -> Node.Sqrt(transform(n.inner))
                else -> n
            }
        }
        return casEngine.simplify(transform(node))
    }

    private fun splitFactors(node: Node): List<Node> {
        val list = mutableListOf<Node>()
        fun walk(n: Node) {
            if (n is Node.Mul) {
                walk(n.l)
                walk(n.r)
            } else {
                list.add(n)
            }
        }
        walk(node)
        return list
    }

    private fun substituteTrigWithT(node: Node, fn: String, casEngine: CasEngine): Node {
        fun walk(n: Node): Node {
            return when (n) {
                is Node.Func -> {
                    if (n.fn == fn) Node.Variable("t")
                    else n
                }
                is Node.Add -> Node.Add(walk(n.l), walk(n.r))
                is Node.Sub -> Node.Sub(walk(n.l), walk(n.r))
                is Node.Mul -> Node.Mul(walk(n.l), walk(n.r))
                is Node.Div -> Node.Div(walk(n.l), walk(n.r))
                is Node.Pow -> Node.Pow(walk(n.base), walk(n.exp))
                is Node.Sqrt -> Node.Sqrt(walk(n.inner))
                else -> n
            }
        }
        return casEngine.simplify(walk(node))
    }

    private fun isOnlyEvenPowerOfCos(node: Node): Boolean {
        var hasCos1 = false
        fun walk(n: Node, insidePow2: Boolean) {
            when (n) {
                is Node.Func -> {
                    if (n.fn == "cos" && !insidePow2) hasCos1 = true
                }
                is Node.Pow -> {
                    val isPow2 = n.exp is Node.Num && n.exp.value.num == 2L && n.exp.value.den == 1L
                    walk(n.base, isPow2)
                }
                is Node.Add -> { walk(n.l, false); walk(n.r, false) }
                is Node.Sub -> { walk(n.l, false); walk(n.r, false) }
                is Node.Mul -> { walk(n.l, false); walk(n.r, false) }
                is Node.Div -> { walk(n.l, false); walk(n.r, false) }
                else -> {}
            }
        }
        walk(node, false)
        return !hasCos1
    }

    private fun isOnlyEvenPowerOfSin(node: Node): Boolean {
        var hasSin1 = false
        fun walk(n: Node, insidePow2: Boolean) {
            when (n) {
                is Node.Func -> {
                    if (n.fn == "sin" && !insidePow2) hasSin1 = true
                }
                is Node.Pow -> {
                    val isPow2 = n.exp is Node.Num && n.exp.value.num == 2L && n.exp.value.den == 1L
                    walk(n.base, isPow2)
                }
                is Node.Add -> { walk(n.l, false); walk(n.r, false) }
                is Node.Sub -> { walk(n.l, false); walk(n.r, false) }
                is Node.Mul -> { walk(n.l, false); walk(n.r, false) }
                is Node.Div -> { walk(n.l, false); walk(n.r, false) }
                else -> {}
            }
        }
        walk(node, false)
        return !hasSin1
    }

    private fun replaceCos2WithSin2(node: Node, casEngine: CasEngine): Node {
        fun walk(n: Node): Node {
            return when {
                n is Node.Pow && n.base is Node.Func && (n.base as Node.Func).fn == "cos" &&
                        n.exp is Node.Num && n.exp.value.num == 2L && n.exp.value.den == 1L -> {
                    val arg = (n.base as Node.Func).arg
                    Node.Sub(Node.Num(Fraction(1, 1)), Node.Pow(Node.Func("sin", arg), Node.Num(Fraction(2, 1))))
                }
                n is Node.Add -> Node.Add(walk(n.l), walk(n.r))
                n is Node.Sub -> Node.Sub(walk(n.l), walk(n.r))
                n is Node.Mul -> Node.Mul(walk(n.l), walk(n.r))
                n is Node.Div -> Node.Div(walk(n.l), walk(n.r))
                n is Node.Pow -> Node.Pow(walk(n.base), walk(n.exp))
                n is Node.Sqrt -> Node.Sqrt(walk(n.inner))
                else -> n
            }
        }
        return casEngine.simplify(walk(node))
    }

    private fun replaceSin2WithCos2(node: Node, casEngine: CasEngine): Node {
        fun walk(n: Node): Node {
            return when {
                n is Node.Pow && n.base is Node.Func && (n.base as Node.Func).fn == "sin" &&
                        n.exp is Node.Num && n.exp.value.num == 2L && n.exp.value.den == 1L -> {
                    val arg = (n.base as Node.Func).arg
                    Node.Sub(Node.Num(Fraction(1, 1)), Node.Pow(Node.Func("cos", arg), Node.Num(Fraction(2, 1))))
                }
                n is Node.Add -> Node.Add(walk(n.l), walk(n.r))
                n is Node.Sub -> Node.Sub(walk(n.l), walk(n.r))
                n is Node.Mul -> Node.Mul(walk(n.l), walk(n.r))
                n is Node.Div -> Node.Div(walk(n.l), walk(n.r))
                n is Node.Pow -> Node.Pow(walk(n.base), walk(n.exp))
                n is Node.Sqrt -> Node.Sqrt(walk(n.inner))
                else -> n
            }
        }
        return casEngine.simplify(walk(node))
    }

    data class SignedTerm(val sign: Int, val node: Node)

    fun collectAdditiveTerms(node: Node, currentSign: Int = 1): List<SignedTerm> {
        val list = mutableListOf<SignedTerm>()
        fun walk(n: Node, s: Int) {
            when (n) {
                is Node.Add -> { walk(n.l, s); walk(n.r, s) }
                is Node.Sub -> { walk(n.l, s); walk(n.r, -s) }
                is Node.Num -> {
                    if (!isZero(n)) list.add(SignedTerm(s, n))
                }
                else -> list.add(SignedTerm(s, n))
            }
        }
        walk(node, currentSign)
        return list
    }

    private fun isZero(n: Node): Boolean = n is Node.Num && n.value.num == 0L

    private fun divideTermByFactor(node: Node, factor: Node.Func, casEngine: CasEngine): Node? {
        if (node is Node.Func && node.fn == factor.fn && node.arg.toExprString() == factor.arg.toExprString()) {
            return Node.Num(Fraction(1, 1))
        }
        if (node is Node.Pow && node.base is Node.Func) {
            val baseFunc = node.base as Node.Func
            if (baseFunc.fn == factor.fn && baseFunc.arg.toExprString() == factor.arg.toExprString()) {
                if (node.exp is Node.Num && node.exp.value.den == 1L) {
                    val exp = node.exp.value.num
                    return when {
                        exp == 1L -> Node.Num(Fraction(1, 1))
                        exp == 2L -> factor
                        exp > 2L -> Node.Pow(factor, Node.Num(Fraction(exp - 1, 1L)))
                        else -> null
                    }
                }
            }
        }
        if (node is Node.Mul) {
            val dl = divideTermByFactor(node.l, factor, casEngine)
            if (dl != null) return casEngine.simplify(Node.Mul(dl, node.r))
            val dr = divideTermByFactor(node.r, factor, casEngine)
            if (dr != null) return casEngine.simplify(Node.Mul(node.l, dr))
        }
        if (node is Node.Div) {
            val dl = divideTermByFactor(node.l, factor, casEngine)
            if (dl != null) return casEngine.simplify(Node.Div(dl, node.r))
        }
        return null
    }

    private fun tryFactorCommonTrig(node: Node, casEngine: CasEngine): Pair<Node, Node>? {
        val terms = collectAdditiveTerms(node)
        if (terms.size < 2) return null

        val candidates = mutableListOf<Node.Func>()
        fun findFuncs(n: Node) {
            when (n) {
                is Node.Func -> if (n.fn in listOf("sin", "cos", "tan")) {
                    if (candidates.none { it.fn == n.fn && it.arg.toExprString() == n.arg.toExprString() }) {
                        candidates.add(n)
                    }
                }
                is Node.Add -> { findFuncs(n.l); findFuncs(n.r) }
                is Node.Sub -> { findFuncs(n.l); findFuncs(n.r) }
                is Node.Mul -> { findFuncs(n.l); findFuncs(n.r) }
                is Node.Div -> { findFuncs(n.l); findFuncs(n.r) }
                is Node.Pow -> { findFuncs(n.base); findFuncs(n.exp) }
                is Node.Sqrt -> findFuncs(n.inner)
                else -> {}
            }
        }
        terms.forEach { findFuncs(it.node) }

        for (cand in candidates) {
            val quotients = terms.map { term ->
                divideTermByFactor(term.node, cand, casEngine)?.let { q ->
                    SignedTerm(term.sign, q)
                }
            }
            if (quotients.all { it != null }) {
                val nonNullQuotients = quotients.filterNotNull()
                var factor2: Node = if (nonNullQuotients[0].sign < 0) {
                    Node.Sub(Node.Num(Fraction(0, 1)), nonNullQuotients[0].node)
                } else {
                    nonNullQuotients[0].node
                }
                for (i in 1 until nonNullQuotients.size) {
                    val q = nonNullQuotients[i]
                    factor2 = if (q.sign < 0) {
                        Node.Sub(factor2, q.node)
                    } else {
                        Node.Add(factor2, q.node)
                    }
                }
                val simplifiedF2 = casEngine.simplify(factor2)
                if (!isZero(simplifiedF2)) {
                    return Pair(cand, simplifiedF2)
                }
            }
        }
        return null
    }

    data class ReductionData(val c: Double, val s: Int, val u: Node)

    private fun containsPi(node: Node): Boolean {
        var found = false
        fun walk(n: Node) {
            if (found) return
            when (n) {
                is Node.Variable -> if (n.name == "π" || n.name.equals("pi", ignoreCase = true)) found = true
                is Node.Add -> { walk(n.l); walk(n.r) }
                is Node.Sub -> { walk(n.l); walk(n.r) }
                is Node.Mul -> { walk(n.l); walk(n.r) }
                is Node.Div -> { walk(n.l); walk(n.r) }
                is Node.Pow -> { walk(n.base); walk(n.exp) }
                is Node.Sqrt -> walk(n.inner)
                is Node.Func -> walk(n.arg)
                else -> {}
            }
        }
        walk(node)
        return found
    }

    private fun extractReductionData(arg: Node, casEngine: CasEngine): ReductionData? {
        if (!arg.containsVar("x") || !containsPi(arg)) return null

        val terms = collectAdditiveTerms(arg)
        val piTerms = terms.filter { containsPi(it.node) && !it.node.containsVar("x") }
        val xTerms = terms.filter { it.node.containsVar("x") && !containsPi(it.node) }
        val otherTerms = terms.filter { !containsPi(it.node) && !it.node.containsVar("x") }

        if (piTerms.isEmpty() || xTerms.isEmpty() || otherTerms.isNotEmpty()) return null

        var cSum = 0.0
        for (pt in piTerms) {
            val d = casEngine.evalNodeAtX(pt.node, 0.0)
            if (d.isNaN() || d.isInfinite()) return null
            val v = d / Math.PI
            cSum += pt.sign * v
        }

        val mDouble = 2.0 * cSum
        val m = mDouble.roundToInt()
        if (abs(mDouble - m) > 1e-4) return null

        var s = xTerms[0].sign
        var uNode: Node = xTerms[0].node
        for (i in 1 until xTerms.size) {
            val xt = xTerms[i]
            uNode = if (xt.sign == s) {
                Node.Add(uNode, xt.node)
            } else {
                Node.Sub(uNode, xt.node)
            }
        }
        var simplifiedU = casEngine.simplify(uNode)
        val uValAt1 = casEngine.evalNodeAtX(simplifiedU, 1.0)
        val uValAt0 = casEngine.evalNodeAtX(simplifiedU, 0.0)
        if (uValAt1 - uValAt0 < -1e-9) {
            s = -s
            simplifiedU = casEngine.simplify(Node.Sub(Node.Num(Fraction(0, 1)), simplifiedU))
        }

        return ReductionData(cSum, s, simplifiedU)
    }

    private fun tryReduceTrig(fn: String, arg: Node, casEngine: CasEngine): Pair<Node, String>? {
        val data = extractReductionData(arg, casEngine) ?: return null
        val c = data.c
        val m = (2.0 * c).roundToInt()
        val s = data.s
        val u = data.u
        val m4 = ((m % 4) + 4) % 4

        val reduced: Node = when (fn) {
            "sin" -> when (m4) {
                0 -> if (s > 0) Node.Func("sin", u) else Node.Sub(Node.Num(Fraction(0, 1)), Node.Func("sin", u))
                1 -> Node.Func("cos", u)
                2 -> if (s > 0) Node.Sub(Node.Num(Fraction(0, 1)), Node.Func("sin", u)) else Node.Func("sin", u)
                3 -> Node.Sub(Node.Num(Fraction(0, 1)), Node.Func("cos", u))
                else -> return null
            }
            "cos" -> when (m4) {
                0 -> Node.Func("cos", u)
                1 -> if (s > 0) Node.Sub(Node.Num(Fraction(0, 1)), Node.Func("sin", u)) else Node.Func("sin", u)
                2 -> Node.Sub(Node.Num(Fraction(0, 1)), Node.Func("cos", u))
                3 -> if (s > 0) Node.Func("sin", u) else Node.Sub(Node.Num(Fraction(0, 1)), Node.Func("sin", u))
                else -> return null
            }
            "tan" -> if (m % 2 == 0) {
                if (s > 0) Node.Func("tan", u) else Node.Sub(Node.Num(Fraction(0, 1)), Node.Func("tan", u))
            } else {
                if (s > 0) Node.Div(Node.Num(Fraction(-1, 1)), Node.Func("tan", u))
                else Node.Div(Node.Num(Fraction(1, 1)), Node.Func("tan", u))
            }
            else -> return null
        }

        val simplified = casEngine.simplify(reduced)
        val origStr = "$fn(${arg.toExprString()})"
        val redStr = simplified.toExprString()
        val step = "Формула приведения: $origStr = $redStr"
        return Pair(simplified, step)
    }

    fun applyReductionFormulas(
        node: Node,
        casEngine: CasEngine,
        steps: MutableList<String>,
        detailedSteps: MutableList<TrigStep>? = null
    ): Node {
        fun transform(n: Node): Node {
            return when (n) {
                is Node.Func -> {
                    val transformedArg = transform(n.arg)
                    if (n.fn in listOf("sin", "cos", "tan")) {
                        val red = tryReduceTrig(n.fn, transformedArg, casEngine)
                        if (red != null) {
                            val (reducedNode, stepDesc) = red
                            steps.add(stepDesc)
                            detailedSteps?.add(
                                TrigStep(
                                    section = "Пункт а",
                                    title = "Формула приведения",
                                    formula = "${n.fn}(${formatFormulaUnicode(transformedArg.toExprString())}) = ${formatFormulaUnicode(reducedNode.toExprString())}",
                                    explanation = "Применяем формулу приведения для упрощения аргументов."
                                )
                            )
                            reducedNode
                        } else {
                            Node.Func(n.fn, transformedArg)
                        }
                    } else {
                        Node.Func(n.fn, transformedArg)
                    }
                }
                is Node.Add -> Node.Add(transform(n.l), transform(n.r))
                is Node.Sub -> Node.Sub(transform(n.l), transform(n.r))
                is Node.Mul -> Node.Mul(transform(n.l), transform(n.r))
                is Node.Div -> Node.Div(transform(n.l), transform(n.r))
                is Node.Pow -> Node.Pow(transform(n.base), transform(n.exp))
                is Node.Sqrt -> Node.Sqrt(transform(n.inner))
                else -> n
            }
        }
        return casEngine.simplify(transform(node))
    }

    private fun extractLinearCoeffs(arg: Node, casEngine: CasEngine): Pair<Fraction, Fraction>? {
        if (!arg.containsVar("x")) return null
        return try {
            val at0 = casEngine.evalNodeAtX(arg, 0.0)
            val at1 = casEngine.evalNodeAtX(arg, 1.0)
            val at2 = casEngine.evalNodeAtX(arg, 2.0)
            if (at0.isNaN() || at1.isNaN() || at2.isNaN()) return null

            val kD = at1 - at0
            if (abs(at2 - at0 - 2.0 * kD) > 1e-5) return null
            if (abs(kD) < 1e-9) return null

            val kFrac = Fraction.fromDouble(kD)
            if (abs(kFrac.toDouble() - kD) > 1e-6 || kFrac.den > 24L) return null

            val bFrac = if (abs(at0) < 1e-7) {
                Fraction(0, 1)
            } else {
                val bPi = at0 / Math.PI
                val f = Fraction.fromDouble(bPi)
                if (abs(f.toDouble() * Math.PI - at0) > 1e-6 || f.den > 24L) return null
                f
            }

            Pair(kFrac.simplify(), bFrac.simplify())
        } catch (_: Exception) {
            null
        }
    }

    private fun trySolveDirectTrigEquals(
        node: Node,
        casEngine: CasEngine,
        steps: MutableList<String>,
        detailedSteps: MutableList<TrigStep>? = null
    ): List<TrigRootSeries>? {
        if (node !is Node.Sub) return null

        fun unpackFunc(n: Node): Node.Func? {
            if (n is Node.Func && n.fn in listOf("sin", "cos")) return n
            if (n is Node.Mul && n.l is Node.Num && (n.l as Node.Num).value == Fraction(1, 1) && n.r is Node.Func) {
                val f = n.r as Node.Func
                if (f.fn in listOf("sin", "cos")) return f
            }
            if (n is Node.Mul && n.r is Node.Num && (n.r as Node.Num).value == Fraction(1, 1) && n.l is Node.Func) {
                val f = n.l as Node.Func
                if (f.fn in listOf("sin", "cos")) return f
            }
            return null
        }

        val fL = unpackFunc(node.l) ?: return null
        val fR = unpackFunc(node.r) ?: return null
        if (fL.fn != fR.fn) return null

        val fn = fL.fn
        val u = fL.arg
        val v = fR.arg

        val (k1, b1) = extractLinearCoeffs(u, casEngine) ?: return null
        val (k2, b2) = extractLinearCoeffs(v, casEngine) ?: return null

        if (k1 == k2) return null

        val seriesList = mutableListOf<TrigRootSeries>()

        when (fn) {
            "cos" -> {
                steps.add("Уравнение вида cos(u) = cos(v): u = v + 2πk или u = -v + 2πk")
                detailedSteps?.add(
                    TrigStep(
                        section = "Пункт а",
                        title = "Уравнение вида cos(u) = cos(v)",
                        formula = "cos(u) = cos(v)  ⇒  u = ±v + 2πk, k ∈ ℤ",
                        explanation = "Приравниваем аргументы косинусов с точностью до знака и периода 2πk."
                    )
                )
                val kDiff = k1 - k2
                val bDiff = b1 - b2
                val bFrac1 = (bDiff.negate() / kDiff).simplify()
                var pFrac1 = (Fraction(2, 1) / kDiff).simplify()
                if (pFrac1.toDouble() < 0) pFrac1 = pFrac1.negate()

                val s1 = SingleSeries(
                    baseAngleRad = bFrac1.toDouble() * Math.PI,
                    periodRad = pFrac1.toDouble() * Math.PI,
                    baseFracPi = bFrac1,
                    periodFracPi = pFrac1
                )
                seriesList.add(TrigRootSeries(formatSeriesList(listOf(s1)), listOf(s1)))

                val kSum = k1 + k2
                if (kSum != Fraction(0, 1)) {
                    val bSum = b1 + b2
                    val bFrac2 = (bSum.negate() / kSum).simplify()
                    var pFrac2 = (Fraction(2, 1) / kSum).simplify()
                    if (pFrac2.toDouble() < 0) pFrac2 = pFrac2.negate()

                    val s2 = SingleSeries(
                        baseAngleRad = bFrac2.toDouble() * Math.PI,
                        periodRad = pFrac2.toDouble() * Math.PI,
                        baseFracPi = bFrac2,
                        periodFracPi = pFrac2
                    )
                    seriesList.add(TrigRootSeries(formatSeriesList(listOf(s2)), listOf(s2)))
                }
            }
            "sin" -> {
                steps.add("Уравнение вида sin(u) = sin(v): u = v + 2πk или u = π - v + 2πk")
                detailedSteps?.add(
                    TrigStep(
                        section = "Пункт а",
                        title = "Уравнение вида sin(u) = sin(v)",
                        formula = "sin(u) = sin(v)  ⇒  u = v + 2πk  или  u = π - v + 2πk, k ∈ ℤ",
                        explanation = "Приравниваем аргументы синусов с учетом периода 2πk."
                    )
                )
                val kDiff = k1 - k2
                val bDiff = b1 - b2
                val bFrac1 = (bDiff.negate() / kDiff).simplify()
                var pFrac1 = (Fraction(2, 1) / kDiff).simplify()
                if (pFrac1.toDouble() < 0) pFrac1 = pFrac1.negate()

                val s1 = SingleSeries(
                    baseAngleRad = bFrac1.toDouble() * Math.PI,
                    periodRad = pFrac1.toDouble() * Math.PI,
                    baseFracPi = bFrac1,
                    periodFracPi = pFrac1
                )
                seriesList.add(TrigRootSeries(formatSeriesList(listOf(s1)), listOf(s1)))

                val kSum = k1 + k2
                if (kSum != Fraction(0, 1)) {
                    val bSum = b1 + b2
                    val bFrac2 = ((Fraction(1, 1) - bSum) / kSum).simplify()
                    var pFrac2 = (Fraction(2, 1) / kSum).simplify()
                    if (pFrac2.toDouble() < 0) pFrac2 = pFrac2.negate()

                    val s2 = SingleSeries(
                        baseAngleRad = bFrac2.toDouble() * Math.PI,
                        periodRad = pFrac2.toDouble() * Math.PI,
                        baseFracPi = bFrac2,
                        periodFracPi = pFrac2
                    )
                    seriesList.add(TrigRootSeries(formatSeriesList(listOf(s2)), listOf(s2)))
                }
            }
        }

        return deduplicateSeries(seriesList)
    }

    private fun trySolveHomogeneous1(
        node: Node,
        arg: LinearArg,
        casEngine: CasEngine,
        steps: MutableList<String>,
        detailedSteps: MutableList<TrigStep>? = null
    ): List<TrigRootSeries>? {
        if (node is Node.Sub || node is Node.Add) {
            val isSub = node is Node.Sub
            val l = if (isSub) (node as Node.Sub).l else (node as Node.Add).l
            val r = if (isSub) (node as Node.Sub).r else (node as Node.Add).r

            val sinPart = if (hasFunc(l, "sin")) l else if (hasFunc(r, "sin")) r else null
            val cosPart = if (hasFunc(l, "cos")) l else if (hasFunc(r, "cos")) r else null

            if (sinPart != null && cosPart != null) {
                val a = extractCoeff(sinPart, "sin")
                val b = extractCoeff(cosPart, "cos")
                val c = if (isSub) {
                    if (l === sinPart) (b / a).simplify()
                    else (b.negate() / a).simplify()
                } else {
                    (b.negate() / a).simplify()
                }

                steps.add("Однородное тригонометрическое уравнение: делим на cos(...)  =>  tan(...) = $c")
                detailedSteps?.add(
                    TrigStep(
                        section = "Пункт а",
                        title = "Однородное тригонометрическое уравнение",
                        formula = "${formatFormulaUnicode(node.toExprString())} = 0  ⇒  tg(x) = ${formatFormulaUnicode(c.toString())}",
                        explanation = "Делим обе части однородного уравнения на cos(x) ≠ 0 и переходим к тангенсу."
                    )
                )
                return solveElementary("tan", c.toDouble(), c, arg, steps)
            }
        }
        return null
    }

    private fun hasFunc(n: Node, fn: String): Boolean {
        if (n is Node.Func && n.fn == fn) return true
        if (n is Node.Mul) return hasFunc(n.l, fn) || hasFunc(n.r, fn)
        return false
    }

    private fun extractCoeff(n: Node, fn: String): Fraction {
        if (n is Node.Func && n.fn == fn) return Fraction(1, 1)
        if (n is Node.Mul) {
            if (n.l is Node.Num && hasFunc(n.r, fn)) return n.l.value
            if (n.r is Node.Num && hasFunc(n.l, fn)) return n.r.value
        }
        return Fraction(1, 1)
    }

    private fun toPolynomial(node: Node, target: String): Map<Int, Node>? {
        if (!node.containsVar(target)) return mapOf(0 to node)
        return when (node) {
            is Node.Variable -> if (node.name == target) mapOf(1 to Node.Num(Fraction(1, 1))) else mapOf(0 to node)
            is Node.Add -> {
                val pl = toPolynomial(node.l, target) ?: return null
                val pr = toPolynomial(node.r, target) ?: return null
                val res = pl.toMutableMap()
                for ((k, v) in pr) {
                    res[k] = addPolyNodes(res[k] ?: Node.Num(Fraction(0, 1)), v)
                }
                res
            }
            is Node.Sub -> {
                val pl = toPolynomial(node.l, target) ?: return null
                val pr = toPolynomial(node.r, target) ?: return null
                val res = pl.toMutableMap()
                for ((k, v) in pr) {
                    res[k] = subPolyNodes(res[k] ?: Node.Num(Fraction(0, 1)), v)
                }
                res
            }
            is Node.Mul -> {
                val pl = toPolynomial(node.l, target) ?: return null
                val pr = toPolynomial(node.r, target) ?: return null
                val res = mutableMapOf<Int, Node>()
                for ((k1, v1) in pl) {
                    for ((k2, v2) in pr) {
                        val deg = k1 + k2
                        val prod = mulPolyNodes(v1, v2)
                        res[deg] = addPolyNodes(res[deg] ?: Node.Num(Fraction(0, 1)), prod)
                    }
                }
                res
            }
            is Node.Pow -> {
                if (node.exp is Node.Num && node.exp.value.den == 1L) {
                    when (node.exp.value.num) {
                        0L -> mapOf(0 to Node.Num(Fraction(1, 1)))
                        1L -> toPolynomial(node.base, target)
                        2L -> {
                            val pb = toPolynomial(node.base, target) ?: return null
                            val res = mutableMapOf<Int, Node>()
                            for ((k1, v1) in pb) {
                                for ((k2, v2) in pb) {
                                    val deg = k1 + k2
                                    val prod = mulPolyNodes(v1, v2)
                                    res[deg] = addPolyNodes(res[deg] ?: Node.Num(Fraction(0, 1)), prod)
                                }
                            }
                            res
                        }
                        else -> null
                    }
                } else null
            }
            else -> null
        }
    }

    private fun addPolyNodes(a: Node, b: Node): Node {
        if (a is Node.Num && b is Node.Num) return Node.Num(a.value + b.value)
        return Node.Add(a, b)
    }

    private fun subPolyNodes(a: Node, b: Node): Node {
        if (a is Node.Num && b is Node.Num) return Node.Num(a.value - b.value)
        return Node.Sub(a, b)
    }

    private fun mulPolyNodes(a: Node, b: Node): Node {
        if (a is Node.Num && b is Node.Num) return Node.Num(a.value * b.value)
        return Node.Mul(a, b)
    }
}
