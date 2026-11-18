package com.example.calculator

import com.example.calculator.CasEngine.Fraction
import kotlin.math.*

/**
 * Структурированный шаг решения системы уравнений.
 */
data class SystemStep(
    val badge: String,
    val title: String,
    val formula: String,
    val explanation: String
)

/**
 * Решатель произвольных систем уравнений в естественной записи
 * (например: `x - y = 2` и `2y - x = 4`, или `y = 2x + 1`, `3x + 2y - z = 7`).
 *
 * Поддерживает:
 * - Точное аналитическое решение линейных систем от 2 до 5 переменных на рациональных дробях `Fraction` методом подстановки и исключения переменных.
 * - Численное решение нелинейных систем от 2 переменных методом Ньютона-Рафсона.
 */
class EquationSystemSolver(
    private val casEngine: CasEngine = CasEngine()
) {

    companion object {
        private val RESERVED_FUNCS = setOf(
            "sin", "cos", "tan", "asin", "acos", "atan",
            "log", "ln", "sqrt", "cbrt", "fact", "abs"
        )

        private val STANDARD_VAR_ORDER = listOf(
            "x", "y", "z", "w", "u", "v", "t", "a", "b", "c", "d"
        )
    }

    /**
     * Основной метод решения системы уравнений.
     */
    fun solve(equations: List<String>): LinearSystemResult {
        try {
            val validEqs = equations.map { it.trim() }.filter { it.isNotEmpty() }
            if (validEqs.isEmpty()) {
                return LinearSystemResult.Error("Введите уравнения")
            }

            // 1. Парсинг каждого уравнения в форму (LHS) - (RHS)
            val parsedNodes = mutableListOf<CasEngine.Node>()
            for (eq in validEqs) {
                if (!eq.contains('=')) {
                    return LinearSystemResult.Error("Уравнение должно содержать знак '=': \"$eq\"")
                }
                val parts = eq.split('=')
                if (parts.size != 2) {
                    return LinearSystemResult.Error("Уравнение должно содержать ровно один знак '=': \"$eq\"")
                }
                val lhs = parts[0].trim()
                val rhs = parts[1].trim()
                if (lhs.isEmpty() || rhs.isEmpty()) {
                    return LinearSystemResult.Error("Обе части уравнения должны быть заполнены: \"$eq\"")
                }

                val diffExpr = "($lhs) - ($rhs)"
                val node = try {
                    CasEngine.Parser(diffExpr).parse()
                } catch (e: Exception) {
                    return LinearSystemResult.Error("Ошибка в уравнении \"$eq\": ${e.message}")
                }
                parsedNodes.add(node)
            }

            // 2. Извлечение переменных
            val varSet = mutableSetOf<String>()
            for (node in parsedNodes) {
                collectVariables(node, varSet)
            }

            val sortedVars = varSet.sortedWith { v1, v2 ->
                val idx1 = STANDARD_VAR_ORDER.indexOf(v1.lowercase())
                val idx2 = STANDARD_VAR_ORDER.indexOf(v2.lowercase())
                if (idx1 != -1 && idx2 != -1) idx1.compareTo(idx2)
                else if (idx1 != -1) -1
                else if (idx2 != -1) 1
                else v1.compareTo(v2, ignoreCase = true)
            }

            val numEqs = validEqs.size
            val numVars = sortedVars.size

            if (numVars < 2) {
                return LinearSystemResult.Error("В системе должно быть не менее 2 переменных (найдено: $numVars)")
            }
            if (numVars > 5) {
                return LinearSystemResult.Error("Поддерживается до 5 переменных (найдено: $numVars)")
            }
            if (numEqs < numVars) {
                return LinearSystemResult.Error("Уравнений: $numEqs, переменных: $numVars — добавьте ещё уравнение")
            }
            if (numEqs > numVars) {
                return LinearSystemResult.Error("Уравнений: $numEqs, переменных: $numVars — удалите лишнее уравнение")
            }

            val n = numVars

            // 3. Проверка линейности и вычисление точных коэффициентов A[i][j] и b[i]
            var isLinearSystem = true
            val matrixA = Array(n) { Array(n) { Fraction(0L) } }
            val vectorB = Array(n) { Fraction(0L) }

            for (i in 0 until n) {
                val node = parsedNodes[i]

                // F(0, 0, ..., 0)
                val zeroMap = sortedVars.associateWith { Fraction(0L) }
                val cFrac = evalNodeFraction(node, zeroMap)
                if (cFrac == null) {
                    isLinearSystem = false
                    break
                }

                // b_i = -c_i (так как F = sum(a_ij * x_j) + c_i = 0 => sum(a_ij * x_j) = -c_i)
                vectorB[i] = cFrac.negate()

                // a_ij = F(e_j) - c_i
                for (j in 0 until n) {
                    val oneMap = sortedVars.mapIndexed { idx, v ->
                        v to (if (idx == j) Fraction(1L) else Fraction(0L))
                    }.toMap()
                    val f1 = evalNodeFraction(node, oneMap)
                    if (f1 == null) {
                        isLinearSystem = false
                        break
                    }
                    matrixA[i][j] = (f1 - cFrac).simplify()
                }
                if (!isLinearSystem) break

                // Проверка линейности в двух контрольных точках:
                val testMap1 = sortedVars.mapIndexed { idx, v ->
                    v to Fraction((2 + idx).toLong())
                }.toMap()
                var expected1: Fraction = cFrac
                for (j in 0 until n) {
                    expected1 = (expected1 + (matrixA[i][j] * testMap1.getValue(sortedVars[j]))).simplify()
                }
                val actual1 = evalNodeFraction(node, testMap1)
                if (actual1 == null || actual1.simplify() != expected1.simplify()) {
                    isLinearSystem = false
                    break
                }

                val testMap2 = sortedVars.mapIndexed { idx, v ->
                    v to Fraction((-1 - idx).toLong())
                }.toMap()
                var expected2: Fraction = cFrac
                for (j in 0 until n) {
                    expected2 = (expected2 + (matrixA[i][j] * testMap2.getValue(sortedVars[j]))).simplify()
                }
                val actual2 = evalNodeFraction(node, testMap2)
                if (actual2 == null || actual2.simplify() != expected2.simplify()) {
                    isLinearSystem = false
                    break
                }
            }

            // 4. Решение линейной системы методом подстановки / последовательного исключения
            if (isLinearSystem) {
                val standardLines = mutableListOf<String>()
                for (i in 0 until n) {
                    standardLines.add("${i + 1}) " + formatEquationStandard(matrixA[i], sortedVars, vectorB[i]))
                }
                val step1 = SystemStep(
                    badge = "Шаг 1",
                    title = "Запись системы в стандартном виде",
                    formula = standardLines.joinToString("\n"),
                    explanation = if (n == 2) "Переносим слагаемые с переменными в левую часть, а числа — в правую."
                    else "Переносим слагаемые с переменными в левую часть, а свободные числа — в правую часть каждого уравнения."
                )

                if (n == 2) {
                    return solveLinear2DSubstitution(matrixA, vectorB, sortedVars, step1)
                } else {
                    return solveLinearGauss(matrixA, vectorB, sortedVars, step1)
                }
            }

            // 5. Нелинейная система: численный поиск корней методом Ньютона-Рафсона (для 2 переменных)
            if (n == 2) {
                return solveNonlinear2D(parsedNodes[0], parsedNodes[1], sortedVars[0], sortedVars[1])
            } else {
                return LinearSystemResult.Error("Численное решение нелинейных систем поддерживается для 2 переменных")
            }

        } catch (e: Exception) {
            return LinearSystemResult.Error(e.message ?: "Ошибка решения системы")
        }
    }

    /**
     * Сбор всех переменных в выражении, исключая константы и математические функции.
     */
    private fun collectVariables(node: CasEngine.Node, set: MutableSet<String>) {
        when (node) {
            is CasEngine.Node.Variable -> {
                val name = node.name
                if (!CasEngine.isConstant(name) && !RESERVED_FUNCS.contains(name.lowercase())) {
                    set.add(name)
                }
            }
            is CasEngine.Node.Add -> {
                collectVariables(node.l, set)
                collectVariables(node.r, set)
            }
            is CasEngine.Node.Sub -> {
                collectVariables(node.l, set)
                collectVariables(node.r, set)
            }
            is CasEngine.Node.Mul -> {
                collectVariables(node.l, set)
                collectVariables(node.r, set)
            }
            is CasEngine.Node.Div -> {
                collectVariables(node.l, set)
                collectVariables(node.r, set)
            }
            is CasEngine.Node.Pow -> {
                collectVariables(node.base, set)
                collectVariables(node.exp, set)
            }
            is CasEngine.Node.Sqrt -> {
                collectVariables(node.inner, set)
            }
            is CasEngine.Node.Func -> {
                collectVariables(node.arg, set)
            }
            is CasEngine.Node.Num -> {}
        }
    }

    /**
     * Точное вычисление выражения в рациональных дробях Fraction.
     */
    private fun evalNodeFraction(node: CasEngine.Node, vars: Map<String, Fraction>): Fraction? {
        return when (node) {
            is CasEngine.Node.Num -> node.value
            is CasEngine.Node.Variable -> {
                val constVal = CasEngine.getConstantValue(node.name)
                if (constVal != null) {
                    Fraction.fromDouble(constVal)
                } else {
                    vars[node.name]
                }
            }
            is CasEngine.Node.Add -> {
                val l = evalNodeFraction(node.l, vars) ?: return null
                val r = evalNodeFraction(node.r, vars) ?: return null
                (l + r).simplify()
            }
            is CasEngine.Node.Sub -> {
                val l = evalNodeFraction(node.l, vars) ?: return null
                val r = evalNodeFraction(node.r, vars) ?: return null
                (l - r).simplify()
            }
            is CasEngine.Node.Mul -> {
                val l = evalNodeFraction(node.l, vars) ?: return null
                val r = evalNodeFraction(node.r, vars) ?: return null
                (l * r).simplify()
            }
            is CasEngine.Node.Div -> {
                val l = evalNodeFraction(node.l, vars) ?: return null
                val r = evalNodeFraction(node.r, vars) ?: return null
                if (r.num == 0L) return null
                (l / r).simplify()
            }
            is CasEngine.Node.Pow -> {
                val b = evalNodeFraction(node.base, vars) ?: return null
                val e = evalNodeFraction(node.exp, vars) ?: return null
                if (e.den == 1L) {
                    val exp = e.num
                    when {
                        exp == 0L -> Fraction(1L)
                        exp in 1..8 -> {
                            var res = Fraction(1L)
                            for (k in 0 until exp) res = res * b
                            res.simplify()
                        }
                        exp in -8..-1 && b.num != 0L -> {
                            var res = Fraction(1L)
                            for (k in 0 until -exp) res = res * b
                            (Fraction(1L) / res).simplify()
                        }
                        else -> null
                    }
                } else null
            }
            else -> null
        }
    }

    /**
     * Численное вычисление выражения в Double.
     */
    private fun evalNodeDouble(node: CasEngine.Node, vars: Map<String, Double>): Double {
        return when (node) {
            is CasEngine.Node.Num -> node.value.toDouble()
            is CasEngine.Node.Variable -> {
                val constVal = CasEngine.getConstantValue(node.name)
                if (constVal != null) constVal
                else vars[node.name] ?: Double.NaN
            }
            is CasEngine.Node.Add -> evalNodeDouble(node.l, vars) + evalNodeDouble(node.r, vars)
            is CasEngine.Node.Sub -> evalNodeDouble(node.l, vars) - evalNodeDouble(node.r, vars)
            is CasEngine.Node.Mul -> evalNodeDouble(node.l, vars) * evalNodeDouble(node.r, vars)
            is CasEngine.Node.Div -> {
                val r = evalNodeDouble(node.r, vars)
                if (r == 0.0 || r.isNaN()) Double.NaN
                else evalNodeDouble(node.l, vars) / r
            }
            is CasEngine.Node.Pow -> {
                val b = evalNodeDouble(node.base, vars)
                val e = evalNodeDouble(node.exp, vars)
                b.pow(e)
            }
            is CasEngine.Node.Sqrt -> {
                val v = evalNodeDouble(node.inner, vars)
                if (v < 0.0 || v.isNaN()) Double.NaN else sqrt(v)
            }
            is CasEngine.Node.Func -> {
                val a = evalNodeDouble(node.arg, vars)
                if (a.isNaN()) return Double.NaN
                when (node.fn.lowercase()) {
                    "sin" -> sin(if (casEngine.isDegreeMode) Math.toRadians(a) else a)
                    "cos" -> cos(if (casEngine.isDegreeMode) Math.toRadians(a) else a)
                    "tan" -> tan(if (casEngine.isDegreeMode) Math.toRadians(a) else a)
                    "asin" -> if (a in -1.0..1.0) { val r = asin(a); if (casEngine.isDegreeMode) Math.toDegrees(r) else r } else Double.NaN
                    "acos" -> if (a in -1.0..1.0) { val r = acos(a); if (casEngine.isDegreeMode) Math.toDegrees(r) else r } else Double.NaN
                    "atan" -> { val r = atan(a); if (casEngine.isDegreeMode) Math.toDegrees(r) else r }
                    "ln" -> if (a > 0) ln(a) else Double.NaN
                    "log" -> if (a > 0) log10(a) else Double.NaN
                    "cbrt" -> cbrt(a)
                    "abs" -> abs(a)
                    else -> Double.NaN
                }
            }
        }
    }

    private fun formatEquationStandard(rowA: Array<Fraction>, vars: List<String>, bVal: Fraction): String {
        val sb = StringBuilder()
        var termsAdded = 0
        for (j in vars.indices) {
            val a = rowA[j].simplify()
            if (a.num == 0L) continue
            val isFirst = termsAdded == 0
            val termStr = formatTermCoeff(a, vars[j], isFirst)
            sb.append(termStr)
            termsAdded++
        }
        if (termsAdded == 0) sb.append("0")
        sb.append(" = ").append(bVal.toFormattedString())
        return sb.toString()
    }

    private fun formatTermCoeff(a: Fraction, varName: String, isFirst: Boolean): String {
        val absA = Fraction(abs(a.num), a.den).simplify()
        val coeffStr = if (absA == Fraction(1L)) varName else "${absA.toFormattedString()}$varName"
        return if (isFirst) {
            if (a.num < 0L) "−$coeffStr" else coeffStr
        } else {
            if (a.num < 0L) " − $coeffStr" else " + $coeffStr"
        }
    }

    private fun Fraction.toFormattedString(): String {
        val s = this.simplify()
        return if (s.den == 1L) {
            if (s.num < 0L) "−${abs(s.num)}" else "${s.num}"
        } else {
            if (s.num < 0L) "−${abs(s.num)}/${s.den}" else "${s.num}/${s.den}"
        }
    }

    /**
     * Пошаговое решение систем 2x2 методом подстановки (школьный алгоритм).
     */
    private fun solveLinear2DSubstitution(
        A: Array<Array<Fraction>>,
        b: Array<Fraction>,
        varNames: List<String>,
        step1: SystemStep
    ): LinearSystemResult {
        val steps = mutableListOf<SystemStep>(step1)
        val var0 = varNames[0]
        val var1 = varNames[1]

        data class Candidate(val eqIdx: Int, val varIdx: Int, val coeff: Fraction)

        val candidates = listOf(
            Candidate(0, 0, A[0][0]),
            Candidate(0, 1, A[0][1]),
            Candidate(1, 0, A[1][0]),
            Candidate(1, 1, A[1][1])
        ).filter { it.coeff.num != 0L }

        if (candidates.isEmpty()) {
            val b0 = b[0].simplify()
            val b1 = b[1].simplify()
            if (b0.num != 0L || b1.num != 0L) {
                val nonZeroB = if (b0.num != 0L) b0 else b1
                steps.add(
                    SystemStep(
                        badge = "Анализ",
                        title = "Проверка решений",
                        formula = "0 = ${nonZeroB.toFormattedString()}  ⇒  Решений нет",
                        explanation = "После подстановки переменная сократилась и получилось неверное равенство. Система несовместна — решений нет."
                    )
                )
                return LinearSystemResult.NoSolution(steps)
            } else {
                steps.add(
                    SystemStep(
                        badge = "Анализ",
                        title = "Проверка решений",
                        formula = "0 = 0  ⇒  Бесконечно много решений",
                        explanation = "После подстановки получилось верное тождество 0 = 0. Уравнения выражают одну и ту же зависимость — система имеет бесконечно много решений."
                    )
                )
                return LinearSystemResult.InfiniteSolutions(steps)
            }
        }

        // Выбор наиболее удобного коэффициента (1 или -1 в приоритете, затем наименьший по модулю)
        val chosen = candidates.find { it.coeff == Fraction(1L) }
            ?: candidates.find { it.coeff == Fraction(-1L) }
            ?: candidates.minByOrNull { abs(it.coeff.toDouble()) }!!

        val eqIdx = chosen.eqIdx
        val varIdx = chosen.varIdx
        val otherEqIdx = 1 - eqIdx
        val otherVarIdx = 1 - varIdx

        val varExpName = varNames[varIdx]
        val varOtherName = varNames[otherVarIdx]

        val aExp = A[eqIdx][varIdx]
        val aOther = A[eqIdx][otherVarIdx]
        val bSel = b[eqIdx]

        // aExp * vExp + aOther * vOther = bSel  =>  vExp = (bSel/aExp) - (aOther/aExp) * vOther
        val cConst = (bSel / aExp).simplify()
        val cCoeff = ((aOther.negate()) / aExp).simplify()

        fun formatExprRhs(constVal: Fraction, coeffVal: Fraction, vName: String): String {
            val constStr = if (constVal.num != 0L) constVal.toFormattedString() else ""
            if (coeffVal.num == 0L) {
                return if (constStr.isEmpty()) "0" else constStr
            }
            val absCoeff = Fraction(abs(coeffVal.num), coeffVal.den).simplify()
            val coeffStr = if (absCoeff == Fraction(1L)) vName else "${absCoeff.toFormattedString()}$vName"

            return if (constStr.isEmpty()) {
                if (coeffVal.num < 0L) "−$coeffStr" else coeffStr
            } else {
                if (coeffVal.num < 0L) "$constStr − $coeffStr" else "$constStr + $coeffStr"
            }
        }

        val exprRhsStr = formatExprRhs(cConst, cCoeff, varOtherName)

        val step2Formula = "Из ${eqIdx + 1}-го уравнения выразим $varExpName:\n$varExpName = $exprRhsStr"
        val step2Explanation = "Выбираем уравнение с наиболее удобным коэффициентом и выражаем одну переменную через другую."

        steps.add(
            SystemStep(
                badge = "Шаг 2",
                title = "Выражаем переменную из уравнения",
                formula = step2Formula,
                explanation = step2Explanation
            )
        )

        // Шаг 3: Подставляем в другое уравнение
        val aSubExp = A[otherEqIdx][varIdx]
        val aSubOther = A[otherEqIdx][otherVarIdx]
        val bSub = b[otherEqIdx]

        val exprInParens = if (exprRhsStr.contains(" ") || exprRhsStr.startsWith("−")) "($exprRhsStr)" else exprRhsStr
        val subTerm1Str = when {
            aSubExp == Fraction(1L) -> exprInParens
            aSubExp == Fraction(-1L) -> "−$exprInParens"
            else -> "${aSubExp.toFormattedString()} · $exprInParens"
        }
        val otherTermStr = formatTermCoeff(aSubOther, varOtherName, isFirst = false)
        val line1 = "$subTerm1Str$otherTermStr = ${bSub.toFormattedString()}"

        val k1 = (aSubExp * cConst).simplify()
        val k2 = (aSubExp * cCoeff).simplify()

        val k1Str = if (k1.num != 0L) k1.toFormattedString() else ""
        val k2TermStr = formatTermCoeff(k2, varOtherName, isFirst = k1Str.isEmpty())
        val line2 = "$k1Str$k2TermStr$otherTermStr = ${bSub.toFormattedString()}"

        val aComb = (k2 + aSubOther).simplify()
        val bComb = (bSub - k1).simplify()

        if (aComb.num == 0L) {
            if (bComb.num != 0L) {
                val step3Formula = "0 = ${bComb.toFormattedString()}  ⇒  Решений нет"
                steps.add(
                    SystemStep(
                        badge = "Анализ",
                        title = "Проверка решений",
                        formula = step3Formula,
                        explanation = "После подстановки переменная сократилась и получилось неверное равенство. Система несовместна — решений нет."
                    )
                )
                return LinearSystemResult.NoSolution(steps)
            } else {
                val step3Formula = "0 = 0  ⇒  Бесконечно много решений"
                steps.add(
                    SystemStep(
                        badge = "Анализ",
                        title = "Проверка решений",
                        formula = step3Formula,
                        explanation = "После подстановки получилось верное тождество 0 = 0. Уравнения выражают одну и ту же зависимость — система имеет бесконечно много решений."
                    )
                )
                return LinearSystemResult.InfiniteSolutions(steps)
            }
        }

        val valOther = (bComb / aComb).simplify()

        val aCombAbs = Fraction(abs(aComb.num), aComb.den).simplify()
        val aCombTerm = if (aComb.num < 0L) "−${if (aCombAbs == Fraction(1L)) varOtherName else "${aCombAbs.toFormattedString()}$varOtherName"}"
                        else if (aCombAbs == Fraction(1L)) varOtherName else "${aCombAbs.toFormattedString()}$varOtherName"

        val line3 = "$aCombTerm = ${bComb.toFormattedString()}  ⇒  $varOtherName = ${valOther.toFormattedString()}"

        val step3Formula = "$line1\n$line2\n$line3"
        val step3Explanation = "Подставляем полученное выражение в ${otherEqIdx + 1}-е уравнение, раскрываем скобки, приводим подобные слагаемые и находим значение $varOtherName."

        steps.add(
            SystemStep(
                badge = "Шаг 3",
                title = "Подставляем в другое уравнение",
                formula = step3Formula,
                explanation = step3Explanation
            )
        )

        // Шаг 4: Находим вторую переменную
        val valExp = (cConst + (cCoeff * valOther)).simplify()

        val valOtherInParens = if (valOther.num < 0L) "(${valOther.toFormattedString()})" else valOther.toFormattedString()
        val step4SubstStr = when {
            cCoeff == Fraction(0L) -> cConst.toFormattedString()
            cConst == Fraction(0L) -> {
                if (cCoeff == Fraction(1L)) valOtherInParens
                else if (cCoeff == Fraction(-1L)) "−$valOtherInParens"
                else "${cCoeff.toFormattedString()} · $valOtherInParens"
            }
            else -> {
                val cConstStr = cConst.toFormattedString()
                if (cCoeff.num > 0L) {
                    val coeffStr = if (cCoeff == Fraction(1L)) "" else "${cCoeff.toFormattedString()} · "
                    "$cConstStr + $coeffStr$valOtherInParens"
                } else {
                    val absC = cCoeff.negate()
                    val coeffStr = if (absC == Fraction(1L)) "" else "${absC.toFormattedString()} · "
                    "$cConstStr − $coeffStr$valOtherInParens"
                }
            }
        }

        val step4Formula = "$varExpName = $step4SubstStr = ${valExp.toFormattedString()}"
        val step4Explanation = "Подставляем найденное значение $varOtherName = ${valOther.toFormattedString()} в выражение для $varExpName из шага 2."

        steps.add(
            SystemStep(
                badge = "Шаг 4",
                title = "Находим вторую переменную",
                formula = step4Formula,
                explanation = step4Explanation
            )
        )

        // Шаг 5: Ответ
        val val0 = if (varIdx == 0) valExp else valOther
        val val1 = if (varIdx == 1) valExp else valOther

        val lineVar0 = if (val0.den == 1L) "$var0 = ${val0.num}" else "$var0 = ${val0.toFormattedString()}  (≈ ${Fraction.formatDoubleNorm1(val0.toDouble())})"
        val lineVar1 = if (val1.den == 1L) "$var1 = ${val1.num}" else "$var1 = ${val1.toFormattedString()}  (≈ ${Fraction.formatDoubleNorm1(val1.toDouble())})"

        steps.add(
            SystemStep(
                badge = "Ответ",
                title = "Решение системы",
                formula = "$lineVar0\n$lineVar1",
                explanation = "Записываем найденную пару значений, обращающую оба уравнения в верные равенства."
            )
        )

        return LinearSystemResult.UniqueSolution(listOf(val0, val1), listOf(lineVar0, lineVar1), steps)
    }

    /**
     * Пошаговое решение систем N >= 3 методом последовательного исключения переменных (школьный метод Гаусса без матричной терминологии).
     */
    private fun solveLinearGauss(
        A: Array<Array<Fraction>>,
        b: Array<Fraction>,
        varNames: List<String>,
        step1: SystemStep
    ): LinearSystemResult {
        val steps = mutableListOf<SystemStep>(step1)
        val n = varNames.size

        val m = Array(n) { i ->
            Array(n + 1) { j ->
                if (j < n) A[i][j] else b[i]
            }
        }

        var leadRow = 0
        for (col in 0 until n) {
            var pivotRow = leadRow
            var maxVal = abs(m[leadRow][col].toDouble())
            for (r in leadRow + 1 until n) {
                val curVal = abs(m[r][col].toDouble())
                if (curVal > maxVal) {
                    maxVal = curVal
                    pivotRow = r
                }
            }

            if (m[pivotRow][col].num == 0L) {
                continue
            }

            if (pivotRow != leadRow) {
                val temp = m[leadRow]
                m[leadRow] = m[pivotRow]
                m[pivotRow] = temp
            }

            val pivot = m[leadRow][col]
            for (j in col until n + 1) {
                m[leadRow][j] = (m[leadRow][j] / pivot).simplify()
            }

            for (r in 0 until n) {
                if (r != leadRow) {
                    val factor = m[r][col]
                    if (factor.num != 0L) {
                        for (j in col until n + 1) {
                            m[r][j] = (m[r][j] - (factor * m[leadRow][j])).simplify()
                        }
                    }
                }
            }

            leadRow++
        }

        val elimLines = mutableListOf<String>()
        for (i in 0 until n) {
            val rowA = Array(n) { col -> m[i][col] }
            val bVal = m[i][n]
            elimLines.add("${i + 1}) " + formatEquationStandard(rowA, varNames, bVal))
        }

        steps.add(
            SystemStep(
                badge = "Шаг 2",
                title = "Последовательное исключение переменных",
                formula = elimLines.joinToString("\n"),
                explanation = "Используем метод последовательного исключения переменных (пошаговое вычитание уравнений) для приведения системы к треугольному виду."
            )
        )

        // Проверка несовместности: строка 0 = C (C != 0)
        for (i in 0 until n) {
            val allCoeffsZero = (0 until n).all { j -> m[i][j].num == 0L }
            if (allCoeffsZero && m[i][n].num != 0L) {
                steps.add(
                    SystemStep(
                        badge = "Анализ",
                        title = "Проверка решений",
                        formula = "0 = ${m[i][n].toFormattedString()}  ⇒  Решений нет",
                        explanation = "В процессе исключения переменных получено противоречивое уравнение 0 = c (где c ≠ 0). Система несовместна — решений нет."
                    )
                )
                return LinearSystemResult.NoSolution(steps)
            }
        }

        // Проверка бесконечного числа решений
        if (leadRow < n || (0 until n).any { i -> (0..n).all { j -> m[i][j].num == 0L } }) {
            steps.add(
                SystemStep(
                    badge = "Анализ",
                    title = "Проверка решений",
                    formula = "0 = 0  ⇒  Бесконечно много решений",
                    explanation = "В процессе исключения переменных получено тождество 0 = 0. Число независимых уравнений меньше числа переменных — система имеет бесконечно много решений."
                )
            )
            return LinearSystemResult.InfiniteSolutions(steps)
        }

        // Единственное решение N >= 3
        val values = mutableListOf<Fraction>()
        val formattedLines = mutableListOf<String>()
        val backSubLines = mutableListOf<String>()

        for (i in 0 until n) {
            val frac = m[i][n].simplify()
            values.add(frac)
            val vName = varNames[i]
            val line = if (frac.den == 1L) {
                "$vName = ${frac.num}"
            } else {
                "$vName = ${frac.toFormattedString()}  (≈ ${Fraction.formatDoubleNorm1(frac.toDouble())})"
            }
            formattedLines.add(line)
            backSubLines.add("$vName = ${frac.toFormattedString()}")
        }

        steps.add(
            SystemStep(
                badge = "Шаг 3",
                title = "Обратная подстановка снизу вверх",
                formula = backSubLines.joinToString("\n"),
                explanation = "Последовательно находим значения всех переменных, начиная с последнего уравнения и двигаясь вверх."
            )
        )

        steps.add(
            SystemStep(
                badge = "Ответ",
                title = "Решение системы",
                formula = formattedLines.joinToString("\n"),
                explanation = "Записываем точные значения всех переменных системы."
            )
        )

        return LinearSystemResult.UniqueSolution(values, formattedLines, steps)
    }

    /**
     * 2D Численный метод решения нелинейных систем 2x2 в школьной формулировке.
     */
    private fun solveNonlinear2D(
        fNode: CasEngine.Node,
        gNode: CasEngine.Node,
        varX: String,
        varY: String
    ): LinearSystemResult {
        val steps = mutableListOf<SystemStep>()

        steps.add(
            SystemStep(
                badge = "Шаг 1",
                title = "Анализ нелинейной системы",
                formula = "1-е уравнение: f($varX, $varY) = 0\n2-е уравнение: g($varX, $varY) = 0",
                explanation = "Система содержит нелинейные выражения. Для поиска всех точек пересечения графиков уравнений применяется численный алгоритм решения."
            )
        )

        steps.add(
            SystemStep(
                badge = "Шаг 2",
                title = "Поиск точек пересечения графиков",
                formula = "Поиск точек пересечения графиков уравнений f($varX, $varY) = 0 и g($varX, $varY) = 0",
                explanation = "Выполняется сканирование области возможных значений и итеративное уточнение координат точек пересечения с точностью до 10⁻⁶."
            )
        )

        fun f(x: Double, y: Double): Double = evalNodeDouble(fNode, mapOf(varX to x, varY to y))
        fun g(x: Double, y: Double): Double = evalNodeDouble(gNode, mapOf(varX to x, varY to y))

        data class Point(val x: Double, val y: Double)

        val foundRoots = mutableListOf<Point>()

        val grid = listOf(
            -10.0, -5.0, -3.0, -2.0, -1.0, -0.5, 0.0, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0
        )

        val h = 1e-6

        for (x0 in grid) {
            for (y0 in grid) {
                var curX = x0
                var curY = y0
                var converged = false

                for (iter in 0 until 60) {
                    val fVal = f(curX, curY)
                    val gVal = g(curX, curY)
                    if (fVal.isNaN() || gVal.isNaN() || fVal.isInfinite() || gVal.isInfinite()) break

                    if (max(abs(fVal), abs(gVal)) < 1e-10) {
                        converged = true
                        break
                    }

                    // Численные производные
                    val dfDx = (f(curX + h, curY) - f(curX - h, curY)) / (2.0 * h)
                    val dfDy = (f(curX, curY + h) - f(curX, curY - h)) / (2.0 * h)
                    val dgDx = (g(curX + h, curY) - g(curX - h, curY)) / (2.0 * h)
                    val dgDy = (g(curX, curY + h) - g(curX, curY - h)) / (2.0 * h)

                    val detJ = dfDx * dgDy - dfDy * dgDx
                    if (abs(detJ) < 1e-13) break

                    val deltaX = -(dgDy * fVal - dfDy * gVal) / detJ
                    val deltaY = -(-dgDx * fVal + dfDx * gVal) / detJ

                    if (deltaX.isNaN() || deltaY.isNaN() || deltaX.isInfinite() || deltaY.isInfinite()) break

                    curX += deltaX
                    curY += deltaY

                    if (max(abs(deltaX), abs(deltaY)) < 1e-10) {
                        val finalF = f(curX, curY)
                        val finalG = g(curX, curY)
                        if (!finalF.isNaN() && !finalG.isNaN() && max(abs(finalF), abs(finalG)) < 1e-6) {
                            converged = true
                        }
                        break
                    }
                }

                if (converged) {
                    val fCheck = f(curX, curY)
                    val gCheck = g(curX, curY)
                    if (!fCheck.isNaN() && !gCheck.isNaN() && max(abs(fCheck), abs(gCheck)) < 1e-5) {
                        var rx = curX
                        var ry = curY
                        if (abs(round(rx) - rx) < 1e-8) rx = round(rx)
                        if (abs(round(ry) - ry) < 1e-8) ry = round(ry)

                        val isDuplicate = foundRoots.any { existing ->
                            hypot(existing.x - rx, existing.y - ry) < 1e-3
                        }
                        if (!isDuplicate) {
                            foundRoots.add(Point(rx, ry))
                        }
                    }
                }
            }
        }

        if (foundRoots.isEmpty()) {
            steps.add(
                SystemStep(
                    badge = "Анализ",
                    title = "Результат поиска",
                    formula = "Действительных точек пересечения не найдено  ⇒  Решений нет",
                    explanation = "Графики уравнений не имеют общих действительных точек пересечения."
                )
            )
            return LinearSystemResult.NoSolution(steps)
        }

        foundRoots.sortWith(compareBy({ it.x }, { it.y }))

        fun formatRootVal(v: Double): String {
            return if (abs(round(v) - v) < 1e-8) {
                round(v).toLong().toString()
            } else {
                Fraction.formatDoubleNorm1(v)
            }
        }

        val formattedLines = mutableListOf<String>()
        if (foundRoots.size == 1) {
            val r = foundRoots[0]
            formattedLines.add("$varX = ${formatRootVal(r.x)}")
            formattedLines.add("$varY = ${formatRootVal(r.y)}")
        } else {
            for ((idx, r) in foundRoots.withIndex()) {
                formattedLines.add("Решение ${idx + 1}: $varX = ${formatRootVal(r.x)}, $varY = ${formatRootVal(r.y)}")
            }
        }

        steps.add(
            SystemStep(
                badge = "Ответ",
                title = "Найденные решения",
                formula = formattedLines.joinToString("\n"),
                explanation = "Все найденные действительные точки пересечения графиков уравнений системы."
            )
        )

        return LinearSystemResult.UniqueSolution(emptyList(), formattedLines, steps)
    }
}
