package com.example.calculator

import kotlin.math.*

sealed class CompiledPlot {
    data class ExplicitY(val fn: (Double) -> Double) : CompiledPlot()
    data class ExplicitX(val fn: (Double) -> Double) : CompiledPlot()
    data class ImplicitXY(val fxy: (Double, Double) -> Double) : CompiledPlot()
    data class InequalityXY(val fxy: (Double, Double) -> Double, val op: String) : CompiledPlot()
}

object DesmosPlotCompiler {

    private val KNOWN_FUNCS = setOf(
        "sin", "cos", "tan", "asin", "acos", "atan",
        "log", "ln", "sqrt", "cbrt", "abs", "fact"
    )

    /**
     * Извлечение всех свободных параметров из формулы (все буквы кроме x, y, функций и констант).
     */
    fun extractParameters(rawExpr: String): Set<String> {
        val trimmed = rawExpr.trim()
        if (trimmed.isEmpty()) return emptySet()
        val clean = normalizeExpr(trimmed)
        if (clean.isEmpty()) return emptySet()

        val result = mutableSetOf<String>()
        val parts = clean.split(Regex("""<=|>=|<|>|=""")).map { it.trim() }.filter { it.isNotEmpty() }
        for (part in parts) {
            try {
                val ast = CasEngine.Parser(part).parse()
                result.addAll(findParameters(ast))
            } catch (_: Exception) {}
        }
        return result
    }

    /**
     * Поиск свободных параметров в узлах дерева AST.
     */
    fun findParameters(ast: CasEngine.Node): Set<String> {
        val result = mutableSetOf<String>()
        fun traverse(node: CasEngine.Node) {
            when (node) {
                is CasEngine.Node.Num, is CasEngine.Node.Approx -> {}
                is CasEngine.Node.Variable -> {
                    val name = node.name
                    val lower = name.lowercase()
                    if (lower != "x" && lower != "y" && !CasEngine.isConstant(name) && lower !in KNOWN_FUNCS) {
                        result.add(name)
                    }
                }
                is CasEngine.Node.Add -> { traverse(node.l); traverse(node.r) }
                is CasEngine.Node.Sub -> { traverse(node.l); traverse(node.r) }
                is CasEngine.Node.Mul -> { traverse(node.l); traverse(node.r) }
                is CasEngine.Node.Div -> { traverse(node.l); traverse(node.r) }
                is CasEngine.Node.Pow -> { traverse(node.base); traverse(node.exp) }
                is CasEngine.Node.Sqrt -> traverse(node.inner)
                is CasEngine.Node.Func -> traverse(node.arg)
                is CasEngine.Node.Comb -> { traverse(node.n); traverse(node.k) }
            }
        }
        traverse(ast)
        return result
    }

    /**
     * Компиляция строки математического выражения в оптимизированный функциональный объект.
     */
    fun compile(rawExpr: String, params: Map<String, Double> = emptyMap()): CompiledPlot? {
        val trimmed = rawExpr.trim()
        if (trimmed.isEmpty()) return null

        val clean = normalizeExpr(trimmed)
        if (clean.isEmpty()) return null

        return try {
            // 1. Неравенства: <=, >=, <, >
            val ineqOps = listOf("<=", ">=", "<", ">")
            var matchedOp: String? = null
            for (op in ineqOps) {
                if (clean.contains(op)) {
                    matchedOp = op
                    break
                }
            }

            if (matchedOp != null) {
                val idx = clean.indexOf(matchedOp)
                val lhsStr = clean.substring(0, idx).trim()
                val rhsStr = clean.substring(idx + matchedOp.length).trim()
                if (lhsStr.isNotEmpty() && rhsStr.isNotEmpty()) {
                    val lhsAst = CasEngine.Parser(lhsStr).parse()
                    val rhsAst = CasEngine.Parser(rhsStr).parse()
                    return CompiledPlot.InequalityXY({ x, y ->
                        evalNode(lhsAst, x, y, params) - evalNode(rhsAst, x, y, params)
                    }, matchedOp)
                }
            }

            // 2. Уравнения с знаком '='
            if (clean.contains("=")) {
                val parts = clean.split("=")
                if (parts.size != 2) return null
                val lhsStr = parts[0].trim()
                val rhsStr = parts[1].trim()
                if (lhsStr.isEmpty() || rhsStr.isEmpty()) return null

                val isLhsY = lhsStr.equals("y", ignoreCase = true) ||
                        lhsStr.equals("f(x)", ignoreCase = true) ||
                        lhsStr.equals("y(x)", ignoreCase = true)
                val isRhsY = rhsStr.equals("y", ignoreCase = true) ||
                        rhsStr.equals("f(x)", ignoreCase = true) ||
                        rhsStr.equals("y(x)", ignoreCase = true)

                if (isLhsY) {
                    val rhsAst = CasEngine.Parser(rhsStr).parse()
                    if (!rhsAst.containsVar("y")) {
                        return CompiledPlot.ExplicitY { x -> evalNode(rhsAst, x, 0.0, params) }
                    }
                } else if (isRhsY) {
                    val lhsAst = CasEngine.Parser(lhsStr).parse()
                    if (!lhsAst.containsVar("y")) {
                        return CompiledPlot.ExplicitY { x -> evalNode(lhsAst, x, 0.0, params) }
                    }
                }

                val isLhsX = lhsStr.equals("x", ignoreCase = true) ||
                        lhsStr.equals("g(y)", ignoreCase = true) ||
                        lhsStr.equals("x(y)", ignoreCase = true)
                val isRhsX = rhsStr.equals("x", ignoreCase = true) ||
                        rhsStr.equals("g(y)", ignoreCase = true) ||
                        rhsStr.equals("x(y)", ignoreCase = true)

                if (isLhsX) {
                    val rhsAst = CasEngine.Parser(rhsStr).parse()
                    if (!rhsAst.containsVar("x")) {
                        return CompiledPlot.ExplicitX { y -> evalNode(rhsAst, 0.0, y, params) }
                    }
                } else if (isRhsX) {
                    val lhsAst = CasEngine.Parser(lhsStr).parse()
                    if (!lhsAst.containsVar("x")) {
                        return CompiledPlot.ExplicitX { y -> evalNode(lhsAst, 0.0, y, params) }
                    }
                }

                val lhsAst = CasEngine.Parser(lhsStr).parse()
                val rhsAst = CasEngine.Parser(rhsStr).parse()
                return CompiledPlot.ImplicitXY { x, y ->
                    evalNode(lhsAst, x, y, params) - evalNode(rhsAst, x, y, params)
                }
            }

            // 3. Выражения без знака '=' (по умолчанию y = f(x))
            val ast = CasEngine.Parser(clean).parse()
            if (!ast.containsVar("y")) {
                CompiledPlot.ExplicitY { x -> evalNode(ast, x, 0.0, params) }
            } else if (!ast.containsVar("x")) {
                CompiledPlot.ExplicitY { x -> evalNode(ast, x, 0.0, params) }
            } else {
                CompiledPlot.ImplicitXY { x, y -> evalNode(ast, x, y, params) }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Оценка дерева выражений при заданных x, y и значениях параметров.
     */
    fun evalNode(
        node: CasEngine.Node,
        xVal: Double,
        yVal: Double,
        params: Map<String, Double> = emptyMap()
    ): Double {
        return when (node) {
            is CasEngine.Node.Num -> node.value.toDouble()
            is CasEngine.Node.Approx -> node.value
            is CasEngine.Node.Variable -> {
                val constVal = CasEngine.getConstantValue(node.name)
                if (constVal != null) {
                    constVal
                } else {
                    val lower = node.name.lowercase()
                    when (lower) {
                        "x" -> xVal
                        "y" -> yVal
                        else -> params[node.name] ?: params[lower] ?: 1.0
                    }
                }
            }
            is CasEngine.Node.Add -> evalNode(node.l, xVal, yVal, params) + evalNode(node.r, xVal, yVal, params)
            is CasEngine.Node.Sub -> evalNode(node.l, xVal, yVal, params) - evalNode(node.r, xVal, yVal, params)
            is CasEngine.Node.Mul -> evalNode(node.l, xVal, yVal, params) * evalNode(node.r, xVal, yVal, params)
            is CasEngine.Node.Div -> {
                val r = evalNode(node.r, xVal, yVal, params)
                if (r == 0.0 || r.isNaN()) Double.NaN
                else evalNode(node.l, xVal, yVal, params) / r
            }
            is CasEngine.Node.Pow -> {
                val b = evalNode(node.base, xVal, yVal, params)
                val e = evalNode(node.exp, xVal, yVal, params)
                if (b < 0.0 && e != floor(e)) Double.NaN else b.pow(e)
            }
            is CasEngine.Node.Sqrt -> {
                val v = evalNode(node.inner, xVal, yVal, params)
                if (v < 0.0 || v.isNaN()) Double.NaN else sqrt(v)
            }
            is CasEngine.Node.Func -> {
                val a = evalNode(node.arg, xVal, yVal, params)
                if (a.isNaN()) return Double.NaN
                when (node.fn.lowercase()) {
                    "sin" -> sin(a)
                    "cos" -> cos(a)
                    "tan" -> {
                        val c = cos(a)
                        if (abs(c) < 1e-15) Double.NaN else tan(a)
                    }
                    "asin" -> if (a < -1.0 || a > 1.0) Double.NaN else asin(a)
                    "acos" -> if (a < -1.0 || a > 1.0) Double.NaN else acos(a)
                    "atan" -> atan(a)
                    "log" -> if (a <= 0.0) Double.NaN else log10(a)
                    "ln" -> if (a <= 0.0) Double.NaN else ln(a)
                    "abs" -> abs(a)
                    "cbrt" -> cbrt(a)
                    "fact" -> {
                        if (a < 0.0 || a > 170.0 || a != floor(a)) Double.NaN
                        else {
                            var res = 1.0
                            val n = a.toInt()
                            for (k in 2..n) res *= k
                            res
                        }
                    }
                    else -> Double.NaN
                }
            }
            is CasEngine.Node.Comb -> {
                val nD = evalNode(node.n, xVal, yVal, params)
                val kD = evalNode(node.k, xVal, yVal, params)
                if (nD.isNaN() || kD.isNaN() || nD < 0.0 || kD < 0.0 || nD % 1.0 != 0.0 || kD % 1.0 != 0.0 || kD > nD) {
                    Double.NaN
                } else {
                    val resNode = CasEngine().evalCombinatorics(node.isPerm, nD.toLong(), kD.toLong())
                    when (resNode) {
                        is CasEngine.Node.Num -> resNode.value.toDouble()
                        is CasEngine.Node.Approx -> resNode.value
                        else -> Double.NaN
                    }
                }
            }
        }
    }

    /**
     * Обратная совместимость для вычисления узла по (x, y) без дополнительных параметров.
     */
    fun evalNodeAtXY(node: CasEngine.Node, xVal: Double, yVal: Double): Double {
        return evalNode(node, xVal, yVal, emptyMap())
    }

    private fun normalizeExpr(str: String): String {
        var s = str
            .replace('х', 'x')
            .replace('Х', 'x')
            .replace('у', 'y')
            .replace('У', 'y')
            .replace("≤", "<=")
            .replace("≥", ">=")
            .replace("−", "-")
            .replace("–", "-")
            .replace("—", "-")
            .replace("·", "*")
            .replace("×", "*")
            .replace("÷", "/")
            .replace("√", "sqrt")

        if (s.contains('|')) {
            val sb = StringBuilder()
            var inside = false
            for (ch in s) {
                if (ch == '|') {
                    if (!inside) {
                        sb.append("abs(")
                        inside = true
                    } else {
                        sb.append(")")
                        inside = false
                    }
                } else {
                    sb.append(ch)
                }
            }
            s = sb.toString()
        }

        return s
    }
}
