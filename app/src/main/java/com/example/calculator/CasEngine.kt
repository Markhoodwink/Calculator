package com.example.calculator

import com.example.calculator.CasEngine.Fraction
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.*

data class CasStep(
    val badge: String,
    val title: String,
    val formula: String,
    val explanation: String
)

class CasEngine {

    var isDegreeMode: Boolean = true
    var lastAnswer: String = "0"

    companion object {
        val PHYSICAL_CONSTANTS: Map<String, Double> = mapOf(
            "Nₐ" to 6e23,
            "Na" to 6e23,
            "c" to 3e8,
            "h" to 6.6e-34,
            "g" to 10.0,
            "G" to 6.7e-11,
            "k" to 9e9,
            "k_B" to 1.38e-23,
            "kB" to 1.38e-23,
            "R" to 8.31,
            "ε₀" to 8.85e-12,
            "e₀" to 8.85e-12,
            "eps0" to 8.85e-12,
            "μ₀" to 1.26e-6,
            "mu0" to 1.26e-6,
            "qₑ" to 1.6e-19,
            "qe" to 1.6e-19,
            "mₑ" to 9.1e-31,
            "me" to 9.1e-31,
            "mₚ" to 1.67e-27,
            "mp" to 1.67e-27,
            "e" to Math.E,
            "π" to Math.PI,
            "pi" to Math.PI
        )

        fun isConstant(name: String): Boolean {
            return PHYSICAL_CONSTANTS.containsKey(name)
        }

        fun getConstantValue(name: String): Double? {
            return PHYSICAL_CONSTANTS[name] ?: PHYSICAL_CONSTANTS[name.lowercase()]
        }
    }

    // --- ТОЧНЫЕ РАЦИОНАЛЬНЫЕ ДРОБИ ---
    data class Fraction(val num: Long, val den: Long = 1L) {
        init {
            require(den != 0L) { "Деление на ноль" }
        }

        fun simplify(): Fraction {
            if (num == 0L) return Fraction(0L, 1L)
            val g = gcd(abs(num), abs(den))
            val sign = if (den < 0) -1L else 1L
            return Fraction((num / g) * sign, abs(den) / g)
        }

        operator fun plus(o: Fraction) = Fraction(num * o.den + o.num * den, den * o.den).simplify()
        operator fun minus(o: Fraction) = Fraction(num * o.den - o.num * den, den * o.den).simplify()
        operator fun times(o: Fraction) = Fraction(num * o.num, den * o.den).simplify()
        operator fun div(o: Fraction) = Fraction(num * o.den, den * o.num).simplify()
        fun negate() = Fraction(-num, den).simplify()
        fun toDouble(): Double = num.toDouble() / den.toDouble()

        override fun toString(): String {
            val s = simplify()
            return if (s.den == 1L) "${s.num}" else "${s.num}/${s.den}"
        }

        companion object {
            fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

            fun toSuperscript(exp: Int): String {
                val supDigits = mapOf(
                    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
                    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
                    '-' to '⁻', '+' to '⁺'
                )
                return exp.toString().map { supDigits[it] ?: it }.joinToString("")
            }

            /**
             * Форматирование по стандарту Casio Norm 1:
             * - строго 0 или |x| < 1e-15 -> "0"
             * - |x| >= 1e10 или |x| < 1e-3 -> m · 10^e (научная нотация с Unicode степенями)
             * - 1e-3 <= |x| < 1e10 -> десятичное число до 10-12 значащих цифр без хвостовых нулей
             */
            fun formatDoubleNorm1(d: Double): String {
                if (d.isNaN()) return "NaN"
                if (d.isInfinite()) return if (d > 0) "+∞" else "-∞"
                val absD = abs(d)
                if (absD == 0.0 || absD < 1e-300) return "0"

                return if (absD >= 1e10 || absD < 1e-3) {
                    var exp = floor(log10(absD)).toInt()
                    var mantissa = d / 10.0.pow(exp.toDouble())
                    var bd = BigDecimal(mantissa, MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros()
                    if (bd.abs() >= BigDecimal.TEN) {
                        exp++
                        mantissa /= 10.0
                        bd = BigDecimal(mantissa, MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros()
                    } else if (bd.abs() < BigDecimal.ONE && bd.abs() > BigDecimal.ZERO) {
                        exp--
                        mantissa *= 10.0
                        bd = BigDecimal(mantissa, MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros()
                    }
                    val mantissaStr = bd.toPlainString()
                    val sup = toSuperscript(exp)
                    "$mantissaStr · 10$sup"
                } else {
                    val bd = BigDecimal(d, MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
                    bd.toPlainString()
                }
            }

            fun fractionToPlainDecimalString(f: Fraction): String {
                if (f.num == 0L) return "0"
                return try {
                    val numBd = BigDecimal(f.num)
                    val denBd = BigDecimal(f.den)
                    val bd = numBd.divide(denBd, MathContext(16, RoundingMode.HALF_UP)).stripTrailingZeros()
                    bd.toPlainString()
                } catch (_: Exception) {
                    val d = f.toDouble()
                    if (abs(d) < 1e-15) "0"
                    else BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
                }
            }

            fun fromDecimalString(str: String): Fraction {
                val trimmed = str.trim()
                if (trimmed.isEmpty() || trimmed == "0" || trimmed == "0.0") return Fraction(0L, 1L)
                try {
                    val bd = BigDecimal(trimmed).stripTrailingZeros()
                    val scale = bd.scale()
                    val unscaled = bd.unscaledValue()
                    if (scale <= 0) {
                        val mult = BigInteger.TEN.pow(-scale)
                        val bigVal = unscaled.multiply(mult)
                        if (bigVal <= BigInteger.valueOf(Long.MAX_VALUE) &&
                            bigVal >= BigInteger.valueOf(Long.MIN_VALUE)) {
                            return Fraction(bigVal.toLong(), 1L)
                        }
                    } else {
                        val bigDen = BigInteger.TEN.pow(scale)
                        val g = unscaled.abs().gcd(bigDen)
                        val num = unscaled.divide(g)
                        val den = bigDen.divide(g)
                        if (num <= BigInteger.valueOf(Long.MAX_VALUE) &&
                            num >= BigInteger.valueOf(Long.MIN_VALUE) &&
                            den <= BigInteger.valueOf(Long.MAX_VALUE)) {
                            return Fraction(num.toLong(), den.toLong()).simplify()
                        }
                    }
                } catch (_: Exception) {}
                val d = trimmed.toDoubleOrNull() ?: 0.0
                return fromDouble(d)
            }

            fun fromDouble(d: Double): Fraction {
                if (d.isNaN() || d.isInfinite()) return Fraction(0, 1)
                if (abs(d) == 0.0 || abs(d) < 1e-300) return Fraction(0, 1)
                val rounded = d.roundToLong()
                if (rounded != 0L && abs(d - rounded) < 1e-11) return Fraction(rounded, 1L)

                // 1. Попытка через BigDecimal (для точных степеней 10 вроде 5e-9)
                try {
                    val bd = BigDecimal.valueOf(d).stripTrailingZeros()
                    val scale = bd.scale()
                    if (scale in 1..15) {
                        val unscaled = bd.unscaledValue()
                        val bigDen = BigInteger.TEN.pow(scale)
                        val g = unscaled.abs().gcd(bigDen)
                        val num = unscaled.divide(g)
                        val den = bigDen.divide(g)
                        if (num <= BigInteger.valueOf(Long.MAX_VALUE) &&
                            num >= BigInteger.valueOf(Long.MIN_VALUE) &&
                            den <= BigInteger.valueOf(1_000_000_000L)) {
                            return Fraction(num.toLong(), den.toLong()).simplify()
                        }
                    }
                } catch (_: Exception) {}

                // 2. Непрерывные дроби (Continued fractions) с ограничением знаменателя 10^9
                var x = abs(d)
                var h1 = 1L; var h2 = 0L
                var k1 = 0L; var k2 = 1L
                var b = x
                val maxDen = 1_000_000_000L
                do {
                    val a = floor(b).toLong()
                    if (a < 0) break
                    if (k1 > 0 && a > (Long.MAX_VALUE - k2) / k1) break
                    if (h1 > 0 && a > (Long.MAX_VALUE - h2) / h1) break
                    var aux = h1; h1 = a * h1 + h2; h2 = aux
                    aux = k1; k1 = a * k1 + k2; k2 = aux
                    val rem = b - a
                    if (rem < 1e-12) break
                    b = 1.0 / rem
                } while (abs(abs(d) - h1.toDouble() / k1.toDouble()) > 1e-9 && k1 < maxDen)

                val sign = if (d < 0) -1L else 1L
                if (k1 > 0) {
                    val frac = Fraction(sign * h1, k1).simplify()
                    if (abs(frac.toDouble() - d) < 1e-6) return frac
                }

                // 3. Fallback через BigDecimal округление
                try {
                    val bd = BigDecimal(d, MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros()
                    val scale = bd.scale()
                    if (scale in 1..12) {
                        val unscaled = bd.unscaledValue()
                        val bigDen = BigInteger.TEN.pow(scale)
                        val g = unscaled.abs().gcd(bigDen)
                        val num = unscaled.divide(g)
                        val den = bigDen.divide(g)
                        if (num <= BigInteger.valueOf(Long.MAX_VALUE) &&
                            num >= BigInteger.valueOf(Long.MIN_VALUE) &&
                            den <= BigInteger.valueOf(Long.MAX_VALUE)) {
                            return Fraction(num.toLong(), den.toLong()).simplify()
                        }
                    }
                } catch (_: Exception) {}

                return Fraction(d.roundToLong(), 1L)
            }
        }
    }

    // --- ДЕРЕВО АЛГЕБРАИЧЕСКИХ ВЫРАЖЕНИЙ (AST) ---
    sealed class Node {
        abstract fun containsVar(name: String): Boolean
        abstract fun precedence(): Int
        abstract fun toExprString(): String

        data class Num(val value: Fraction) : Node() {
            override fun containsVar(name: String) = false
            override fun precedence() = 4
            override fun toExprString() = value.toString()
        }

        data class Approx(val value: Double) : Node() {
            override fun containsVar(name: String) = false
            override fun precedence() = 4
            override fun toExprString() = Fraction.formatDoubleNorm1(value)
        }

        data class Variable(val name: String) : Node() {
            override fun containsVar(name: String) = this.name == name && !isConstant(name)
            override fun precedence() = 4
            override fun toExprString() = name
        }

        data class Add(val l: Node, val r: Node) : Node() {
            override fun containsVar(name: String) = l.containsVar(name) || r.containsVar(name)
            override fun precedence() = 1
            override fun toExprString(): String {
                val rStr = r.toExprString()
                return if (rStr.startsWith("-")) "${l.toExprString()} - ${rStr.substring(1)}"
                else "${l.toExprString()} + $rStr"
            }
        }

        data class Sub(val l: Node, val r: Node) : Node() {
            override fun containsVar(name: String) = l.containsVar(name) || r.containsVar(name)
            override fun precedence() = 1
            override fun toExprString(): String {
                if (l is Num && l.value.num == 0L) {
                    val rStr = if (r is Add || r is Sub) "(${r.toExprString()})" else r.toExprString()
                    return "-$rStr"
                }
                val rStr = if (r.precedence() <= 1) "(${r.toExprString()})" else r.toExprString()
                return "${l.toExprString()} - $rStr"
            }
        }

        data class Mul(val l: Node, val r: Node) : Node() {
            override fun containsVar(name: String) = l.containsVar(name) || r.containsVar(name)
            override fun precedence() = 2
            override fun toExprString(): String {
                val lStr = if (l.precedence() < 2) "(${l.toExprString()})" else l.toExprString()
                val rStr = if (r.precedence() < 2) "(${r.toExprString()})" else r.toExprString()

                // FIX 3: «2t», «6a» — без знака «·» между числом и переменной/функцией
                val canOmitDot = when {
                    l is Num -> r !is Num  // 2 * x -> "2x", 2 * sqrt(…) -> "2√(…)"
                    l is Variable -> r is Variable || r is Func || r is Sqrt || r is Pow
                    else -> false
                }

                return if (canOmitDot) {
                    // Не добавляем лишних скобок если правая часть — Add/Sub (они уже обёрнуты)
                    val formattedR = if (r is Add || r is Sub) "($rStr)" else rStr
                    "$lStr$formattedR"
                } else {
                    "$lStr · $rStr"
                }
            }
        }

        data class Div(val l: Node, val r: Node) : Node() {
            override fun containsVar(name: String) = l.containsVar(name) || r.containsVar(name)
            override fun precedence() = 2
            override fun toExprString(): String {
                val lStr = if (l.precedence() < 2) "(${l.toExprString()})" else l.toExprString()
                val rStr = if (r.precedence() <= 2) "(${r.toExprString()})" else r.toExprString()
                return "$lStr / $rStr"
            }
        }

        data class Pow(val base: Node, val exp: Node) : Node() {
            override fun containsVar(name: String) = base.containsVar(name) || exp.containsVar(name)
            override fun precedence() = 3
            override fun toExprString(): String {
                val bStr = if (base.precedence() < 3) "(${base.toExprString()})" else base.toExprString()
                val eStr = if (exp.precedence() < 3) "(${exp.toExprString()})" else exp.toExprString()
                return "$bStr^$eStr"
            }
        }

        data class Sqrt(val inner: Node) : Node() {
            override fun containsVar(name: String) = inner.containsVar(name)
            override fun precedence() = 4
            override fun toExprString(): String {
                val innerStr = inner.toExprString()
                return if ((inner is Num && inner.value.den == 1L) || inner is Variable) {
                    "√$innerStr"
                } else {
                    "√($innerStr)"
                }
            }
        }

        data class Func(val fn: String, val arg: Node) : Node() {
            override fun containsVar(name: String) = arg.containsVar(name)
            override fun precedence() = 4
            override fun toExprString() = "$fn(${arg.toExprString()})"
        }

        data class Comb(val isPerm: Boolean, val n: Node, val k: Node) : Node() {
            override fun containsVar(name: String) = n.containsVar(name) || k.containsVar(name)
            override fun precedence() = 4
            override fun toExprString(): String {
                val fn = if (isPerm) "A" else "C"
                return "$fn(${n.toExprString()}, ${k.toExprString()})"
            }
        }
    }

    fun evalCombinatorics(isPerm: Boolean, n: Long, k: Long): Node {
        if (n < 0L || k < 0L) {
            throw ArithmeticException("Индексы должны быть целыми неотрицательными числами")
        }
        if (k > n) {
            throw ArithmeticException("Верхний индекс k не может быть больше нижнего n")
        }
        val bigN = BigInteger.valueOf(n)

        val res: BigInteger = if (isPerm) {
            var acc = BigInteger.ONE
            for (i in 0L until k) {
                acc = acc * (bigN - BigInteger.valueOf(i))
            }
            acc
        } else {
            var acc = BigInteger.ONE
            val r = minOf(k, n - k)
            for (i in 0L until r) {
                acc = acc * (bigN - BigInteger.valueOf(i)) / BigInteger.valueOf(i + 1L)
            }
            acc
        }

        val maxLong = BigInteger.valueOf(Long.MAX_VALUE)
        return if (res <= maxLong) {
            Node.Num(Fraction(res.longValueExact()))
        } else {
            Node.Approx(res.toDouble())
        }
    }

    // --- ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ УПРОЩЕНИЯ ---
    private fun isZero(n: Node) = n is Node.Num && n.value.num == 0L
    private fun isOne(n: Node) = n is Node.Num && n.value.num == 1L && n.value.den == 1L
    private fun isMinusOne(n: Node) = n is Node.Num && n.value.num == -1L && n.value.den == 1L

    private fun negateNode(n: Node): Node {
        return when (n) {
            is Node.Num -> Node.Num(n.value.negate())
            is Node.Approx -> Node.Approx(-n.value)
            is Node.Sub -> if (isZero(n.l)) n.r else Node.Sub(n.r, n.l)
            is Node.Mul -> if (n.l is Node.Num) Node.Mul(Node.Num(n.l.value.negate()), n.r) else if (n.l is Node.Approx) Node.Mul(Node.Approx(-n.l.value), n.r) else Node.Sub(Node.Num(Fraction(0)), n)
            else -> Node.Sub(Node.Num(Fraction(0)), n)
        }
    }

    private fun getDoubleValue(n: Node): Double? {
        return when (n) {
            is Node.Num -> n.value.toDouble()
            is Node.Approx -> n.value
            else -> null
        }
    }

    fun simplifyRadicalFraction(radicand: Fraction): Pair<Fraction, Long> {
        val s = radicand.simplify()
        if (s.num <= 0L) return Pair(Fraction(0), 0L)
        val p = s.num
        val q = s.den
        val n = p * q
        var k = 1L
        var m = n
        var d = 2L
        while (d * d <= m) {
            val d2 = d * d
            while (m % d2 == 0L) {
                k *= d
                m /= d2
            }
            d++
        }
        val coef = Fraction(k, q).simplify()
        return Pair(coef, m)
    }

    private fun exactCbrtFraction(f: Fraction): Fraction? {
        val s = f.simplify()
        if (s.num == 0L) return Fraction(0)
        val sign = if (s.num < 0) -1L else 1L
        val absNum = abs(s.num)
        val cn = cbrt(absNum.toDouble()).roundToLong()
        val cd = cbrt(s.den.toDouble()).roundToLong()
        return if (cn * cn * cn == absNum && cd * cd * cd == s.den) Fraction(sign * cn, cd) else null
    }

    private fun gcdLong(a: Long, b: Long): Long {
        var x = abs(a)
        var y = abs(b)
        while (y != 0L) {
            val t = y
            y = x % y
            x = t
        }
        return x
    }

    private fun getIntegerContent(node: Node): Long {
        return when (node) {
            is Node.Num -> if (node.value.den == 1L) abs(node.value.num) else 1L
            is Node.Add -> gcdLong(getIntegerContent(node.l), getIntegerContent(node.r))
            is Node.Sub -> {
                val cl = getIntegerContent(node.l)
                val cr = getIntegerContent(node.r)
                if (isZero(node.l)) cr else gcdLong(cl, cr)
            }
            is Node.Mul -> {
                val cl = getIntegerContent(node.l)
                val cr = getIntegerContent(node.r)
                val product = cl * cr
                if (product > 1L) product else 1L
            }
            else -> 1L
        }
    }

    private fun divideNodeByInt(node: Node, factor: Long): Node {
        if (factor <= 1L) return node
        return when (node) {
            is Node.Num -> if (node.value.den == 1L && node.value.num % factor == 0L) {
                Node.Num(Fraction(node.value.num / factor, 1L))
            } else node
            is Node.Add -> Node.Add(divideNodeByInt(node.l, factor), divideNodeByInt(node.r, factor))
            is Node.Sub -> Node.Sub(divideNodeByInt(node.l, factor), divideNodeByInt(node.r, factor))
            is Node.Mul -> {
                val cl = getIntegerContent(node.l)
                if (cl > 1L && cl % factor == 0L) {
                    val newL = divideNodeByInt(node.l, factor)
                    if (isOne(newL)) node.r else Node.Mul(newL, node.r)
                } else {
                    val cr = getIntegerContent(node.r)
                    if (cr > 1L && cr % factor == 0L) {
                        val newR = divideNodeByInt(node.r, factor)
                        if (isOne(newR)) node.l else Node.Mul(node.l, newR)
                    } else node
                }
            }
            else -> node
        }
    }

    // ---- Вспомогательная: извлекаем подкоренное выражение из узла, если это √ ----
    private fun sqrtInner(n: Node): Node? = when (n) {
        is Node.Sqrt -> n.inner
        is Node.Pow -> {
            val e = n.exp
            if (e is Node.Num && e.value.num == 1L && e.value.den == 2L) n.base else null
        }
        else -> null
    }

    // FIX 3: распределяем константу-множитель внутрь скобок — 4·(a+b) -> 4a+4b
    // только для числовых коэффициентов, чтобы упростить дискриминант
    private fun distributeNum(coef: Fraction, expr: Node): Node {
        if (coef.num == 1L && coef.den == 1L) return expr
        return when (expr) {
            is Node.Add -> Node.Add(distributeNum(coef, expr.l), distributeNum(coef, expr.r))
            is Node.Sub -> Node.Sub(distributeNum(coef, expr.l), distributeNum(coef, expr.r))
            is Node.Num -> Node.Num(coef * expr.value)
            else -> Node.Mul(Node.Num(coef), expr)
        }
    }

    fun simplify(node: Node): Node {
        return when (node) {
            is Node.Num -> node
            is Node.Approx -> node
            is Node.Variable -> {
                val constVal = if (node.name == "π" || node.name == "pi" || node.name.equals("e", ignoreCase = true)) null else getConstantValue(node.name)
                if (constVal != null) {
                    if (abs(constVal) in 1e-11..1e11) {
                        Node.Num(Fraction.fromDouble(constVal))
                    } else {
                        node
                    }
                } else node
            }
            is Node.Add -> {
                val l = simplify(node.l)
                val r = simplify(node.r)
                if (l is Node.Approx || r is Node.Approx) {
                    val lVal = getDoubleValue(l)
                    val rVal = getDoubleValue(r)
                    if (lVal != null && rVal != null) {
                        return Node.Approx(lVal + rVal)
                    }
                }
                when {
                    l is Node.Num && r is Node.Num -> {
                        val d = l.value.toDouble() + r.value.toDouble()
                        if (abs(d) >= 9e15) Node.Approx(d)
                        else {
                            try {
                                val sumFrac = l.value + r.value
                                if (abs(sumFrac.num) >= 9_000_000_000_000_000L || abs(sumFrac.den) >= 9_000_000_000_000_000L) {
                                    Node.Approx(sumFrac.toDouble())
                                } else Node.Num(sumFrac)
                            } catch (_: Exception) {
                                Node.Approx(d)
                            }
                        }
                    }
                    isZero(l) -> r
                    isZero(r) -> l
                    l.toExprString() == r.toExprString() -> simplify(Node.Mul(Node.Num(Fraction(2)), l))
                    else -> Node.Add(l, r)
                }
            }
            is Node.Sub -> {
                val l = simplify(node.l)
                val r = simplify(node.r)
                if (l is Node.Approx || r is Node.Approx) {
                    val lVal = getDoubleValue(l)
                    val rVal = getDoubleValue(r)
                    if (lVal != null && rVal != null) {
                        return Node.Approx(lVal - rVal)
                    }
                }
                when {
                    l is Node.Num && r is Node.Num -> {
                        val d = l.value.toDouble() - r.value.toDouble()
                        if (abs(d) >= 9e15) Node.Approx(d)
                        else {
                            try {
                                val diffFrac = l.value - r.value
                                if (abs(diffFrac.num) >= 9_000_000_000_000_000L || abs(diffFrac.den) >= 9_000_000_000_000_000L) {
                                    Node.Approx(diffFrac.toDouble())
                                } else Node.Num(diffFrac)
                            } catch (_: Exception) {
                                Node.Approx(d)
                            }
                        }
                    }
                    isZero(r) -> l
                    l.toExprString() == r.toExprString() -> Node.Num(Fraction(0))
                    r is Node.Num && r.value.num < 0 -> simplify(Node.Add(l, Node.Num(r.value.negate())))
                    else -> Node.Sub(l, r)
                }
            }
            is Node.Mul -> {
                val l = simplify(node.l)
                val r = simplify(node.r)

                if (l is Node.Approx || r is Node.Approx) {
                    val lVal = getDoubleValue(l)
                    val rVal = getDoubleValue(r)
                    if (lVal != null && rVal != null) {
                        return Node.Approx(lVal * rVal)
                    }
                }

                // sqrt(x) * sqrt(x) -> x
                val lInner = sqrtInner(l)
                val rInner = sqrtInner(r)
                if (lInner != null && rInner != null &&
                    lInner.toExprString() == rInner.toExprString()) {
                    return simplify(lInner)
                }

                // FIX 3: num * (Add/Sub) -> distribute to collapse 4*(3-2x) -> 12-8x
                if (l is Node.Num && (r is Node.Add || r is Node.Sub)) {
                    return simplify(distributeNum(l.value, r))
                }
                if (r is Node.Num && (l is Node.Add || l is Node.Sub)) {
                    return simplify(distributeNum(r.value, l))
                }

                when {
                    l is Node.Num && r is Node.Num -> {
                        val d = l.value.toDouble() * r.value.toDouble()
                        if (abs(d) >= 9e15) Node.Approx(d)
                        else {
                            try {
                                val prodFrac = l.value * r.value
                                if (abs(prodFrac.num) >= 9_000_000_000_000_000L || abs(prodFrac.den) >= 9_000_000_000_000_000L) {
                                    Node.Approx(prodFrac.toDouble())
                                } else Node.Num(prodFrac)
                            } catch (_: Exception) {
                                Node.Approx(d)
                            }
                        }
                    }
                    isZero(l) || isZero(r) -> Node.Num(Fraction(0))
                    isOne(l) -> r
                    isOne(r) -> l
                    isMinusOne(l) -> simplify(negateNode(r))
                    isMinusOne(r) -> simplify(negateNode(l))
                    l is Node.Mul && l.l is Node.Num && r is Node.Num ->
                        simplify(Node.Mul(Node.Num(l.l.value * r.value), l.r))
                    l is Node.Num && r is Node.Mul && r.l is Node.Num ->
                        simplify(Node.Mul(Node.Num(l.value * r.l.value), r.r))
                    l.toExprString() == r.toExprString() -> simplify(Node.Pow(l, Node.Num(Fraction(2))))
                    else -> Node.Mul(l, r)
                }
            }
            is Node.Div -> {
                var l = simplify(node.l)
                var r = simplify(node.r)

                if (l is Node.Approx || r is Node.Approx) {
                    val lVal = getDoubleValue(l)
                    val rVal = getDoubleValue(r)
                    if (lVal != null && rVal != null) {
                        if (rVal == 0.0) throw ArithmeticException("Деление на ноль")
                        return Node.Approx(lVal / rVal)
                    }
                }

                if (r is Node.Num && r.value.num < 0) {
                    l = negateNode(l)
                    r = Node.Num(r.value.negate())
                } else if (r is Node.Sub && isZero(r.l)) {
                    l = negateNode(l)
                    r = r.r
                }

                when {
                    isZero(r) -> throw ArithmeticException("Деление на ноль")
                    l is Node.Num && r is Node.Num -> {
                        val d = l.value.toDouble() / r.value.toDouble()
                        if (abs(d) >= 9e15) Node.Approx(d)
                        else {
                            try {
                                val divFrac = l.value / r.value
                                if (abs(divFrac.num) >= 9_000_000_000_000_000L || abs(divFrac.den) >= 9_000_000_000_000_000L) {
                                    Node.Approx(divFrac.toDouble())
                                } else Node.Num(divFrac)
                            } catch (_: Exception) {
                                Node.Approx(d)
                            }
                        }
                    }
                    isZero(l) -> Node.Num(Fraction(0))
                    isOne(r) -> l
                    isMinusOne(r) -> simplify(negateNode(l))
                    l.toExprString() == r.toExprString() -> Node.Num(Fraction(1))
                    else -> {
                        val numFactor = getIntegerContent(l)
                        val denFactor = getIntegerContent(r)
                        val gcdCommon = gcdLong(numFactor, denFactor)
                        if (gcdCommon > 1L) {
                            val reducedL = simplify(divideNodeByInt(l, gcdCommon))
                            val reducedR = simplify(divideNodeByInt(r, gcdCommon))
                            if (isOne(reducedR)) reducedL
                            else Node.Div(reducedL, reducedR)
                        } else {
                            Node.Div(l, r)
                        }
                    }
                }
            }
            is Node.Pow -> {
                val b = simplify(node.base)
                val e = simplify(node.exp)

                // Distribute integer exponent over products, quotients, and sqrts
                if (e is Node.Num && e.value.den == 1L && abs(e.value.num) in 1L..12L) {
                    val p = e.value.num
                    if (b is Node.Mul) {
                        return simplify(Node.Mul(Node.Pow(b.l, e), Node.Pow(b.r, e)))
                    }
                    if (b is Node.Div) {
                        return simplify(Node.Div(Node.Pow(b.l, e), Node.Pow(b.r, e)))
                    }
                    if (b is Node.Sqrt && p % 2L == 0L) {
                        return simplify(Node.Pow(b.inner, Node.Num(Fraction(p / 2L))))
                    }
                    if (b is Node.Sub && isZero(b.l)) {
                        val innerPow = simplify(Node.Pow(b.r, e))
                        return if (p % 2L == 0L) innerPow else simplify(negateNode(innerPow))
                    }
                }

                // sqrt(x)^2 -> x
                if (e is Node.Num && e.value.num == 2L && e.value.den == 1L) {
                    val inner = sqrtInner(b)
                    if (inner != null) return simplify(inner)
                }

                if (b is Node.Approx || e is Node.Approx) {
                    val bVal = getDoubleValue(b)
                    val eVal = getDoubleValue(e)
                    if (bVal != null && eVal != null) {
                        val d = bVal.pow(eVal)
                        if (d.isNaN() || d.isInfinite()) throw ArithmeticException("Математическая ошибка при возведении в степень")
                        return Node.Approx(d)
                    }
                }

                if (b is Node.Num && e is Node.Num) {
                    val p = e.value.num
                    val den = e.value.den
                    if (den == 1L && p == 0L) return Node.Num(Fraction(1))
                    if (den == 1L && abs(p) in 1..15) {
                        try {
                            var res = Fraction(1)
                            var overflowed = false
                            for (i in 0 until abs(p)) {
                                if (b.value.num != 0L && abs(res.num) > Long.MAX_VALUE / abs(b.value.num)) { overflowed = true; break }
                                if (b.value.den != 0L && abs(res.den) > Long.MAX_VALUE / abs(b.value.den)) { overflowed = true; break }
                                res = (res * b.value).simplify()
                            }
                            if (!overflowed) {
                                val finalFrac = if (p > 0) res else Fraction(1) / res
                                val d = finalFrac.toDouble()
                                if (abs(d) >= 9e15 || abs(finalFrac.num) >= 9_000_000_000_000_000L || abs(finalFrac.den) >= 9_000_000_000_000_000L) {
                                    return Node.Approx(d)
                                }
                                return Node.Num(finalFrac)
                            }
                        } catch (_: Exception) {}
                    }
                    val d = b.value.toDouble().pow(e.value.toDouble())
                    if (!d.isNaN() && !d.isInfinite()) {
                        if (abs(d) >= 9e15) return Node.Approx(d)
                        return Node.Num(Fraction.fromDouble(d))
                    }
                }
                if (isOne(e)) return b
                if (isZero(e)) return Node.Num(Fraction(1))
                if (isZero(b) && e is Node.Num && e.value.num > 0) return Node.Num(Fraction(0))
                if (isOne(b)) return Node.Num(Fraction(1))
                Node.Pow(b, e)
            }
            is Node.Sqrt -> {
                val inner = simplify(node.inner)
                if (inner is Node.Approx) {
                    if (inner.value < 0) throw ArithmeticException("Корень из отрицательного числа")
                    val d = sqrt(inner.value)
                    return if (abs(d) >= 9e15) Node.Approx(d) else Node.Num(Fraction.fromDouble(d))
                }
                if (inner is Node.Num && inner.value.toDouble() < 0) {
                    throw ArithmeticException("Корень из отрицательного числа")
                }
                if (inner is Node.Num) {
                    val (coef, m) = simplifyRadicalFraction(inner.value)
                    if (m == 0L) return Node.Num(Fraction(0))
                    if (m == 1L) return Node.Num(coef)
                    val sqrtM = Node.Sqrt(Node.Num(Fraction(m)))
                    return when {
                        coef.num == 1L && coef.den == 1L -> sqrtM
                        coef.den == 1L -> Node.Mul(Node.Num(Fraction(coef.num)), sqrtM)
                        coef.num == 1L -> Node.Div(sqrtM, Node.Num(Fraction(coef.den)))
                        else -> Node.Div(Node.Mul(Node.Num(Fraction(coef.num)), sqrtM), Node.Num(Fraction(coef.den)))
                    }
                }
                if (inner is Node.Pow && inner.exp is Node.Num &&
                    inner.exp.value.num == 2L && inner.exp.value.den == 1L) {
                    return inner.base
                }
                Node.Sqrt(inner)
            }
            is Node.Func -> {
                val arg = simplify(node.arg)
                val argVal: Double? = when (arg) {
                    is Node.Num -> arg.value.toDouble()
                    is Node.Approx -> arg.value
                    else -> null
                }
                if (argVal != null) {
                    if (node.fn == "fact") {
                        if (abs(argVal - round(argVal)) < 1e-9) {
                            val n = round(argVal).toInt()
                            if (n < 0) throw ArithmeticException("Факториал отрицательного числа не определён")
                            if (n > 170) throw ArithmeticException("Переполнение факториала")
                            if (n <= 20) {
                                var r = 1L
                                for (i in 2..n) r *= i
                                return Node.Num(Fraction(r))
                            } else {
                                var r = 1.0
                                for (i in 2..n) r *= i.toDouble()
                                return Node.Approx(r)
                            }
                        } else {
                            throw ArithmeticException("Факториал определён только для целых чисел")
                        }
                    }
                    if (arg is Node.Num && node.fn == "cbrt") {
                        val exact = exactCbrtFraction(arg.value)
                        if (exact != null) return Node.Num(exact)
                    }
                    val x = argVal

                    if ((node.fn == "ln" || node.fn == "log") && x <= 0) {
                        throw ArithmeticException(
                            if (x == 0.0) "Логарифм нуля не определён"
                            else "Логарифм отрицательного числа не определён"
                        )
                    }
                    if ((node.fn == "asin" || node.fn == "acos") && (x < -1.0 || x > 1.0)) {
                        throw ArithmeticException("Аргумент ${node.fn} вне области [-1, 1]: $x")
                    }

                    val rad = if (isDegreeMode) Math.toRadians(x) else x

                    if (node.fn == "tan") {
                        if (isDegreeMode) {
                            val xMod = ((x % 180.0) + 180.0) % 180.0
                            if (abs(xMod - 90.0) < 1e-9) {
                                throw ArithmeticException("tan(${x.toLong()}°) не определён (вертикальная асимптота)")
                            }
                        } else {
                            val cosVal = cos(rad)
                            if (abs(cosVal) < 1e-12) {
                                throw ArithmeticException("tan не определён (вертикальная асимптота)")
                            }
                        }
                    }

                    val res = when (node.fn) {
                        "sin" -> sin(rad)
                        "cos" -> cos(rad)
                        "tan" -> tan(rad)
                        "asin" -> if (isDegreeMode) Math.toDegrees(asin(x)) else asin(x)
                        "acos" -> if (isDegreeMode) Math.toDegrees(acos(x)) else acos(x)
                        "atan" -> if (isDegreeMode) Math.toDegrees(atan(x)) else atan(x)
                        "log" -> log10(x)
                        "ln" -> ln(x)
                        "cbrt" -> cbrt(x)
                        else -> Double.NaN
                    }

                    if (res.isNaN()) {
                        throw ArithmeticException("Функция ${node.fn} вернула NaN для аргумента $x")
                    }
                    if (res.isInfinite()) {
                        throw ArithmeticException("Результат ${node.fn}($x) = ${if (res > 0) "+∞" else "-∞"}")
                    }
                    if (abs(res) >= 9e15 || arg is Node.Approx) return Node.Approx(res)
                    return Node.Num(Fraction.fromDouble(res))
                }
                Node.Func(node.fn, arg)
            }
            is Node.Comb -> {
                val sN = simplify(node.n)
                val sK = simplify(node.k)
                if (sN is Node.Num && sK is Node.Num) {
                    if (sN.value.den != 1L || sK.value.den != 1L) {
                        throw ArithmeticException("Индексы должны быть целыми неотрицательными числами")
                    }
                    evalCombinatorics(node.isPerm, sN.value.num, sK.value.num)
                } else if (sN is Node.Approx || sK is Node.Approx) {
                    val nD = getDoubleValue(sN) ?: throw ArithmeticException("Индексы должны быть целыми неотрицательными числами")
                    val kD = getDoubleValue(sK) ?: throw ArithmeticException("Индексы должны быть целыми неотрицательными числами")
                    if (nD < 0.0 || kD < 0.0 || nD % 1.0 != 0.0 || kD % 1.0 != 0.0) {
                        throw ArithmeticException("Индексы должны быть целыми неотрицательными числами")
                    }
                    if (kD > nD) {
                        throw ArithmeticException("Верхний индекс k не может быть больше нижнего n")
                    }
                    evalCombinatorics(node.isPerm, nD.toLong(), kD.toLong())
                } else {
                    Node.Comb(node.isPerm, sN, sK)
                }
            }
        }
    }

    // --- ПАРСЕР ВЫРАЖЕНИЙ ---
    class Parser(input: String, private val ansVal: String = "0") {
        private val src = preprocess(input)
        private var pos = 0

        private fun preprocess(raw: String): String {
            val cleanAns = if (ansVal.isBlank()) "0" else {
                val part = if (ansVal.contains("=")) ansVal.substringAfter("=").trim() else ansVal.trim()
                if (part.isBlank()) "0" else part
            }
            val withAns = raw
                .replace("Ans", "($cleanAns)")
                .replace("ans", "($cleanAns)")
                .replace("ANS", "($cleanAns)")

            val supMap = mapOf(
                '⁰' to '0', '¹' to '1', '²' to '2', '3' to '3', '⁴' to '4',
                '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9',
                '⁻' to '-', '⁺' to '+'
            )
            val supRegex = Regex("""[⁻⁺]?[⁰¹²³⁴⁵⁶⁷⁸⁹]+""")
            val sWithSup = supRegex.replace(withAns) { m ->
                val converted = m.value.map { supMap[it] ?: it }.joinToString("")
                if (converted.startsWith("-")) "^($converted)" else "^$converted"
            }
            val s = sWithSup.replace(" ", "")
                .replace("−", "-")
                .replace("×", "*")
                .replace("·", "*")
                .replace("⋅", "*")
                .replace("∙", "*")
                .replace("÷", "/")
                .replace("³√", "cbrt")
                .replace("∛", "cbrt")

            val reservedWords = listOf(
                "comb", "perm", "asin", "acos", "atan", "cbrt", "sqrt", "fact", "sin", "cos", "tan", "log", "ln", "abs",
                "eps0", "mu0", "k_B", "kB", "Nₐ", "Na", "e₀", "ε₀", "μ₀",
                "mₑ", "me", "mₚ", "mp", "qₑ", "qe", "pi"
            )

            fun isIdentPart(ch: Char): Boolean =
                ch.isLetter() || ch == '_' || ch in "₀₁₂₃₄₅₆₇₈₉ₐₑₚₒₓ"

            fun tokenizeLetterSeq(seq: String): List<String> {
                val tokens = mutableListOf<String>()
                var k = 0
                while (k < seq.length) {
                    var matched: String? = null
                    for (rw in reservedWords) {
                        if (seq.startsWith(rw, k)) {
                            matched = rw
                            break
                        }
                    }
                    if (matched != null) {
                        tokens.add(matched)
                        k += matched.length
                    } else {
                        tokens.add(seq[k].toString())
                        k++
                    }
                }
                return tokens
            }

            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (isIdentPart(c)) {
                    val start = i
                    while (i < s.length && isIdentPart(s[i])) {
                        i++
                    }
                    val letterSeq = s.substring(start, i)
                    val tokens = tokenizeLetterSeq(letterSeq)
                    sb.append(tokens.joinToString("*"))
                    continue
                } else {
                    sb.append(c)
                }
                i++
            }

            val pass1 = sb.toString()

            val sb2 = StringBuilder()
            var j = 0
            while (j < pass1.length) {
                val c = pass1[j]
                sb2.append(c)
                if (j + 1 < pass1.length) {
                    val next = pass1[j + 1]
                    val isCurrEnd = c.isDigit() || c == ')' || c == '!' || c == '%' || c == 'π' || c in "₀₁₂₃₄₅₆₇₈₉ₐₑₚₒₓ"
                    val isNextStart = next.isLetter() || next == '(' || next == '√' || next == 'π' || next == '_' ||
                            next in "₀₁₂₃₄₅₆₇₈₉ₐₑₚₒₓ" ||
                            (next.isDigit() && (c == ')' || c == '%' || c == '!' || c == 'π' || c.isLetter() || c in "₀₁₂₃₄₅₆₇₈₉ₐₑₚₒₓ"))
                    if (isCurrEnd && isNextStart) {
                        sb2.append('*')
                    }
                }
                j++
            }

            var result = sb2.toString()

            val divRegex = Regex("""/(\\d+\\*([a-zA-Z]\\*)*[a-zA-Z])""")
            result = divRegex.replace(result) { match ->
                "/(" + match.groupValues[1] + ")"
            }

            return result
        }

        fun parse(): Node {
            if (src.isEmpty()) return Node.Num(Fraction(0))
            val node = parseAddSub()
            if (pos < src.length) throw IllegalArgumentException("Синтаксическая ошибка возле '${src[pos]}'")
            return node
        }

        private fun parseAddSub(): Node {
            var left = parseMulDiv()
            while (pos < src.length) {
                when (src[pos]) {
                    '+' -> { pos++; left = Node.Add(left, parseMulDiv()) }
                    '-' -> { pos++; left = Node.Sub(left, parseMulDiv()) }
                    else -> break
                }
            }
            return left
        }

        private fun parseMulDiv(): Node {
            var left = parsePow()
            while (pos < src.length) {
                when (src[pos]) {
                    '*' -> { pos++; left = Node.Mul(left, parsePow()) }
                    '/' -> { pos++; left = Node.Div(left, parsePow()) }
                    else -> break
                }
            }
            return left
        }

        private fun parsePow(): Node {
            var left = parsePostfix()
            if (pos < src.length && src[pos] == '^') {
                pos++
                left = Node.Pow(left, parsePow())
            }
            return left
        }

        private fun parsePostfix(): Node {
            var node = parseFactor()
            while (pos < src.length && (src[pos] == '!' || src[pos] == '%')) {
                if (src[pos] == '!') {
                    pos++
                    node = Node.Func("fact", node)
                } else if (src[pos] == '%') {
                    pos++
                    node = Node.Div(node, Node.Num(Fraction(100)))
                }
            }
            return node
        }

        private fun parseFactor(): Node {
            if (pos >= src.length) throw IllegalArgumentException("Незавершенное выражение")

            if (src[pos] == '-') {
                pos++
                return Node.Sub(Node.Num(Fraction(0)), parsePow())
            }
            if (src[pos] == '+') {
                pos++
                return parsePow()
            }
            if (src[pos] == '(') {
                pos++
                val res = parseAddSub()
                if (pos < src.length && src[pos] == ')') pos++
                return res
            }
            if (src[pos] == '√') {
                pos++
                if (pos < src.length && src[pos] == '(') {
                    pos++
                    val inner = parseAddSub()
                    if (pos < src.length && src[pos] == ')') pos++
                    return Node.Sqrt(inner)
                }
                return Node.Sqrt(parseFactor())
            }
            if (src[pos].isDigit() || src[pos] == '.') {
                val start = pos
                var hasDot = false
                while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) {
                    if (src[pos] == '.') hasDot = true
                    pos++
                }
                if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E') &&
                    pos + 1 < src.length && (src[pos + 1].isDigit() || src[pos + 1] == '+' || src[pos + 1] == '-')) {
                    hasDot = true
                    pos++
                    if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                    while (pos < src.length && src[pos].isDigit()) pos++
                }
                val token = src.substring(start, pos)
                return if (hasDot) {
                    Node.Num(Fraction.fromDecimalString(token))
                } else {
                    val l = token.toLongOrNull()
                    if (l != null) Node.Num(Fraction(l)) else Node.Num(Fraction.fromDecimalString(token))
                }
            }
            fun isIdentStartChar(ch: Char): Boolean =
                ch.isLetter() || ch == 'π' || ch == '_' || ch in "₀₁₂₃₄₅₆₇₈₉ₐₑₚₒₓ"

            fun isIdentBodyChar(ch: Char): Boolean =
                ch.isLetterOrDigit() || ch == '_' || ch in "₀₁₂₃₄₅₆₇₈₉ₐₑₚₒₓ"

            if (isIdentStartChar(src[pos])) {
                if (src[pos] == 'π') {
                    pos++
                    return Node.Variable("π")
                }
                val start = pos
                while (pos < src.length && isIdentBodyChar(src[pos])) pos++
                val name = src.substring(start, pos)
                val funcs = setOf("sin", "cos", "tan", "asin", "acos", "atan", "log", "ln", "sqrt", "cbrt", "abs")
                if (name in funcs && pos < src.length && src[pos] == '(') {
                    pos++
                    val arg = parseAddSub()
                    if (pos < src.length && src[pos] == ')') pos++
                    return if (name == "sqrt") Node.Sqrt(arg) else Node.Func(name, arg)
                }
                if ((name == "comb" || name == "perm") && pos < src.length && src[pos] == '(') {
                    pos++
                    val arg1 = parseAddSub()
                    if (pos < src.length && src[pos] == ',') {
                        pos++
                    } else {
                        throw IllegalArgumentException("Ожидалась запятая ',' в $name(n, k)")
                    }
                    val arg2 = parseAddSub()
                    if (pos < src.length && src[pos] == ')') pos++
                    return Node.Comb(isPerm = (name == "perm"), n = arg1, k = arg2)
                }
                return Node.Variable(name)
            }
            throw IllegalArgumentException("Неожиданный символ '${src[pos]}'")
        }
    }

    private fun toRational(node: Node): Pair<Node, Node> {
        return when (node) {
            is Node.Add -> {
                val (nl, dl) = toRational(node.l)
                val (nr, dr) = toRational(node.r)
                if (dl.toExprString() == dr.toExprString()) {
                    Pair(simplify(Node.Add(nl, nr)), dl)
                } else {
                    Pair(simplify(Node.Add(Node.Mul(nl, dr), Node.Mul(nr, dl))), simplify(Node.Mul(dl, dr)))
                }
            }
            is Node.Sub -> {
                val (nl, dl) = toRational(node.l)
                val (nr, dr) = toRational(node.r)
                if (dl.toExprString() == dr.toExprString()) {
                    Pair(simplify(Node.Sub(nl, nr)), dl)
                } else {
                    Pair(simplify(Node.Sub(Node.Mul(nl, dr), Node.Mul(nr, dl))), simplify(Node.Mul(dl, dr)))
                }
            }
            is Node.Mul -> {
                val (nl, dl) = toRational(node.l)
                val (nr, dr) = toRational(node.r)
                Pair(simplify(Node.Mul(nl, nr)), simplify(Node.Mul(dl, dr)))
            }
            is Node.Div -> {
                val (nl, dl) = toRational(node.l)
                val (nr, dr) = toRational(node.r)
                Pair(simplify(Node.Mul(nl, dr)), simplify(Node.Mul(dl, nr)))
            }
            else -> Pair(node, Node.Num(Fraction(1)))
        }
    }

    // --- ПОЛИНОМИАЛЬНОЕ РАЗЛОЖЕНИЕ (до степени 4) ---
    private fun mulPoly(pl: Map<Int, Node>?, pr: Map<Int, Node>?, maxDeg: Int = 4): Map<Int, Node>? {
        if (pl == null || pr == null) return null
        val res = mutableMapOf<Int, Node>()
        for ((kl, vl) in pl) {
            for ((kr, vr) in pr) {
                if (kl + kr > maxDeg) return null
                val term = simplify(Node.Mul(vl, vr))
                val prev = res[kl + kr] ?: Node.Num(Fraction(0))
                res[kl + kr] = simplify(Node.Add(prev, term))
            }
        }
        return res
    }

    private fun toPolynomial(rawNode: Node, target: String, maxDeg: Int = 4): Map<Int, Node>? {
        val node = simplify(rawNode)
        if (!node.containsVar(target)) return mapOf(0 to node)
        return when (node) {
            is Node.Variable -> if (node.name == target) mapOf(1 to Node.Num(Fraction(1))) else mapOf(0 to node)
            is Node.Add -> {
                val pl = toPolynomial(node.l, target, maxDeg) ?: return null
                val pr = toPolynomial(node.r, target, maxDeg) ?: return null
                val res = pl.toMutableMap()
                for ((k, v) in pr) {
                    res[k] = simplify(Node.Add(res[k] ?: Node.Num(Fraction(0)), v))
                }
                res
            }
            is Node.Sub -> {
                val pl = toPolynomial(node.l, target, maxDeg) ?: return null
                val pr = toPolynomial(node.r, target, maxDeg) ?: return null
                val res = pl.toMutableMap()
                for ((k, v) in pr) {
                    res[k] = simplify(Node.Sub(res[k] ?: Node.Num(Fraction(0)), v))
                }
                res
            }
            is Node.Mul -> mulPoly(toPolynomial(node.l, target, maxDeg), toPolynomial(node.r, target, maxDeg), maxDeg)
            is Node.Div -> {
                if (node.r.containsVar(target)) return null
                val pl = toPolynomial(node.l, target, maxDeg) ?: return null
                pl.mapValues { (_, v) -> simplify(Node.Div(v, node.r)) }
            }
            is Node.Pow -> {
                if (node.exp is Node.Num && node.exp.value.den == 1L) {
                    when (node.exp.value.num) {
                        0L -> mapOf(0 to Node.Num(Fraction(1)))
                        1L -> toPolynomial(node.base, target, maxDeg)
                        2L -> {
                            val pb = toPolynomial(node.base, target, maxDeg) ?: return null
                            mulPoly(pb, pb, maxDeg)
                        }
                        3L -> {
                            val pb = toPolynomial(node.base, target, maxDeg) ?: return null
                            val sq = mulPoly(pb, pb, maxDeg) ?: return null
                            mulPoly(sq, pb, maxDeg)
                        }
                        4L -> {
                            val pb = toPolynomial(node.base, target, maxDeg) ?: return null
                            val sq = mulPoly(pb, pb, maxDeg) ?: return null
                            mulPoly(sq, sq, maxDeg)
                        }
                        else -> null
                    }
                } else null
            }
            else -> null
        }
    }

    private fun exactNthRoot(f: Fraction, n: Long): Fraction? {
        if (n <= 0L) return null
        val s = f.simplify()
        if (s.num < 0L) return null
        if (s.num == 0L) return Fraction(0L)
        val numD = s.num.toDouble().pow(1.0 / n).roundToLong()
        val denD = s.den.toDouble().pow(1.0 / n).roundToLong()
        if (numD <= 0L || denD <= 0L) return null
        return try {
            val pNum = BigInteger.valueOf(numD).pow(n.toInt())
            val pDen = BigInteger.valueOf(denD).pow(n.toInt())
            if (pNum == BigInteger.valueOf(s.num) && pDen == BigInteger.valueOf(s.den)) {
                Fraction(numD, denD)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractExclusions(leftAst: Node, rightAst: Node, dl: Node, dr: Node, target: String): List<String> {
        val excl = mutableSetOf<String>()

        fun processDen(node: Node) {
            if (!node.containsVar(target)) return
            val poly = toPolynomial(node, target, maxDeg = 2)
            if (poly != null) {
                val a = simplify(poly[2] ?: Node.Num(Fraction(0)))
                val b = simplify(poly[1] ?: Node.Num(Fraction(0)))
                val c = simplify(poly[0] ?: Node.Num(Fraction(0)))
                if (isZero(a) && !isZero(b)) {
                    val root = simplify(Node.Div(negateNode(c), b))
                    excl.add("$target ≠ ${root.toExprString()}")
                } else if (!isZero(a)) {
                    if (a is Node.Num && b is Node.Num && c is Node.Num) {
                        val aD = a.value.toDouble()
                        val bD = b.value.toDouble()
                        val cD = c.value.toDouble()
                        val D = bD * bD - 4.0 * aD * cD
                        if (D >= 0) {
                            val r1 = (-bD - sqrt(D)) / (2.0 * aD)
                            val r2 = (-bD + sqrt(D)) / (2.0 * aD)
                            excl.add("$target ≠ ${formatRoot(r1)}")
                            if (abs(r1 - r2) > 1e-9) {
                                excl.add("$target ≠ ${formatRoot(r2)}")
                            }
                        }
                    }
                }
            }
        }

        fun collect(n: Node) {
            when (n) {
                is Node.Div -> {
                    processDen(n.r)
                    collect(n.l)
                    collect(n.r)
                }
                is Node.Add -> { collect(n.l); collect(n.r) }
                is Node.Sub -> { collect(n.l); collect(n.r) }
                is Node.Mul -> { collect(n.l); collect(n.r) }
                is Node.Pow -> { collect(n.base); collect(n.exp) }
                is Node.Sqrt -> collect(n.inner)
                is Node.Func -> collect(n.arg)
                else -> {}
            }
        }

        collect(leftAst)
        collect(rightAst)
        processDen(dl)
        processDen(dr)

        return excl.toList()
    }

    data class SolveResult(
        val displayFormula: String,
        val primaryNode: Node?,
        val steps: List<String> = emptyList(),
        val exactFraction: Fraction? = null,
        val detailedSteps: List<CasStep> = emptyList()
    )

    private fun extractOdzSteps(leftAst: Node, rightAst: Node, target: String): List<CasStep> {
        val steps = mutableListOf<CasStep>()
        val odzItems = mutableListOf<String>()

        fun processDen(node: Node) {
            if (!node.containsVar(target)) return
            val dStr = node.toExprString()
            val poly = toPolynomial(node, target, maxDeg = 2)
            if (poly != null) {
                val a = simplify(poly[2] ?: Node.Num(Fraction(0)))
                val b = simplify(poly[1] ?: Node.Num(Fraction(0)))
                val c = simplify(poly[0] ?: Node.Num(Fraction(0)))
                if (isZero(a) && !isZero(b)) {
                    val root = simplify(Node.Div(negateNode(c), b))
                    odzItems.add("$dStr ≠ 0  ⇒  $target ≠ ${root.toExprString()}")
                    return
                }
            }
            odzItems.add("$dStr ≠ 0")
        }

        fun collectDenominators(node: Node) {
            when (node) {
                is Node.Div -> {
                    if (node.r.containsVar(target)) {
                        processDen(node.r)
                    }
                    collectDenominators(node.l)
                    collectDenominators(node.r)
                }
                is Node.Add -> { collectDenominators(node.l); collectDenominators(node.r) }
                is Node.Sub -> { collectDenominators(node.l); collectDenominators(node.r) }
                is Node.Mul -> { collectDenominators(node.l); collectDenominators(node.r) }
                is Node.Pow -> { collectDenominators(node.base); collectDenominators(node.exp) }
                is Node.Sqrt -> {
                    if (node.inner.containsVar(target)) {
                        val innerStr = node.inner.toExprString()
                        odzItems.add("$innerStr ≥ 0")
                    }
                    collectDenominators(node.inner)
                }
                is Node.Func -> collectDenominators(node.arg)
                else -> {}
            }
        }

        collectDenominators(leftAst)
        collectDenominators(rightAst)

        val uniqueItems = odzItems.distinct()
        if (uniqueItems.isNotEmpty()) {
            val formulaStr = uniqueItems.joinToString(";  ")
            steps.add(
                CasStep(
                    badge = "ОДЗ",
                    title = "Находим область допустимых значений",
                    formula = formulaStr,
                    explanation = "Знаменатель дроби не может быть равен нулю, а выражение под квадратным корнем должно быть неотрицательным."
                )
            )
        }

        return steps
    }

    private fun formatReadableExpr(expr: String): String {
        return expr
            .replace("*", "·")
            .replace("^2", "²")
            .replace("^3", "³")
    }

    private fun generateArithmeticSteps(trimmed: String, ast: Node, simplified: Node): List<CasStep> {
        val steps = mutableListOf<CasStep>()

        val mixedPattern = """\(\(\s*([0-9]+)\s*\)\+\(\s*([0-9]+)\s*\)/\(\s*([0-9]+)\s*\)\)""".toRegex()
        val inputReadable = mixedPattern.replace(trimmed) { m ->
            "${m.groupValues[1]} ${m.groupValues[2]}/${m.groupValues[3]}"
        }
        steps.add(
            CasStep(
                badge = "Условие",
                title = "Исходное выражение",
                formula = inputReadable,
                explanation = "Запись исходного выражения."
            )
        )

        if (mixedPattern.containsMatchIn(trimmed)) {
            val converted = mixedPattern.replace(trimmed) { m ->
                val w = m.groupValues[1].toLong()
                val n = m.groupValues[2].toLong()
                val d = m.groupValues[3].toLong()
                val num = w * d + n
                "$num/$d"
            }
            val explanationStr = mixedPattern.findAll(trimmed).map { m ->
                val w = m.groupValues[1]
                val n = m.groupValues[2]
                val d = m.groupValues[3]
                val num = w.toLong() * d.toLong() + n.toLong()
                "$w $n/$d = $num/$d"
            }.joinToString(", ")

            steps.add(
                CasStep(
                    badge = "Шаг 1",
                    title = "Переводим смешанные дроби в неправильные",
                    formula = converted,
                    explanation = "Преобразуем смешанные числа в неправильные дроби ($explanationStr)."
                )
            )
        }

        val combNodes = mutableListOf<Node.Comb>()
        fun findComb(n: Node) {
            if (n is Node.Comb) combNodes.add(n)
            when (n) {
                is Node.Add -> { findComb(n.l); findComb(n.r) }
                is Node.Sub -> { findComb(n.l); findComb(n.r) }
                is Node.Mul -> { findComb(n.l); findComb(n.r) }
                is Node.Div -> { findComb(n.l); findComb(n.r) }
                is Node.Pow -> { findComb(n.base); findComb(n.exp) }
                is Node.Sqrt -> findComb(n.inner)
                is Node.Func -> findComb(n.arg)
                is Node.Comb -> { findComb(n.n); findComb(n.k) }
                else -> {}
            }
        }
        findComb(ast)

        for (combNode in combNodes) {
            val sN = simplify(combNode.n)
            val sK = simplify(combNode.k)
            if (sN is Node.Num && sK is Node.Num && sN.value.den == 1L && sK.value.den == 1L) {
                val nVal = sN.value.num
                val kVal = sK.value.num
                if (nVal >= 0L && kVal >= 0L && kVal <= nVal) {
                    val symbol = if (combNode.isPerm) "A" else "C"
                    val nameStr = if (combNode.isPerm) "размещений" else "сочетаний"
                    val formulaTemplate = if (combNode.isPerm) "${symbol}_n^k = n! / (n - k)!" else "${symbol}_n^k = n! / (k! · (n - k)!)"
                    val diffNK = nVal - kVal
                    val subFormula = if (combNode.isPerm) {
                        "${symbol}_${nVal}^${kVal} = ${nVal}! / (${nVal} - ${kVal})! = ${nVal}! / ${diffNK}!"
                    } else {
                        "${symbol}_${nVal}^${kVal} = ${nVal}! / (${kVal}! · (${nVal} - ${kVal})!) = ${nVal}! / (${kVal}! · ${diffNK}!)"
                    }
                    val resNode = evalCombinatorics(combNode.isPerm, nVal, kVal)
                    val ansStr = resNode.toExprString()
                    steps.add(
                        CasStep(
                            badge = "Шаг ${steps.size}",
                            title = "Вычисляем число $nameStr ${symbol}_${nVal}^${kVal}",
                            formula = "$subFormula = $ansStr",
                            explanation = "Применяем формулу: $formulaTemplate. Подставляем n = $nVal, k = $kVal."
                        )
                    )
                }
            }
        }

        if (simplified is Node.Num) {
            val simpleStr = simplified.toExprString()
            if (steps.size == 2) {
                steps.add(
                    CasStep(
                        badge = "Шаг 2",
                        title = "Приводим дроби к общему знаменателю и вычисляем",
                        formula = simpleStr,
                        explanation = "Находим наименьший общий знаменатель (НОК) и выполняем арифметические действия."
                    )
                )
            } else if (trimmed.contains('^') || trimmed.contains("sqrt") || trimmed.contains("cbrt") ||
                trimmed.contains("sin") || trimmed.contains("cos") || trimmed.contains("tan") ||
                trimmed.contains("comb") || trimmed.contains("perm")
            ) {
                steps.add(
                    CasStep(
                        badge = "Шаг 1",
                        title = "Вычисляем значения функций и степеней",
                        formula = simpleStr,
                        explanation = "Выполняем операцию возведения в степень и функции по порядку действий."
                    )
                )
            } else if (steps.size == 1) {
                steps.add(
                    CasStep(
                        badge = "Шаг 1",
                        title = "Выполняем арифметические действия",
                        formula = simpleStr,
                        explanation = "Вычисляем значение выражения по порядку действий."
                    )
                )
            }
        } else {
            val simpleStr = simplified.toExprString()
            steps.add(
                CasStep(
                    badge = "Шаг 1",
                    title = "Раскрываем скобки и приводим подобные",
                    formula = simpleStr,
                    explanation = "Применяем математические свойства и группируем подобные слагаемые."
                )
            )
        }

        val finalStr = simplified.toExprString()
        val mixed = if (simplified is Node.Num) toMixedFraction(simplified.value) else null
        if (mixed != null) {
            val mixedStr = "${mixed.first} ${mixed.second}/${mixed.third}"
            steps.add(
                CasStep(
                    badge = "Ответ",
                    title = "Записываем ответ",
                    formula = finalStr,
                    explanation = "Получен точный результат: $finalStr (или $mixedStr в виде смешанной дроби)."
                )
            )
        } else {
            steps.add(
                CasStep(
                    badge = "Ответ",
                    title = "Записываем ответ",
                    formula = finalStr,
                    explanation = "Результат вычислений: $finalStr"
                )
            )
        }

        return steps
    }

    fun evaluateFull(expression: String, includeSteps: Boolean = true): SolveResult {
        return try {
            val trimmed = expression.trim()
            if (trimmed.isEmpty()) return SolveResult("0", Node.Num(Fraction(0)), emptyList(), Fraction(0))

            if (trimmed.contains("=")) {
                return isolateVariableFull(trimmed, "x")
            }

            // Одиночный числовой литерал (сохраняем исходную точность без вычислений)
            val singleNumberRegex = Regex("""^[+-]?((\d+(\.\d*)?)|(\.\d+))$""")
            if (trimmed.matches(singleNumberRegex)) {
                val frac = Fraction.fromDecimalString(trimmed)
                lastAnswer = trimmed
                val stepList = if (includeSteps) listOf(
                    CasStep("Условие", "Исходное выражение", trimmed, "Запись числового значения."),
                    CasStep("Ответ", "Записываем ответ", trimmed, "Значение: $trimmed")
                ) else emptyList()
                return SolveResult(
                    displayFormula = trimmed,
                    primaryNode = Node.Num(frac),
                    steps = listOf("Значение: $trimmed"),
                    exactFraction = frac,
                    detailedSteps = stepList
                )
            }

            val ast = Parser(trimmed, lastAnswer).parse()
            if (hasPhysicalConstant(ast)) {
                val d = evalNodeAtX(ast, 0.0)
                if (!d.isNaN() && !d.isInfinite()) {
                    val str = Fraction.formatDoubleNorm1(d)
                    lastAnswer = str
                    val frac = Fraction.fromDouble(d)
                    val stepList = if (includeSteps) listOf(
                        CasStep("Условие", "Исходное выражение", trimmed, "Запись выражения с физическими константами."),
                        CasStep("Шаг 1", "Вычисляем физическую константу", "$trimmed = $str", "Подставляем значения физических констант."),
                        CasStep("Ответ", "Записываем ответ", str, "Результат вычислений: $str")
                    ) else emptyList()
                    return SolveResult(str, Node.Num(frac), listOf("Ответ: $str"), frac, detailedSteps = stepList)
                }
            }

            val simplified = simplify(ast)
            val detailed = if (includeSteps) generateArithmeticSteps(trimmed, ast, simplified) else emptyList()

            if (simplified is Node.Num) {
                val frac = simplified.value
                val d = frac.toDouble()
                val hasDecimalInput = trimmed.contains('.') ||
                        trimmed.contains('·') ||
                        trimmed.contains('e', ignoreCase = true) ||
                        (trimmed.contains("Ans", ignoreCase = true) && (lastAnswer.contains('.') || lastAnswer.contains('·')))
                val str = if (hasDecimalInput) {
                    Fraction.formatDoubleNorm1(d)
                } else {
                    simplified.toExprString()
                }
                lastAnswer = str
                SolveResult(str, simplified, listOf("Ответ: $str"), frac, detailedSteps = detailed)
            } else if (simplified is Node.Approx) {
                val str = Fraction.formatDoubleNorm1(simplified.value)
                lastAnswer = str
                SolveResult(str, simplified, listOf("Ответ: $str"), null, detailedSteps = detailed)
            } else {
                val str = simplified.toExprString()
                SolveResult(str, simplified, listOf("Упрощено: $str"), detailedSteps = detailed)
            }
        } catch (e: Exception) {
            SolveResult("Ошибка: ${e.message ?: "неверный ввод"}", null)
        }
    }

    // --- ЧИСЛЕННОЕ НАХОЖДЕНИЕ КОРНЕЙ КУБИЧЕСКОГО УРАВНЕНИЯ (метод Кардано/тригонометрический) ---
    private fun solveCubicNumerical(aD: Double, bD: Double, cD: Double, dD: Double): List<Double> {
        // Нормируем: x³ + px² + qx + r = 0
        val p = bD / aD
        val q = cD / aD
        val r = dD / aD

        // Депрессированное кубическое: t³ + pt + q = 0, где x = t - p/3
        val shift = p / 3.0
        val P = q - p * p / 3.0
        val Q = r - p * q / 3.0 + 2.0 * p * p * p / 27.0

        val D = Q * Q / 4.0 + P * P * P / 27.0

        val roots = mutableListOf<Double>()
        if (D > 1e-10) {
            // Один вещественный корень
            val sqrtD = sqrt(D)
            val u = cbrt(-Q / 2.0 + sqrtD)
            val v = cbrt(-Q / 2.0 - sqrtD)
            roots.add(u + v - shift)
        } else if (D < -1e-10) {
            // Три различных вещественных корня — тригонометрический метод
            val m = 2.0 * sqrt(-P / 3.0)
            val theta = acos(3.0 * Q / (P * m)) / 3.0
            roots.add(m * cos(theta) - shift)
            roots.add(m * cos(theta + 2.0 * PI / 3.0) - shift)
            roots.add(m * cos(theta + 4.0 * PI / 3.0) - shift)
        } else {
            // D ≈ 0: кратный корень
            val u = cbrt(-Q / 2.0)
            val r1 = 2.0 * u - shift
            val r2 = -u - shift
            roots.add(r1)
            if (abs(r1 - r2) > 1e-9) roots.add(r2)
        }
        return roots
    }

    // Форматирование вещественного корня: пробуем сделать дробь, иначе Norm 1
    private fun formatRoot(v: Double): String {
        if (abs(v) < 1e-15) return "0"
        val f = Fraction.fromDouble(v)
        val approx = f.toDouble()
        return if (abs(approx - v) < 1e-9 && f.den < 1000000L) f.toString()
        else Fraction.formatDoubleNorm1(v)
    }

    private fun formatRootsDisplay(roots: List<Double>, target: String): String {
        if (roots.isEmpty()) return "Нет решений"
        if (roots.size == 1) return "$target = ${formatRoot(roots[0])}"
        val subs = listOf("₁", "₂", "₃", "₄", "₅")
        return roots.mapIndexed { idx, v ->
            "$target${subs.getOrElse(idx) { "${idx + 1}" }} = ${formatRoot(v)}"
        }.joinToString(";  ")
    }

    private fun formatRootsAnswer(roots: List<Double>, target: String): String {
        if (roots.isEmpty()) return "Нет решений"
        if (roots.size == 1) return "$target = ${formatRoot(roots[0])}"
        val subs = listOf("₁", "₂", "₃", "₄", "₅")
        return roots.mapIndexed { idx, v ->
            "$target${subs.getOrElse(idx) { "${idx + 1}" }} = ${formatRoot(v)}"
        }.joinToString("\n")
    }

    private fun solveBranching(
        argNode: Node,
        rootNode: Node,
        target: String,
        detailedSteps: MutableList<CasStep>,
        titleStr: String
    ): SolveResult {
        val stepNum = detailedSteps.count { it.badge.startsWith("Шаг") } + 1
        val aStr = argNode.toExprString()
        val rStr = rootNode.toExprString()

        detailedSteps.add(
            CasStep(
                badge = "Шаг $stepNum",
                title = titleStr,
                formula = "$aStr = $rStr  или  $aStr = -$rStr",
                explanation = "Раскрываем выражение со знаками плюс и минус."
            )
        )

        val res1 = isolateVariableFull("$aStr = $rStr", target)
        val res2 = isolateVariableFull("$aStr = -($rStr)", target)

        val candRoots = mutableListOf<Double>()

        fun collectRoots(res: SolveResult) {
            if (res.displayFormula != "Нет решений" && res.displayFormula != "Нет действительных корней") {
                if (res.primaryNode != null) {
                    val v = evalNodeAtX(res.primaryNode, 0.0)
                    if (!v.isNaN()) candRoots.add(v)
                }
                val regex = Regex("""[+-]?\d+(\.\d+)?""")
                for (match in regex.findAll(res.displayFormula)) {
                    val d = match.value.toDoubleOrNull()
                    if (d != null) candRoots.add(d)
                }
            }
        }

        collectRoots(res1)
        collectRoots(res2)

        val sortedRoots = candRoots.distinct().sorted()
        if (sortedRoots.isEmpty()) {
            detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений."))
            return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
        }

        val displayStr = formatRootsDisplay(sortedRoots, target)
        val answerFormula = formatRootsAnswer(sortedRoots, target)
        detailedSteps.add(CasStep("Ответ", "Записываем ответ", answerFormula, "Записываем найденные корни."))
        return SolveResult(displayStr, null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
    }

    private fun trySolveLogEquation(
        leftAst: Node,
        rightAst: Node,
        masterEq: Node,
        target: String,
        detailedSteps: MutableList<CasStep>
    ): SolveResult? {
        var logFn: String? = null
        var argA: Node? = null
        var argB: Node? = null

        if (leftAst is Node.Func && rightAst is Node.Func &&
            leftAst.fn == rightAst.fn && (leftAst.fn == "ln" || leftAst.fn == "log")) {
            logFn = leftAst.fn
            argA = leftAst.arg
            argB = rightAst.arg
        } else {
            val expr = simplify(masterEq)
            if (expr is Node.Sub) {
                val l = expr.l
                val r = expr.r
                if (l is Node.Func && r is Node.Func && l.fn == r.fn && (l.fn == "ln" || l.fn == "log")) {
                    logFn = l.fn
                    argA = l.arg
                    argB = r.arg
                }
            }
        }

        if (logFn == null || argA == null || argB == null) return null

        val fnName = if (logFn == "ln") "натурального" else "десятичного"
        detailedSteps.add(
            CasStep(
                badge = "ОДЗ",
                title = "Находим область допустимых значений",
                formula = "${argA.toExprString()} > 0;  ${argB.toExprString()} > 0",
                explanation = "Подлогарифмическое выражение $fnName логарифма должно быть строго больше нуля."
            )
        )

        detailedSteps.add(
            CasStep(
                badge = "Шаг 1",
                title = "Потенцируем уравнение",
                formula = "${argA.toExprString()} = ${argB.toExprString()}",
                explanation = "Так как основания логарифмов равны, приравниваем подлогарифмические выражения."
            )
        )

        val eqAB = simplify(Node.Sub(argA, argB))
        val poly = toPolynomial(eqAB, target, maxDeg = 4) ?: return null

        val a4 = simplify(poly[4] ?: Node.Num(Fraction(0)))
        val a3 = simplify(poly[3] ?: Node.Num(Fraction(0)))
        val a  = simplify(poly[2] ?: Node.Num(Fraction(0)))
        val b  = simplify(poly[1] ?: Node.Num(Fraction(0)))
        val c  = simplify(poly[0] ?: Node.Num(Fraction(0)))

        val candRoots = mutableListOf<Double>()
        if (isZero(a4) && isZero(a3) && !isZero(a)) {
            if (a is Node.Num && b is Node.Num && c is Node.Num) {
                val aD = a.value.toDouble()
                val bD = b.value.toDouble()
                val cD = c.value.toDouble()
                val D = bD * bD - 4.0 * aD * cD
                if (D >= 0) {
                    val sqrtD = sqrt(max(0.0, D))
                    candRoots.add((-bD - sqrtD) / (2.0 * aD))
                    if (D > 1e-9) candRoots.add((-bD + sqrtD) / (2.0 * aD))
                }
            }
        } else if (isZero(a4) && isZero(a3) && isZero(a) && !isZero(b)) {
            val sol = simplify(Node.Div(negateNode(c), b))
            val solVal = evalNodeAtX(sol, 0.0)
            if (!solVal.isNaN()) candRoots.add(solVal)
        }

        val validRoots = candRoots.distinct().sorted().filter { r ->
            val vA = evalNodeAtX(argA, r)
            val vB = evalNodeAtX(argB, r)
            val isValid = !vA.isNaN() && vA > 1e-9 && !vB.isNaN() && vB > 1e-9
            if (!isValid) {
                detailedSteps.add(
                    CasStep(
                        badge = "ОДЗ",
                        title = "Проверка корня по ОДЗ",
                        formula = "$target = ${formatRoot(r)} — посторонний корень по ОДЗ",
                        explanation = "При данном значении подлогарифмическое выражение не строго больше нуля."
                    )
                )
            }
            isValid
        }

        if (validRoots.isEmpty()) {
            detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет решений", "Все найденные корни являются посторонними по ОДЗ."))
            return SolveResult("Нет решений", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
        }

        val displayStr = formatRootsDisplay(validRoots, target)
        val answerFormula = formatRootsAnswer(validRoots, target)
        detailedSteps.add(CasStep("Ответ", "Записываем ответ", answerFormula, "Записываем корни логарифмического уравнения."))
        return SolveResult(displayStr, null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
    }

    private fun trySolveRadicalEquation(
        leftAst: Node,
        rightAst: Node,
        target: String,
        detailedSteps: MutableList<CasStep>
    ): SolveResult? {
        var radicalInner: Node? = null
        var rhsNode: Node? = null

        if (leftAst is Node.Sqrt) {
            radicalInner = leftAst.inner
            rhsNode = rightAst
        } else if (leftAst is Node.Func && leftAst.fn == "sqrt") {
            radicalInner = leftAst.arg
            rhsNode = rightAst
        } else if (rightAst is Node.Sqrt) {
            radicalInner = rightAst.inner
            rhsNode = leftAst
        } else if (rightAst is Node.Func && rightAst.fn == "sqrt") {
            radicalInner = rightAst.arg
            rhsNode = leftAst
        }

        if (radicalInner == null || rhsNode == null) return null

        val rVal = evalNodeAtX(rhsNode, 0.0)
        if (!rhsNode.containsVar(target) && rVal < -1e-9) {
            detailedSteps.add(CasStep("Шаг 1", "Анализируем квадратный корень", "√(...) = ${formatReadableExpr(rhsNode.toExprString())} < 0", "Арифметический квадратный корень не может быть отрицательным."))
            detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений на множестве действительных чисел."))
            return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
        }

        detailedSteps.add(
            CasStep(
                badge = "ОДЗ",
                title = "Находим область допустимых значений",
                formula = "${radicalInner.toExprString()} ≥ 0;  ${formatReadableExpr(rhsNode.toExprString())} ≥ 0",
                explanation = "Выражение под квадратным корнем и правая часть уравнения должны быть неотрицательными."
            )
        )

        detailedSteps.add(
            CasStep(
                badge = "Шаг 1",
                title = "Возводим обе части в квадрат",
                formula = "${radicalInner.toExprString()} = (${formatReadableExpr(rhsNode.toExprString())})²",
                explanation = "Возводим обе части уравнения в квадрат для избавления от знака корня."
            )
        )

        val sqEq = simplify(Node.Sub(radicalInner, Node.Pow(rhsNode, Node.Num(Fraction(2)))))
        val poly = toPolynomial(sqEq, target, maxDeg = 4) ?: return null

        val a4 = simplify(poly[4] ?: Node.Num(Fraction(0)))
        val a3 = simplify(poly[3] ?: Node.Num(Fraction(0)))
        val a  = simplify(poly[2] ?: Node.Num(Fraction(0)))
        val b  = simplify(poly[1] ?: Node.Num(Fraction(0)))
        val c  = simplify(poly[0] ?: Node.Num(Fraction(0)))

        val candRoots = mutableListOf<Double>()
        if (isZero(a4) && isZero(a3) && !isZero(a)) {
            if (a is Node.Num && b is Node.Num && c is Node.Num) {
                val aD = a.value.toDouble()
                val bD = b.value.toDouble()
                val cD = c.value.toDouble()
                val D = bD * bD - 4.0 * aD * cD
                if (D >= 0) {
                    val sqrtD = sqrt(max(0.0, D))
                    candRoots.add((-bD - sqrtD) / (2.0 * aD))
                    if (D > 1e-9) candRoots.add((-bD + sqrtD) / (2.0 * aD))
                }
            }
        } else if (isZero(a4) && isZero(a3) && isZero(a) && !isZero(b)) {
            val sol = simplify(Node.Div(negateNode(c), b))
            val solVal = evalNodeAtX(sol, 0.0)
            if (!solVal.isNaN()) candRoots.add(solVal)
        }

        val validRoots = candRoots.distinct().sorted().filter { r ->
            val vIn = evalNodeAtX(radicalInner, r)
            val vRhs = evalNodeAtX(rhsNode, r)
            val vL = evalNodeAtX(leftAst, r)
            val vR = evalNodeAtX(rightAst, r)
            val isValid = !vIn.isNaN() && vIn >= -1e-9 && !vRhs.isNaN() && vRhs >= -1e-9 &&
                    !vL.isNaN() && !vR.isNaN() && abs(vL - vR) < 1e-5
            if (!isValid) {
                detailedSteps.add(
                    CasStep(
                        badge = "ОДЗ",
                        title = "Проверка корня по ОДЗ",
                        formula = "$target = ${formatRoot(r)} — посторонний корень по ОДЗ",
                        explanation = "При $target = ${formatRoot(r)} правая часть или подкоренное выражение отрицательны, либо исходное равенство не выполняется."
                    )
                )
            }
            isValid
        }

        if (validRoots.isEmpty()) {
            detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет решений", "Все найденные корни являются посторонними по ОДЗ."))
            return SolveResult("Нет решений", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
        }

        val displayStr = formatRootsDisplay(validRoots, target)
        val answerFormula = formatRootsAnswer(validRoots, target)
        detailedSteps.add(CasStep("Ответ", "Записываем ответ", answerFormula, "Записываем корни иррационального уравнения."))
        return SolveResult(displayStr, null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
    }

    private fun normalizeBasePower(base: Long): Pair<Long, Long> {
        if (base <= 1L) return Pair(base, 1L)
        for (b in 2L..10L) {
            var pow = b
            var m = 1L
            while (pow < base) {
                pow *= b
                m++
            }
            if (pow == base) {
                return Pair(b, m)
            }
        }
        return Pair(base, 1L)
    }

    private fun collectAdditiveTerms(node: Node): List<Pair<Boolean, Node>> {
        val list = mutableListOf<Pair<Boolean, Node>>()
        fun walk(n: Node, positive: Boolean) {
            when (n) {
                is Node.Add -> { walk(n.l, positive); walk(n.r, positive) }
                is Node.Sub -> {
                    if (isZero(n.l)) {
                        walk(n.r, !positive)
                    } else {
                        walk(n.l, positive)
                        walk(n.r, !positive)
                    }
                }
                else -> if (!isZero(n)) list.add(Pair(positive, n))
            }
        }
        walk(node, true)
        return list
    }

    private data class ExpTermInfo(
        val coeff: Fraction,
        val base: Long?,
        val expPoly: Map<Int, Fraction>
    )

    private fun polyNodeToFracMap(map: Map<Int, Node>): Map<Int, Fraction>? {
        val res = mutableMapOf<Int, Fraction>()
        for ((deg, n) in map) {
            if (n is Node.Num) {
                res[deg] = n.value
            } else return null
        }
        return res
    }

    private fun scalePoly(p: Map<Int, Fraction>, factor: Fraction): Map<Int, Fraction> {
        return p.mapValues { (_, v) -> (v * factor).simplify() }
    }

    private fun addPolys(p1: Map<Int, Fraction>, p2: Map<Int, Fraction>): Map<Int, Fraction> {
        val keys = p1.keys + p2.keys
        val res = mutableMapOf<Int, Fraction>()
        for (k in keys) {
            val v = (p1[k] ?: Fraction(0)) + (p2[k] ?: Fraction(0))
            if (v.num != 0L) res[k] = v
        }
        return res
    }

    private fun subtractPolys(p1: Map<Int, Fraction>, p2: Map<Int, Fraction>): Map<Int, Fraction> {
        val keys = p1.keys + p2.keys
        val res = mutableMapOf<Int, Fraction>()
        for (k in keys) {
            val v = (p1[k] ?: Fraction(0)) - (p2[k] ?: Fraction(0))
            if (v.num != 0L) res[k] = v
        }
        return res
    }

    private fun polyEquals(p1: Map<Int, Fraction>, p2: Map<Int, Fraction>): Boolean {
        val keys = p1.keys + p2.keys
        return keys.all { k -> (p1[k] ?: Fraction(0)) == (p2[k] ?: Fraction(0)) }
    }

    private fun polyToNode(p: Map<Int, Fraction>, target: String): Node {
        if (p.isEmpty()) return Node.Num(Fraction(0))
        var sum: Node? = null
        for ((deg, coeff) in p.entries.sortedByDescending { it.key }) {
            if (coeff.num == 0L) continue
            val term = when (deg) {
                0 -> Node.Num(coeff)
                1 -> if (coeff == Fraction(1)) Node.Variable(target)
                     else if (coeff == Fraction(-1)) Node.Sub(Node.Num(Fraction(0)), Node.Variable(target))
                     else Node.Mul(Node.Num(coeff), Node.Variable(target))
                else -> {
                    val powNode = Node.Pow(Node.Variable(target), Node.Num(Fraction(deg.toLong())))
                    if (coeff == Fraction(1)) powNode
                    else if (coeff == Fraction(-1)) Node.Sub(Node.Num(Fraction(0)), powNode)
                    else Node.Mul(Node.Num(coeff), powNode)
                }
            }
            sum = if (sum == null) term else Node.Add(sum, term)
        }
        return sum ?: Node.Num(Fraction(0))
    }

    private fun formatPolyExpr(p: Map<Int, Fraction>, target: String): String {
        return polyToNode(p, target).toExprString()
    }

    private fun exactLogBase(base: Long, f: Fraction): Node {
        val s = f.simplify()
        if (s.num > 0L && s.den == 1L) {
            var p = 0L
            var valP = 1L
            while (valP < s.num && valP < Long.MAX_VALUE / base) {
                valP *= base
                p++
            }
            if (valP == s.num) return Node.Num(Fraction(p))
        }
        if (s.num == 1L && s.den > 0L) {
            var p = 0L
            var valP = 1L
            while (valP < s.den && valP < Long.MAX_VALUE / base) {
                valP *= base
                p++
            }
            if (valP == s.den) return Node.Num(Fraction(-p))
        }
        return Node.Div(Node.Func("ln", Node.Num(s)), Node.Func("ln", Node.Num(Fraction(base))))
    }

    private fun parseExpTerm(node: Node, target: String): ExpTermInfo? {
        when (node) {
            is Node.Num -> return ExpTermInfo(node.value, null, emptyMap())
            is Node.Pow -> {
                if (node.base is Node.Num && node.base.value.den == 1L && node.base.value.num > 1L) {
                    val rawBase = node.base.value.num
                    val (bMin, m) = normalizeBasePower(rawBase)
                    val polyNode = toPolynomial(node.exp, target, maxDeg = 2) ?: return null
                    val poly = polyNodeToFracMap(polyNode) ?: return null
                    val scaledPoly = scalePoly(poly, Fraction(m))
                    return ExpTermInfo(Fraction(1), bMin, scaledPoly)
                }
            }
            is Node.Mul -> {
                var coeff = Fraction(1)
                var powNode: Node.Pow? = null
                fun collectMul(n: Node) {
                    when (n) {
                        is Node.Num -> coeff = coeff * n.value
                        is Node.Sub -> if (isZero(n.l)) { coeff = coeff.negate(); collectMul(n.r) }
                        is Node.Pow -> powNode = n
                        is Node.Mul -> { collectMul(n.l); collectMul(n.r) }
                        else -> {}
                    }
                }
                collectMul(node)
                val pn = powNode
                if (pn != null) {
                    val bNode = pn.base
                    if (bNode is Node.Num && bNode.value.den == 1L && bNode.value.num > 1L) {
                        val rawBase = bNode.value.num
                        val (bMin, m) = normalizeBasePower(rawBase)
                        val polyNode = toPolynomial(pn.exp, target, maxDeg = 2) ?: return null
                        val poly = polyNodeToFracMap(polyNode) ?: return null
                        val scaledPoly = scalePoly(poly, Fraction(m))
                        return ExpTermInfo(coeff, bMin, scaledPoly)
                    }
                }
            }
            is Node.Sub -> {
                if (isZero(node.l)) {
                    val inner = parseExpTerm(node.r, target) ?: return null
                    return ExpTermInfo(inner.coeff.negate(), inner.base, inner.expPoly)
                }
            }
            else -> {}
        }
        return null
    }

    private fun trySolveExponentialEquation(
        leftAst: Node,
        rightAst: Node,
        target: String,
        detailedSteps: MutableList<CasStep>
    ): SolveResult? {
        val masterEq = simplify(Node.Sub(leftAst, rightAst))
        val terms = collectAdditiveTerms(masterEq)
        if (terms.isEmpty()) return null

        val parsedTerms = mutableListOf<ExpTermInfo>()
        for ((isPos, tNode) in terms) {
            val parsed = parseExpTerm(tNode, target) ?: return null
            val finalCoeff = if (isPos) parsed.coeff else parsed.coeff.negate()
            parsedTerms.add(parsed.copy(coeff = finalCoeff))
        }

        val nonConstBases = parsedTerms.mapNotNull { it.base }.distinct()
        if (nonConstBases.size != 1) return null
        val base = nonConstBases.first()
        if (base <= 1L) return null

        data class GroupedTerm(val effCoeff: Fraction, val Q: Map<Int, Fraction>)

        val grouped = mutableListOf<GroupedTerm>()
        for (pt in parsedTerms) {
            if (pt.base == null) {
                grouped.add(GroupedTerm(pt.coeff, emptyMap()))
            } else {
                val kFrac = pt.expPoly[0] ?: Fraction(0)
                if (kFrac.den != 1L) return null
                val k = kFrac.num
                val qPoly = pt.expPoly.toMutableMap()
                qPoly.remove(0)

                var basePowK = 1.0
                if (k >= 0) {
                    for (i in 0 until k) basePowK *= base.toDouble()
                } else {
                    for (i in 0 until -k) basePowK /= base.toDouble()
                }
                val basePowKFrac = Fraction.fromDouble(basePowK)
                val effCoeff = pt.coeff * basePowKFrac
                grouped.add(GroupedTerm(effCoeff, qPoly))
            }
        }

        val combinedMap = mutableMapOf<String, GroupedTerm>()
        for (gt in grouped) {
            val key = gt.Q.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
            val prev = combinedMap[key]
            if (prev != null) {
                combinedMap[key] = GroupedTerm(prev.effCoeff + gt.effCoeff, gt.Q)
            } else {
                combinedMap[key] = gt
            }
        }
        val combined = combinedMap.values.filter { it.effCoeff.num != 0L }

        val baseStr = base.toString()

        if (combined.size == 2) {
            val t1 = combined[0]
            val t2 = combined[1]

            val (expTerm, constTerm) = if (t1.Q.isNotEmpty() && t2.Q.isEmpty()) {
                Pair(t1, t2)
            } else if (t2.Q.isNotEmpty() && t1.Q.isEmpty()) {
                Pair(t2, t1)
            } else {
                val qDiff = subtractPolys(t1.Q, t2.Q)
                val rhs = (t2.effCoeff.negate() / t1.effCoeff).simplify()
                if (rhs.toDouble() <= 0) {
                    detailedSteps.add(CasStep("Шаг 1", "Анализируем показательное уравнение", "$baseStr^(...) = $rhs ≤ 0", "Показательная функция не может принимать отрицательные или нулевые значения."))
                    detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений."))
                    return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                }
                val targetLog = exactLogBase(base, rhs)
                detailedSteps.add(CasStep("Шаг 1", "Приводим к общему основанию", "$baseStr^(${formatPolyExpr(qDiff, target)}) = $rhs", "Используем свойства степеней."))
                val rhsEq = polyToNode(qDiff, target)
                val finalEq = simplify(Node.Sub(rhsEq, targetLog))
                return isolateVariableFull("${finalEq.toExprString()} = 0", target)
            }

            val rhs = (constTerm.effCoeff.negate() / expTerm.effCoeff).simplify()
            if (rhs.toDouble() <= 0) {
                detailedSteps.add(CasStep("Шаг 1", "Анализируем показательное уравнение", "$baseStr^(...) = $rhs ≤ 0", "Показательная функция не может принимать отрицательные или нулевые значения."))
                detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений."))
                return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
            }
            val targetLog = exactLogBase(base, rhs)
            val expEq = polyToNode(expTerm.Q, target)
            val finalEq = simplify(Node.Sub(expEq, targetLog))
            detailedSteps.add(CasStep("Шаг 1", "Уравниваем показатели степеней", "${expEq.toExprString()} = ${targetLog.toExprString()}", "Так как основания равны, приравниваем показатели."))
            return isolateVariableFull("${finalEq.toExprString()} = 0", target)
        }

        if (combined.size == 3) {
            val perm = listOf(
                Triple(combined[0], combined[1], combined[2]),
                Triple(combined[0], combined[2], combined[1]),
                Triple(combined[1], combined[0], combined[2]),
                Triple(combined[1], combined[2], combined[0]),
                Triple(combined[2], combined[0], combined[1]),
                Triple(combined[2], combined[1], combined[0])
            )

            var matchedTriple: Triple<GroupedTerm, GroupedTerm, GroupedTerm>? = null
            var uPoly: Map<Int, Fraction>? = null

            for (p in perm) {
                val qSum = addPolys(p.first.Q, p.third.Q)
                val doubleQ2 = scalePoly(p.second.Q, Fraction(2))
                if (polyEquals(qSum, doubleQ2)) {
                    val diff = subtractPolys(p.second.Q, p.third.Q)
                    if (diff.isNotEmpty()) {
                        matchedTriple = p
                        uPoly = diff
                        break
                    }
                }
            }

            if (matchedTriple != null && uPoly != null) {
                val (t1, t2, t3) = matchedTriple
                val a1 = t1.effCoeff
                val a2 = t2.effCoeff
                val a3 = t3.effCoeff

                val uStr = formatPolyExpr(uPoly, target)
                val step1Formula = "Пусть t = $baseStr^($uStr) > 0 ⇒ $a1·t² + ($a2)·t + ($a3) = 0"
                detailedSteps.add(
                    CasStep(
                        badge = "Шаг 1",
                        title = "Замена переменной в показательном уравнении",
                        formula = step1Formula,
                        explanation = "Выполняем замену t = $baseStr^($uStr) (где t > 0) и сводим уравнение к квадратному."
                    )
                )

                val aD = a1.toDouble()
                val bD = a2.toDouble()
                val cD = a3.toDouble()
                val D = bD * bD - 4.0 * aD * cD

                if (D < -1e-9) {
                    detailedSteps.add(CasStep("Шаг 2", "Анализируем дискриминант", "D < 0", "Квадратное уравнение для t не имеет решений."))
                    detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений."))
                    return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                }

                val sqrtD = sqrt(max(0.0, D))
                val tVals = listOf((-bD - sqrtD) / (2.0 * aD), (-bD + sqrtD) / (2.0 * aD)).distinct()
                val validTVals = tVals.filter { it > 1e-9 }

                if (validTVals.isEmpty()) {
                    detailedSteps.add(CasStep("Шаг 2", "Проверка t > 0", "t ≤ 0 для всех корней", "Все найденные корни t не удовлетворяют условию t > 0."))
                    detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений."))
                    return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                }

                val subResults = mutableListOf<SolveResult>()
                val uNode = polyToNode(uPoly, target)

                for (tVal in validTVals) {
                    val tFrac = Fraction.fromDouble(tVal)
                    val logNode = exactLogBase(base, tFrac)
                    val eqForX = simplify(Node.Sub(uNode, logNode))
                    val subRes = isolateVariableFull("${eqForX.toExprString()} = 0", target)
                    if (subRes.displayFormula != "Нет решений" && subRes.displayFormula != "Нет действительных корней") {
                        subResults.add(subRes)
                    }
                }

                if (subResults.isEmpty()) {
                    detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений."))
                    return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                }

                val allDisplays = subResults.flatMap { res ->
                    res.displayFormula.split(";").map { it.trim() }
                }.filter { it.isNotEmpty() }.distinct()

                val combinedDisplay = if (allDisplays.size == 1) {
                    allDisplays[0]
                } else {
                    val regexVal = Regex("""=\s*(.+)""")
                    val values = allDisplays.mapNotNull { d ->
                        regexVal.find(d)?.groupValues?.get(1)?.trim() ?: d
                    }
                    val subs = listOf("₁", "₂", "₃", "₄", "₅")
                    values.mapIndexed { idx, v ->
                        "$target${subs.getOrElse(idx) { "${idx + 1}" }} = $v"
                    }.joinToString(";  ")
                }

                subResults.forEach { detailedSteps.addAll(it.detailedSteps) }
                val answerFormula = combinedDisplay.replace(";  ", "\n")
                detailedSteps.add(CasStep("Ответ", "Записываем ответ", answerFormula, "Записываем корни показательного уравнения."))
                return SolveResult(combinedDisplay, null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
            }
        }

        return null
    }

    fun isolateVariableFull(equation: String, targetVar: String = "x"): SolveResult {
        return try {
            val target = targetVar.trim().ifEmpty { "x" }

            val normalizedEq = equation.trim()
                .replace("÷", "/")
                .replace("×", "*")
                .replace("·", "*")
                .replace("−", "-")

            // Если целевая переменная 'x' и уравнение содержит тригонометрию, делегируем в TrigSolverEngine
            val lowerEq = normalizedEq.lowercase()
            if (target == "x" && (lowerEq.contains("sin") || lowerEq.contains("cos") ||
                        lowerEq.contains("tan") || lowerEq.contains("tg") ||
                        lowerEq.contains("cot") || lowerEq.contains("ctg"))) {
                val trigRes = TrigSolverEngine.solve(normalizedEq, casEngine = this)
                val casSteps = trigRes.detailedSteps.map { CasStep(it.section, it.title, it.formula, it.explanation) }
                if (trigRes.generalSolutions.isNotEmpty()) {
                    val display = trigRes.generalSolutions.joinToString(";  ") { it.displayFormula }
                    return SolveResult(display, null, trigRes.steps, detailedSteps = casSteps)
                } else {
                    val err = trigRes.errorMessage ?: "Нет действительных корней"
                    val fallbackSteps = if (casSteps.isNotEmpty()) casSteps else listOf(
                        CasStep("Ответ", "Записываем ответ", err, "Уравнение не имеет решений на множестве действительных чисел.")
                    )
                    return SolveResult(err, null, trigRes.steps, detailedSteps = fallbackSteps)
                }
            }

            val parts = normalizedEq.split("=")
            val leftRaw = parts[0].trim()
            val rightRaw = if (parts.size >= 2 && parts[1].isNotBlank()) parts[1].trim() else "0"

            val leftAst = Parser(leftRaw, lastAnswer).parse()
            val rightAst = Parser(rightRaw, lastAnswer).parse()

            if (!leftAst.containsVar(target) && !rightAst.containsVar(target)) {
                return SolveResult("Переменная '$target' не найдена", null)
            }

            val detailedSteps = mutableListOf<CasStep>()

            val odzSteps = extractOdzSteps(leftAst, rightAst, target)
            detailedSteps.addAll(odzSteps)

            val (nl, dl) = toRational(leftAst)
            val (nr, dr) = toRational(rightAst)
            val masterEq = simplify(Node.Sub(Node.Mul(nl, dr), Node.Mul(nr, dl)))

            val masterStr = formatReadableExpr(masterEq.toExprString())

            detailedSteps.add(
                CasStep(
                    badge = "Шаг 1",
                    title = "Приводим уравнение к стандартному виду",
                    formula = "$masterStr = 0",
                    explanation = "Переносим все слагаемые в левую часть и приводим подобные."
                )
            )

            fun isExtraneousRoot(rootVal: Double): Boolean {
                if (rootVal.isNaN() || rootVal.isInfinite()) return true
                if (dl !is Node.Num) {
                    val denL = evalNodeAtX(dl, rootVal)
                    if (!denL.isNaN() && abs(denL) < 1e-7) return true
                }
                if (dr !is Node.Num) {
                    val denR = evalNodeAtX(dr, rootVal)
                    if (!denR.isNaN() && abs(denR) < 1e-7) return true
                }
                return false
            }

            val logRes = trySolveLogEquation(leftAst, rightAst, masterEq, target, detailedSteps)
            if (logRes != null) return logRes

            val radRes = trySolveRadicalEquation(leftAst, rightAst, target, detailedSteps)
            if (radRes != null) return radRes

            if (leftAst is Node.Func && leftAst.fn == "abs" && !rightAst.containsVar(target)) {
                val rVal = evalNodeAtX(rightAst, 0.0)
                if (rVal < -1e-9) {
                    detailedSteps.add(CasStep("Шаг 1", "Анализируем модуль", "|...| = ${formatReadableExpr(rightAst.toExprString())} < 0", "Модуль числа не может быть отрицательным."))
                    detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений."))
                    return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                }
                if (abs(rVal) <= 1e-9) {
                    return isolateVariableFull("${leftAst.arg.toExprString()} = 0", target)
                }
                if (leftAst.arg !is Node.Variable || (leftAst.arg as Node.Variable).name != target) {
                    return solveBranching(leftAst.arg, rightAst, target, detailedSteps, "Раскрываем модуль со знаками плюс и минус")
                }
            }

            if (leftAst is Node.Pow && leftAst.exp is Node.Num && leftAst.exp.value.den == 1L && leftAst.exp.value.num >= 2L && leftAst.exp.value.num % 2L == 0L && !rightAst.containsVar(target)) {
                val n = leftAst.exp.value.num
                val rVal = evalNodeAtX(rightAst, 0.0)
                if (rVal < -1e-9) {
                    detailedSteps.add(CasStep("Шаг 1", "Анализируем четную степень", "(${leftAst.base.toExprString()})^$n = ${rightAst.toExprString()} < 0", "Четная степень числа не может быть отрицательной."))
                    detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений."))
                    return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                }
                if (abs(rVal) <= 1e-9) {
                    return isolateVariableFull("${leftAst.base.toExprString()} = 0", target)
                }
                val fracRight = (rightAst as? Node.Num)?.value
                val exactRoot = if (fracRight != null) exactNthRoot(fracRight, n) else null
                val rootNode = if (exactRoot != null) Node.Num(exactRoot) else simplify(Node.Pow(rightAst, Node.Div(Node.Num(Fraction(1)), leftAst.exp)))
                if (leftAst.base !is Node.Variable || (leftAst.base as Node.Variable).name != target) {
                    return solveBranching(leftAst.base, rootNode, target, detailedSteps, "Извлекаем корень четной степени $n")
                }
            }

            val poly = toPolynomial(masterEq, target, maxDeg = 4)
            if (poly == null) {
                val expRes = trySolveExponentialEquation(leftAst, rightAst, target, detailedSteps)
                if (expRes != null) return expRes
            } else {
                val a4 = simplify(poly[4] ?: Node.Num(Fraction(0)))
                val a3 = simplify(poly[3] ?: Node.Num(Fraction(0)))
                val a  = simplify(poly[2] ?: Node.Num(Fraction(0)))
                val b  = simplify(poly[1] ?: Node.Num(Fraction(0)))
                val c  = simplify(poly[0] ?: Node.Num(Fraction(0)))

                // Уравнение вида 0 = const или 0 = 0 (переменная сократилась)
                if (isZero(a4) && isZero(a3) && isZero(a) && isZero(b)) {
                    if (!isZero(c)) {
                        detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет решений", "Получено противоречивое равенство 0 = const."))
                        return SolveResult("Нет решений", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                    } else {
                        val exclusions = extractExclusions(leftAst, rightAst, dl, dr, target)
                        val answerStr = if (exclusions.isNotEmpty()) {
                            "$target ∈ ℝ, ${exclusions.joinToString(", ")}"
                        } else {
                            "$target ∈ ℝ (любое число)"
                        }
                        val explanationStr = if (exclusions.isNotEmpty()) {
                            "Переменная может принимать любое действительное значение, кроме исключённых по ОДЗ: ${exclusions.joinToString(", ")}."
                        } else {
                            "Переменная может принимать любое действительное значение."
                        }
                        detailedSteps.add(CasStep("Ответ", "Записываем ответ", answerStr, explanationStr))
                        return SolveResult(answerStr, null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                    }
                }

                // --- БИКВАДРАТНОЕ УРАВНЕНИЕ (степень 4, a3 = 0, b = 0) ---
                if (!isZero(a4)) {
                    if (isZero(a3) && isZero(b)) {
                        if (a4 is Node.Num && a is Node.Num && c is Node.Num) {
                            val aD = a4.value.toDouble()
                            val bD = a.value.toDouble()
                            val cD = c.value.toDouble()

                            val D_t = bD * bD - 4.0 * aD * cD
                            val a4Str = a4.toExprString()
                            val aStr = a.toExprString()
                            val cStr = c.toExprString()

                            val biquadStepFormula = "Пусть t = $target² ≥ 0 ⇒ $a4Str·t² + ($aStr)·t + ($cStr) = 0\nD = b² − 4ac = $D_t"
                            detailedSteps.add(
                                CasStep(
                                    badge = "Шаг 2",
                                    title = "Замена переменной и вычисление дискриминанта",
                                    formula = biquadStepFormula,
                                    explanation = "Выполняем замену t = $target² (где t ≥ 0) и решаем квадратное уравнение относительно t."
                                )
                            )

                            if (D_t < -1e-9) {
                                detailedSteps.add(CasStep("Шаг 3", "Анализируем дискриминант", "D = $D_t < 0  ⇒  корней нет", "Дискриминант отрицателен, уравнение не имеет решений."))
                                detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений на множестве действительных чисел."))
                                return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                            }

                            val sqrtDt = sqrt(max(0.0, D_t))
                            val t1 = (-bD - sqrtDt) / (2.0 * aD)
                            val t2 = (-bD + sqrtDt) / (2.0 * aD)

                            val validTs = listOf(t1, t2).filter { it >= -1e-9 }.map { if (abs(it) < 1e-9) 0.0 else it }.distinct()
                            if (validTs.isEmpty()) {
                                detailedSteps.add(CasStep("Шаг 3", "Анализируем корни для t", "t₁ < 0, t₂ < 0  ⇒  корней нет", "Все корни t отрицательны, но t = $target² должно быть неотрицательным."))
                                detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений."))
                                return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                            }

                            val candRoots = mutableListOf<Double>()
                            for (tVal in validTs) {
                                if (tVal == 0.0) {
                                    candRoots.add(0.0)
                                } else {
                                    val sqt = sqrt(tVal)
                                    candRoots.add(-sqt)
                                    candRoots.add(sqt)
                                }
                            }

                            val sortedCand = candRoots.distinct().sorted()
                            val validRoots = sortedCand.filter { rVal ->
                                val ext = isExtraneousRoot(rVal)
                                if (ext) {
                                    detailedSteps.add(CasStep("ОДЗ", "Проверка корня по ОДЗ", "$target = ${formatRoot(rVal)} — посторонний корень по ОДЗ", "При данном значении знаменатель обращается в ноль."))
                                }
                                !ext
                            }

                            if (validRoots.isEmpty()) {
                                detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет решений", "Все найденные корни являются посторонними по ОДЗ."))
                                return SolveResult("Нет решений", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                            }

                            val subs = listOf("₁", "₂", "₃", "₄")
                            val parts = validRoots.mapIndexed { idx, v ->
                                "$target${subs.getOrElse(idx) { "${idx + 1}" }} = ${formatRoot(v)}"
                            }
                            val displayStr = parts.joinToString(";  ")
                            val answerFormula = validRoots.mapIndexed { idx, v ->
                                "$target${subs.getOrElse(idx) { "${idx + 1}" }} = ${formatRoot(v)}"
                            }.joinToString("\n")

                            detailedSteps.add(
                                CasStep(
                                    badge = "Шаг 3",
                                    title = "Обратная замена t = $target² и нахождение корней",
                                    formula = displayStr,
                                    explanation = "Находим решения биквадратного уравнения."
                                )
                            )
                            detailedSteps.add(CasStep("Ответ", "Записываем ответ", answerFormula, "Записываем корни биквадратного уравнения."))
                            return SolveResult(displayStr, null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                        }
                    }
                    return SolveResult("Не удалось выразить '$target' (уравнение 4-й степени)", null)
                }

                // --- КУБИЧЕСКОЕ УРАВНЕНИЕ (степень 3) ---
                if (!isZero(a3)) {
                    if (a3 is Node.Num && a is Node.Num && b is Node.Num && c is Node.Num) {
                        val aD = a3.value.toDouble()
                        val bD = a.value.toDouble()
                        val cD = b.value.toDouble()
                        val dD = c.value.toDouble()
                        val rawRoots = solveCubicNumerical(aD, bD, cD, dD).sorted()
                        val validRoots = rawRoots.filter { rVal ->
                            val ext = isExtraneousRoot(rVal)
                            if (ext) {
                                detailedSteps.add(CasStep("ОДЗ", "Проверка корня по ОДЗ", "$target = ${formatRoot(rVal)} — посторонний корень по ОДЗ", "При данном значении знаменатель равен нулю."))
                            }
                            !ext
                        }
                        if (validRoots.isEmpty()) {
                            detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет решений", "Все найденные корни являются посторонними по ОДЗ или уравнение не имеет действительных корней."))
                            return SolveResult("Нет решений", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                        }
                        val r1Str = formatRoot(validRoots.first())
                        detailedSteps.add(CasStep("Шаг 2", "Находим первый корень уравнения", "${target}₁ = $r1Str", "Находим рациональный корень методом подбора."))
                        val subs = listOf("₁", "₂", "₃")
                        val parts2 = validRoots.mapIndexed { idx, v ->
                            "$target${subs.getOrElse(idx) { "${idx + 1}" }} = ${formatRoot(v)}"
                        }
                        val display = parts2.joinToString(";  ")
                        detailedSteps.add(CasStep("Шаг 3", "Раскладываем на множители и находим остальные корни", display, "Делим многочлен на ($target − $r1Str) и решаем полученное квадратное уравнение."))
                        detailedSteps.add(CasStep("Ответ", "Записываем ответ", display, "Записываем найденные корни кубического уравнения."))
                        return SolveResult(display, null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                    }
                    return SolveResult("Не удалось выразить '$target' (кубическое с переменными коэффициентами)", null)
                }

                // --- ЛИНЕЙНОЕ УРАВНЕНИЕ (ax + b = 0) ---
                if (isZero(a) && !isZero(b)) {
                    val sol = simplify(Node.Div(negateNode(c), b))
                    if (!sol.containsVar(target)) {
                        val solVal = evalNodeAtX(sol, 0.0)
                        if (isExtraneousRoot(solVal)) {
                            detailedSteps.add(CasStep("ОДЗ", "Проверка корня по ОДЗ", "$target = ${sol.toExprString()} — посторонний корень по ОДЗ", "При данном значении $target знаменатель обращается в ноль."))
                            detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет решений", "Единственный найденный корень является посторонним по ОДЗ."))
                            return SolveResult("Нет решений", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                        }
                        val frac = (sol as? Node.Num)?.value
                        val solStr = sol.toExprString()
                        val bStr = b.toExprString()
                        val negCStr = negateNode(c).toExprString()
                        detailedSteps.add(CasStep("Шаг 2", "Решаем линейное уравнение", "$bStr·$target = $negCStr  ⇒  $target = $solStr", "Изолируем слагаемое с переменной $target и делим на коэффициент при ней."))
                        detailedSteps.add(CasStep("Ответ", "Записываем ответ", "$target = $solStr", "Найденный корень линейного уравнения."))
                        return SolveResult("$target = $solStr", sol, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, frac, detailedSteps = detailedSteps)
                    }
                }

                // --- КВАДРАТНОЕ УРАВНЕНИЕ (b=0, x²=const) ---
                if (!isZero(a) && isZero(b)) {
                    val rhs = simplify(Node.Div(negateNode(c), a))
                    if (!rhs.containsVar(target)) {
                        if (rhs is Node.Num) {
                            if (rhs.value.num < 0) {
                                detailedSteps.add(CasStep("Шаг 2", "Решаем неполное квадратное уравнение", "$target² = ${rhs.toExprString()} < 0", "Квадрат числа не может быть отрицательным на множестве действительных чисел."))
                                detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений."))
                                return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                            }
                            if (rhs.value.num == 0L) {
                                if (isExtraneousRoot(0.0)) {
                                    detailedSteps.add(CasStep("ОДЗ", "Проверка корня по ОДЗ", "$target = 0 — посторонний корень по ОДЗ", "При $target = 0 знаменатель обращается в ноль."))
                                    detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет решений", "Корень равен 0, но он посторонний по ОДЗ."))
                                    return SolveResult("Нет решений", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                                }
                                detailedSteps.add(CasStep("Шаг 2", "Решаем неполное квадратное уравнение", "$target² = 0  ⇒  $target = 0", "Единственный корень $target = 0."))
                                detailedSteps.add(CasStep("Ответ", "Записываем ответ", "$target = 0", "Записываем найденный корень."))
                                return SolveResult("$target = 0", Node.Num(Fraction(0)), detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, Fraction(0), detailedSteps = detailedSteps)
                            }
                            val rootNode = simplify(Node.Sqrt(rhs))
                            val rootStr = rootNode.toExprString()

                            val r1Val = evalNodeAtX(negateNode(rootNode), 0.0)
                            val r2Val = evalNodeAtX(rootNode, 0.0)

                            val ext1 = isExtraneousRoot(r1Val)
                            val ext2 = isExtraneousRoot(r2Val)

                            if (ext1) detailedSteps.add(CasStep("ОДЗ", "Проверка корня по ОДЗ", "$target = -$rootStr — посторонний корень по ОДЗ", "При данном значении знаменатель равен нулю."))
                            if (ext2) detailedSteps.add(CasStep("ОДЗ", "Проверка корня по ОДЗ", "$target = $rootStr — посторонний корень по ОДЗ", "При данном значении знаменатель равен нулю."))

                            if (ext1 && ext2) {
                                detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет решений", "Все найденные корни являются посторонними по ОДЗ."))
                                return SolveResult("Нет решений", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                            } else if (ext1) {
                                detailedSteps.add(CasStep("Ответ", "Записываем ответ", "$target = $rootStr", "Записываем единственный допустимый корень."))
                                return SolveResult("$target = $rootStr", rootNode, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, (rootNode as? Node.Num)?.value, detailedSteps = detailedSteps)
                            } else if (ext2) {
                                val negRoot = simplify(negateNode(rootNode))
                                detailedSteps.add(CasStep("Ответ", "Записываем ответ", "$target = -$rootStr", "Записываем единственный допустимый корень."))
                                return SolveResult("$target = -$rootStr", negRoot, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, (negRoot as? Node.Num)?.value, detailedSteps = detailedSteps)
                            }

                            val displayStr = "${target}₁ = -$rootStr;  ${target}₂ = $rootStr"
                            detailedSteps.add(CasStep("Шаг 2", "Решаем неполное квадратное уравнение", "$target² = ${rhs.toExprString()}  ⇒  $target = ±$rootStr", "Изолируем $target² и извлекаем квадратный корень."))
                            val answerFormula = "${target}₁ = -$rootStr\n${target}₂ = $rootStr"
                            detailedSteps.add(CasStep("Ответ", "Записываем ответ", answerFormula, "Записываем корни неполного квадратного уравнения."))
                            return SolveResult(displayStr, rootNode, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, (rootNode as? Node.Num)?.value, detailedSteps = detailedSteps)
                        } else {
                            val rootNode = simplify(Node.Sqrt(rhs))
                            val displayStr = "$target = ±${rootNode.toExprString()}"
                            detailedSteps.add(CasStep("Шаг 2", "Решаем неполное квадратное уравнение", displayStr, "Изолируем $target² и извлекаем квадратный корень."))
                            detailedSteps.add(CasStep("Ответ", "Записываем ответ", displayStr, "Записываем корни уравнения."))
                            return SolveResult(displayStr, rootNode, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                        }
                    }
                }

                // --- КВАДРАТНОЕ УРАВНЕНИЕ (общий случай) ---
                if (!isZero(a) && !isZero(b)) {
                    val disc = simplify(Node.Sub(Node.Pow(b, Node.Num(Fraction(2))), Node.Mul(Node.Num(Fraction(4)), Node.Mul(a, c))))
                    if (a is Node.Num && b is Node.Num && disc is Node.Num) {
                        val aVal = a.value
                        val bVal = b.value
                        val cVal = (c as? Node.Num)?.value ?: Fraction(0)
                        val dVal = disc.value

                        val twoA = Node.Num(Fraction(2) * aVal)
                        val negB = Node.Num(bVal.negate())
                        val sqrtD = simplify(Node.Sqrt(disc))
                        val sqrtDStr = sqrtD.toExprString()
                        val dValStr = dVal.toString()

                        val bStr = if (bVal.num < 0) "(${bVal})" else "$bVal"
                        val aStr = if (aVal.num < 0) "(${aVal})" else "$aVal"
                        val cStr = if (cVal.num < 0) "(${cVal})" else "$cVal"

                        var step2Formula = "D = b² − 4ac = ${bStr}² − 4·${aStr}·${cStr} = $dValStr"
                        val rawSqrtD = "√$dValStr"
                        if (sqrtDStr != rawSqrtD) {
                            step2Formula += "\n√D = $rawSqrtD = $sqrtDStr"
                        }

                        detailedSteps.add(
                            CasStep(
                                badge = "Шаг 2",
                                title = "Вычисляем дискриминант",
                                formula = step2Formula,
                                explanation = "Находим дискриминант квадратного уравнения по формуле D = b² − 4ac."
                            )
                        )

                        if (dVal.num < 0) {
                            detailedSteps.add(CasStep("Шаг 3", "Анализируем дискриминант", "D = $dValStr < 0  ⇒  корней нет", "Так как D < 0, квадратное уравнение не имеет действительных корней."))
                            detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет корней (D < 0)", "Действительных корней нет."))
                            return SolveResult("Нет корней (D < 0)", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                        }

                        if (dVal.num == 0L) {
                            val x0 = simplify(Node.Div(negB, twoA))
                            val x0Val = evalNodeAtX(x0, 0.0)
                            if (isExtraneousRoot(x0Val)) {
                                detailedSteps.add(CasStep("ОДЗ", "Проверка корня по ОДЗ", "$target = ${x0.toExprString()} — посторонний корень по ОДЗ", "При данном значении знаменатель равен нулю."))
                                detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет решений", "Единственный корень является посторонним по ОДЗ."))
                                return SolveResult("Нет решений", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                            }
                            val x0Str = x0.toExprString()
                            detailedSteps.add(CasStep("Шаг 3", "Находим единственный корень", "$target = -b / 2a = $x0Str", "Так как D = 0, уравнение имеет единственный корень (кратности 2)."))
                            detailedSteps.add(CasStep("Ответ", "Записываем ответ", "$target = $x0Str", "Записываем единственный корень уравнения."))
                            return SolveResult("$target = $x0Str", x0, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, (x0 as? Node.Num)?.value, detailedSteps = detailedSteps)
                        }

                        val x1 = simplify(Node.Div(Node.Sub(negB, sqrtD), twoA))
                        val x2 = simplify(Node.Div(Node.Add(negB, sqrtD), twoA))
                        val x1Val = evalNodeAtX(x1, 0.0)
                        val x2Val = evalNodeAtX(x2, 0.0)

                        val ext1 = isExtraneousRoot(x1Val)
                        val ext2 = isExtraneousRoot(x2Val)

                        if (ext1) detailedSteps.add(CasStep("ОДЗ", "Проверка корня по ОДЗ", "$target = ${x1.toExprString()} — посторонний корень по ОДЗ", "При данном значении знаменатель равен нулю."))
                        if (ext2) detailedSteps.add(CasStep("ОДЗ", "Проверка корня по ОДЗ", "$target = ${x2.toExprString()} — посторонний корень по ОДЗ", "При данном значении знаменатель равен нулю."))

                        if (ext1 && ext2) {
                            detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет решений", "Все найденные корни являются посторонними по ОДЗ."))
                            return SolveResult("Нет решений", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                        } else if (ext1) {
                            val x2Str = x2.toExprString()
                            detailedSteps.add(CasStep("Ответ", "Записываем ответ", "$target = $x2Str", "Записываем единственный допустимый корень."))
                            return SolveResult("$target = $x2Str", x2, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, (x2 as? Node.Num)?.value, detailedSteps = detailedSteps)
                        } else if (ext2) {
                            val x1Str = x1.toExprString()
                            detailedSteps.add(CasStep("Ответ", "Записываем ответ", "$target = $x1Str", "Записываем единственный допустимый корень."))
                            return SolveResult("$target = $x1Str", x1, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, (x1 as? Node.Num)?.value, detailedSteps = detailedSteps)
                        }

                        val x1Str = x1.toExprString()
                        val x2Str = x2.toExprString()
                        val displayStr = "${target}₁ = $x1Str;  ${target}₂ = $x2Str"

                        val negBStr = negB.toExprString()
                        val twoAStr = twoA.toExprString()
                        val unreducedX1 = "($negBStr - $sqrtDStr) / $twoAStr"
                        val unreducedX2 = "($negBStr + $sqrtDStr) / $twoAStr"

                        val x1Line = if (unreducedX1 != x1Str) "${target}₁ = $unreducedX1 = $x1Str" else "${target}₁ = $x1Str"
                        val x2Line = if (unreducedX2 != x2Str) "${target}₂ = $unreducedX2 = $x2Str" else "${target}₂ = $x2Str"
                        val step3Formula = "${target}₁,₂ = (-b ± √D) / 2a\n$x1Line\n$x2Line"

                        detailedSteps.add(
                            CasStep(
                                badge = "Шаг 3",
                                title = "Находим корни уравнения",
                                formula = step3Formula,
                                explanation = "Так как D > 0, вычисляем два корня по формуле корней квадратного уравнения."
                            )
                        )

                        val answerFormula = "${target}₁ = $x1Str\n${target}₂ = $x2Str"
                        detailedSteps.add(CasStep("Ответ", "Записываем ответ", answerFormula, "Записываем корни квадратного уравнения."))
                        return SolveResult(displayStr, x2, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, (x2 as? Node.Num)?.value, detailedSteps = detailedSteps)
                    } else {
                        val twoA = simplify(Node.Mul(Node.Num(Fraction(2)), a))
                        val negB = simplify(negateNode(b))
                        val sqrtD = simplify(Node.Sqrt(disc))
                        val x1 = simplify(Node.Div(Node.Sub(negB, sqrtD), twoA))
                        val x2 = simplify(Node.Div(Node.Add(negB, sqrtD), twoA))
                        val x1Str = x1.toExprString()
                        val x2Str = x2.toExprString()
                        val displayStr = "${target}₁ = $x1Str;  ${target}₂ = $x2Str"
                        if (!x1.containsVar(target) && !x2.containsVar(target)) {
                            detailedSteps.add(CasStep("Шаг 2", "Находим корни с символьными коэффициентами", displayStr, "Выражаем корни через коэффициенты уравнения."))
                            detailedSteps.add(CasStep("Ответ", "Записываем ответ", displayStr, "Корни уравнения."))
                            return SolveResult(displayStr, x2, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                        }
                    }
                }
            }

            // Фоллбэк: пошаговое упрощение
            var left = leftAst
            var right = rightAst
            if (!left.containsVar(target) && right.containsVar(target)) {
                val tmp = left; left = right; right = tmp
            }

            var hasPlusMinus = false
            var needRootCheck = false
            val odzCheckNodes = mutableListOf<Node>()
            var guard = 0
            while (left !is Node.Variable && guard < 30) {
                guard++
                val stepNum = detailedSteps.count { it.badge.startsWith("Шаг") } + 1
                when (val cur = left) {
                    is Node.Add -> {
                        if (cur.l.containsVar(target)) { right = Node.Sub(right, cur.r); left = cur.l }
                        else { right = Node.Sub(right, cur.l); left = cur.r }
                        right = simplify(right)
                        left = simplify(left)
                        detailedSteps.add(CasStep("Шаг $stepNum", "Переносим слагаемое", "${left.toExprString()} = ${right.toExprString()}", "Переносим свободное слагаемое в правую часть уравнения с противоположным знаком."))
                    }
                    is Node.Sub -> {
                        if (cur.l.containsVar(target)) { right = Node.Add(right, cur.r); left = cur.l }
                        else { right = Node.Sub(cur.l, right); left = cur.r }
                        right = simplify(right)
                        left = simplify(left)
                        detailedSteps.add(CasStep("Шаг $stepNum", "Переносим слагаемое", "${left.toExprString()} = ${right.toExprString()}", "Переносим слагаемое в правую часть уравнения."))
                    }
                    is Node.Mul -> {
                        if (cur.l.containsVar(target)) { right = Node.Div(right, cur.r); left = cur.l }
                        else { right = Node.Div(right, cur.l); left = cur.r }
                        right = simplify(right)
                        left = simplify(left)
                        detailedSteps.add(CasStep("Шаг $stepNum", "Делим на множитель", "${left.toExprString()} = ${right.toExprString()}", "Делим обе части уравнения на множитель."))
                    }
                    is Node.Div -> {
                        if (cur.l.containsVar(target)) { right = Node.Mul(right, cur.r); left = cur.l }
                        else { right = Node.Div(cur.l, right); left = cur.r }
                        right = simplify(right)
                        left = simplify(left)
                        detailedSteps.add(CasStep("Шаг $stepNum", "Умножаем на знаменатель", "${left.toExprString()} = ${right.toExprString()}", "Умножаем обе части уравнения на знаменатель."))
                    }
                    is Node.Pow -> {
                        if (cur.exp.containsVar(target) && !cur.base.containsVar(target)) {
                            val rVal = evalNodeAtX(right, 0.0)
                            if (!right.containsVar(target) && rVal <= 1e-9) {
                                detailedSteps.add(CasStep("Шаг $stepNum", "Анализируем показательную функцию", "${cur.toExprString()} = ${formatReadableExpr(right.toExprString())} ≤ 0", "Показательная функция принимает только положительные значения."))
                                detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений на множестве действительных чисел."))
                                return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                            }
                            if (cur.base == Node.Variable("e") || (cur.base is Node.Variable && (cur.base as Node.Variable).name.equals("e", ignoreCase = true))) {
                                left = cur.exp
                                right = simplify(Node.Func("ln", right))
                                detailedSteps.add(CasStep("Шаг $stepNum", "Логарифмируем показательное уравнение", "${left.toExprString()} = ${right.toExprString()}", "Берем натуральный логарифм от обеих частей уравнения."))
                            } else {
                                left = cur.exp
                                right = simplify(Node.Div(Node.Func("ln", right), Node.Func("ln", cur.base)))
                                detailedSteps.add(CasStep("Шаг $stepNum", "Логарифмируем показательное уравнение", "${left.toExprString()} = ${right.toExprString()}", "Логарифмируем обе части уравнения."))
                            }
                        } else if (cur.base.containsVar(target)) {
                            val exp = cur.exp
                            if (exp is Node.Num && exp.value.den == 1L && exp.value.num >= 2L) {
                                val n = exp.value.num
                                val rVal = evalNodeAtX(right, 0.0)
                                if (!right.containsVar(target)) {
                                    if (n % 2L == 0L) {
                                        if (rVal < -1e-9) {
                                            detailedSteps.add(CasStep("Шаг $stepNum", "Анализируем четную степень", "(${cur.base.toExprString()})^$n = ${right.toExprString()} < 0", "Четная степень числа не может быть отрицательной."))
                                            detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений."))
                                            return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                                        } else if (abs(rVal) < 1e-9) {
                                            right = Node.Num(Fraction(0))
                                            left = cur.base
                                        } else {
                                            val fracRight = (right as? Node.Num)?.value
                                            val exactRoot = if (fracRight != null) exactNthRoot(fracRight, n) else null
                                            val rootNode = if (exactRoot != null) Node.Num(exactRoot) else simplify(Node.Pow(right, Node.Div(Node.Num(Fraction(1)), exp)))
                                            if (cur.base !is Node.Variable || (cur.base as Node.Variable).name != target) {
                                                return solveBranching(cur.base, rootNode, target, detailedSteps, "Извлекаем корень четной степени $n")
                                            }
                                            hasPlusMinus = true
                                            right = rootNode
                                            left = cur.base
                                        }
                                    } else {
                                        // Odd exponent
                                        if (rVal < 0) {
                                            val posRight = simplify(negateNode(right))
                                            val fracPos = (posRight as? Node.Num)?.value
                                            val exactPos = if (fracPos != null) exactNthRoot(fracPos, n) else null
                                            if (exactPos != null) {
                                                right = Node.Num(Fraction(-exactPos.num, exactPos.den))
                                            } else {
                                                val rAbs = abs(rVal)
                                                val d = -rAbs.pow(1.0 / n)
                                                if (abs(d - round(d)) < 1e-9) {
                                                    right = Node.Num(Fraction(round(d).toLong()))
                                                } else {
                                                    right = Node.Num(Fraction.fromDouble(d))
                                                }
                                            }
                                        } else {
                                            val fracRight = (right as? Node.Num)?.value
                                            val exactRoot = if (fracRight != null) exactNthRoot(fracRight, n) else null
                                            if (exactRoot != null) {
                                                right = Node.Num(exactRoot)
                                            } else {
                                                right = simplify(Node.Pow(right, Node.Div(Node.Num(Fraction(1)), exp)))
                                            }
                                        }
                                        left = cur.base
                                    }
                                } else {
                                    right = Node.Pow(right, Node.Div(Node.Num(Fraction(1)), exp))
                                    left = cur.base
                                }
                            } else {
                                right = Node.Pow(right, Node.Div(Node.Num(Fraction(1)), exp))
                                left = cur.base
                            }
                            right = simplify(right)
                            left = simplify(left)
                            detailedSteps.add(CasStep("Шаг $stepNum", "Извлекаем корень степени ${exp.toExprString()}", "${left.toExprString()} = ${right.toExprString()}", "Извлекаем корень из обеих частей уравнения."))
                        } else break
                    }
                    is Node.Sqrt -> {
                        val rVal = evalNodeAtX(right, 0.0)
                        if (!right.containsVar(target) && rVal < -1e-9) {
                            detailedSteps.add(CasStep("Шаг $stepNum", "Анализируем квадратный корень", "√(...) = ${formatReadableExpr(right.toExprString())} < 0", "Арифметический квадратный корень не может быть отрицательным."))
                            detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений на множестве действительных чисел."))
                            return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                        }
                        right = simplify(Node.Pow(right, Node.Num(Fraction(2))))
                        left = cur.inner
                        needRootCheck = true
                        detailedSteps.add(CasStep("Шаг $stepNum", "Возводим в квадрат", "${left.toExprString()} = ${right.toExprString()}", "Возводим обе части уравнения в квадрат для избавления от знака корня."))
                    }
                    is Node.Func -> {
                        when (cur.fn) {
                            "sqrt" -> {
                                val rVal = evalNodeAtX(right, 0.0)
                                if (!right.containsVar(target) && rVal < -1e-9) {
                                    detailedSteps.add(CasStep("Шаг $stepNum", "Анализируем квадратный корень", "√(...) = ${formatReadableExpr(right.toExprString())} < 0", "Арифметический квадратный корень не может быть отрицательным."))
                                    detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений на множестве действительных чисел."))
                                    return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                                }
                                right = simplify(Node.Pow(right, Node.Num(Fraction(2))))
                                left = cur.arg
                                needRootCheck = true
                                detailedSteps.add(CasStep("Шаг $stepNum", "Возводим в квадрат", "${left.toExprString()} = ${right.toExprString()}", "Возводим обе части уравнения в квадрат для избавления от знака корня."))
                            }
                            "cbrt" -> {
                                right = simplify(Node.Pow(right, Node.Num(Fraction(3))))
                                left = cur.arg
                                detailedSteps.add(CasStep("Шаг $stepNum", "Возводим в куб", "${left.toExprString()} = ${right.toExprString()}", "Возводим обе части уравнения в куб."))
                            }
                            "abs" -> {
                                val rVal = evalNodeAtX(right, 0.0)
                                if (!right.containsVar(target) && rVal < -1e-9) {
                                    detailedSteps.add(CasStep("Шаг $stepNum", "Анализируем модуль", "|...| = ${formatReadableExpr(right.toExprString())} < 0", "Модуль числа не может быть отрицательным."))
                                    detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений."))
                                    return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                                }
                                if (!right.containsVar(target) && abs(rVal) <= 1e-9) {
                                    left = cur.arg
                                    right = Node.Num(Fraction(0))
                                    detailedSteps.add(CasStep("Шаг $stepNum", "Раскрываем модуль", "${left.toExprString()} = 0", "Модуль равен нулю, если подмодульное выражение равно нулю."))
                                } else {
                                    if (cur.arg !is Node.Variable || (cur.arg as Node.Variable).name != target) {
                                        return solveBranching(cur.arg, right, target, detailedSteps, "Раскрываем модуль со знаками плюс и минус")
                                    }
                                    hasPlusMinus = true
                                    left = cur.arg
                                    detailedSteps.add(CasStep("Шаг $stepNum", "Раскрываем модуль", "${left.toExprString()} = ±${right.toExprString()}", "Раскрываем модуль со знаками плюс и минус."))
                                }
                            }
                            "ln" -> {
                                left = cur.arg
                                right = simplify(Node.Pow(Node.Variable("e"), right))
                                odzCheckNodes.add(cur.arg)
                                detailedSteps.add(CasStep("Шаг $stepNum", "Потенцируем логарифмическое уравнение", "${left.toExprString()} = ${right.toExprString()}", "Используем определение натурального логарифма: ln(a) = b  ⇒  a = e^b."))
                            }
                            "log" -> {
                                left = cur.arg
                                right = simplify(Node.Pow(Node.Num(Fraction(10)), right))
                                odzCheckNodes.add(cur.arg)
                                detailedSteps.add(CasStep("Шаг $stepNum", "Потенцируем логарифмическое уравнение", "${left.toExprString()} = ${right.toExprString()}", "Используем определение десятичного логарифма: log(a) = b  ⇒  a = 10^b."))
                            }
                            else -> break
                        }
                    }
                    else -> break
                }
                right = simplify(right)
                left = simplify(left)
            }

            var cleanRight = simplify(right)

            if (cleanRight.containsVar(target)) {
                val remEq = simplify(Node.Sub(left, cleanRight))
                val remPoly = toPolynomial(remEq, target, maxDeg = 4)
                if (remPoly != null) {
                    val ra = simplify(remPoly[2] ?: Node.Num(Fraction(0)))
                    val rb = simplify(remPoly[1] ?: Node.Num(Fraction(0)))
                    val rc = simplify(remPoly[0] ?: Node.Num(Fraction(0)))

                    if (isZero(ra) && isZero(rb)) {
                        return if (!isZero(rc)) {
                            SolveResult("Нет решений", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                        } else {
                            val exclusions = extractExclusions(leftAst, rightAst, dl, dr, target)
                            val answerStr = if (exclusions.isNotEmpty()) "$target ∈ ℝ, ${exclusions.joinToString(", ")}" else "$target ∈ ℝ (любое число)"
                            SolveResult(answerStr, null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                        }
                    } else if (!isZero(ra)) {
                        if (ra is Node.Num && rb is Node.Num && rc is Node.Num) {
                            val aD = ra.value.toDouble()
                            val bD = rb.value.toDouble()
                            val cD = rc.value.toDouble()
                            val D = bD * bD - 4.0 * aD * cD
                            if (D >= 0) {
                                val sqrtD = sqrt(max(0.0, D))
                                val r1 = (-bD - sqrtD) / (2.0 * aD)
                                val r2 = (-bD + sqrtD) / (2.0 * aD)
                                val candRoots = listOf(r1, r2).distinct()
                                val validRoots = candRoots.filter { rVal ->
                                    !isExtraneousRoot(rVal) &&
                                    abs(evalNodeAtX(leftAst, rVal) - evalNodeAtX(rightAst, rVal)) < 1e-5
                                }.sorted()
                                if (validRoots.isNotEmpty()) {
                                    val displayStr = formatRootsDisplay(validRoots, target)
                                    val answerFormula = formatRootsAnswer(validRoots, target)
                                    detailedSteps.add(CasStep("Ответ", "Записываем ответ", answerFormula, "Записываем корни уравнения."))
                                    return SolveResult(displayStr, null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                                }
                            }
                        }
                    } else if (isZero(ra) && !isZero(rb)) {
                        cleanRight = simplify(Node.Div(negateNode(rc), rb))
                    }
                }
            }

            // Обязательная проверка: выразилась ли переменная
            if (left !is Node.Variable || (left as Node.Variable).name != target || cleanRight.containsVar(target)) {
                return SolveResult("Не удалось выразить '$target'", null)
            }

            val candVal = evalNodeAtX(cleanRight, 0.0)

            if (needRootCheck && !candVal.isNaN()) {
                val lhsVal = evalNodeAtX(leftAst, candVal)
                val rhsVal = evalNodeAtX(rightAst, candVal)
                if (lhsVal.isNaN() || rhsVal.isNaN() || abs(lhsVal - rhsVal) > 1e-4) {
                    detailedSteps.add(CasStep("ОДЗ", "Проверка корня", "$target = ${cleanRight.toExprString()} — посторонний корень", "При данной подстановке исходное уравнение не выполняется."))
                    detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений."))
                    return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                }
            }

            for (argNode in odzCheckNodes) {
                if (!candVal.isNaN()) {
                    val argVal = evalNodeAtX(argNode, candVal)
                    if (argVal.isNaN() || argVal <= 1e-9) {
                        detailedSteps.add(CasStep("ОДЗ", "Проверка ОДЗ логарифма", "$target = ${cleanRight.toExprString()} — посторонний корень", "Подлогарифмическое выражение должно быть строго больше нуля."))
                        detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет действительных корней", "Уравнение не имеет решений."))
                        return SolveResult("Нет действительных корней", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
                    }
                }
            }

            if (!candVal.isNaN() && isExtraneousRoot(candVal)) {
                detailedSteps.add(CasStep("ОДЗ", "Проверка ОДЗ знаменателя", "$target = ${cleanRight.toExprString()} — посторонний корень по ОДЗ", "При данном значении знаменатель равен нулю."))
                detailedSteps.add(CasStep("Ответ", "Записываем ответ", "Нет решений", "Найденный корень является посторонним по ОДЗ."))
                return SolveResult("Нет решений", null, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
            }

            val rightStr = cleanRight.toExprString()

            if (hasPlusMinus) {
                val displayStr = "${target}₁ = -$rightStr;  ${target}₂ = $rightStr"
                val answerFormula = "${target}₁ = -$rightStr\n${target}₂ = $rightStr"
                detailedSteps.add(CasStep("Ответ", "Записываем ответ", answerFormula, "Записываем ответ с обоими знаками."))
                return SolveResult(displayStr, cleanRight, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, detailedSteps = detailedSteps)
            }

            detailedSteps.add(CasStep("Ответ", "Записываем ответ", "$target = $rightStr", "Записываем итоговый результат."))
            return SolveResult("$target = $rightStr", cleanRight, detailedSteps.map { "${it.badge}: ${it.title} → ${it.formula}" }, (cleanRight as? Node.Num)?.value, detailedSteps = detailedSteps)
        } catch (e: Exception) {
            SolveResult("Ошибка: ${e.message ?: "Не удалось решить уравнение"}", null)
        }
    }

    fun toMixedFraction(f: Fraction): Triple<Long, Long, Long>? {
        val s = f.simplify()
        if (s.den == 1L) return null
        val whole = s.num / s.den
        val rem = abs(s.num % s.den)
        return if (whole != 0L && rem != 0L) Triple(whole, rem, s.den) else null
    }

    /**
     * Парсер значений ячеек матрицы или коэффициентов:
     * поддерживает пустые строки (0), целые числа (-3), десятичные дроби (0.5, -1.25),
     * обыкновенные дроби (1/3, -5/2) и простые выражения.
     */
    fun parseCellToFraction(raw: String): Fraction {
        val s = raw.trim().replace(" ", "").replace("−", "-").replace(',', '.')
        if (s.isEmpty()) return Fraction(0L)

        // Обыкновенная дробь со слэшем: a/b (например, "1/3", "-5/2")
        val slashParts = s.split('/')
        if (slashParts.size == 2) {
            val numPart = slashParts[0].trim().toLongOrNull()
            val denPart = slashParts[1].trim().toLongOrNull()
            if (numPart != null && denPart != null && denPart != 0L) {
                return Fraction(numPart, denPart).simplify()
            }
        }

        // Целое число
        val longVal = s.toLongOrNull()
        if (longVal != null) {
            return Fraction(longVal, 1L)
        }

        // Десятичная дробь (например, "0.5", "-1.25")
        val singleNumberRegex = Regex("""^[+-]?((\d+(\.\d*)?)|(\.\d+))$""")
        if (s.matches(singleNumberRegex)) {
            return Fraction.fromDecimalString(s)
        }

        // Простое выражение через Parser и simplify
        return try {
            val ast = Parser(s, lastAnswer).parse()
            val simplified = simplify(ast)
            if (simplified is Node.Num) {
                simplified.value
            } else {
                val evalRes = evaluateFull(s)
                evalRes.exactFraction ?: Fraction.fromDouble(s.toDoubleOrNull() ?: 0.0)
            }
        } catch (_: Exception) {
            val d = s.toDoubleOrNull() ?: 0.0
            Fraction.fromDouble(d)
        }
    }

    /**
     * Метод Гаусса на точных рациональных дробях (Fraction).
     * rows: расширенная матрица (2x3 для 2x2 или 3x4 для 3x3).
     */
    fun solveLinearSystem(rows: List<List<String>>): LinearSystemResult {
        return try {
            val n = rows.size
            if (n !in 2..3) {
                return LinearSystemResult.Error("Поддерживаются только системы 2×2 и 3×3")
            }
            for (r in rows) {
                if (r.size != n + 1) {
                    return LinearSystemResult.Error("Каждая строка должна содержать ${n + 1} элементов")
                }
            }

            val m = Array(n) { i ->
                Array(n + 1) { j ->
                    parseCellToFraction(rows[i][j])
                }
            }

            var leadRow = 0
            for (col in 0 until n) {
                // Выбор ведущего элемента (partial pivoting)
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

                // Нормализация строки leadRow
                val pivot = m[leadRow][col]
                for (j in col until n + 1) {
                    m[leadRow][j] = m[leadRow][j] / pivot
                }

                // Исключение переменной col из всех остальных строк
                for (r in 0 until n) {
                    if (r != leadRow) {
                        val factor = m[r][col]
                        if (factor.num != 0L) {
                            for (j in col until n + 1) {
                                m[r][j] = m[r][j] - (factor * m[leadRow][j])
                            }
                        }
                    }
                }

                leadRow++
            }

            // 1. Проверка на несовместность: строка 0*x + 0*y + ... = C (C != 0)
            for (i in 0 until n) {
                val allCoeffsZero = (0 until n).all { j -> m[i][j].num == 0L }
                if (allCoeffsZero && m[i][n].num != 0L) {
                    return LinearSystemResult.NoSolution()
                }
            }

            // 2. Проверка на бесконечно много решений: ранг матрицы < n
            if (leadRow < n) {
                return LinearSystemResult.InfiniteSolutions()
            }
            for (i in 0 until n) {
                val allZero = (0..n).all { j -> m[i][j].num == 0L }
                if (allZero) {
                    return LinearSystemResult.InfiniteSolutions()
                }
            }

            // 3. Единственное решение
            val varNames = if (n == 2) listOf("x", "y") else listOf("x", "y", "z")
            val values = mutableListOf<Fraction>()
            val formattedLines = mutableListOf<String>()

            for (i in 0 until n) {
                val frac = m[i][n].simplify()
                values.add(frac)
                val vName = varNames[i]
                val line = if (frac.den == 1L) {
                    "$vName = ${frac.num}"
                } else {
                    "$vName = $frac  (≈ ${Fraction.formatDoubleNorm1(frac.toDouble())})"
                }
                formattedLines.add(line)
            }

            LinearSystemResult.UniqueSolution(values, formattedLines)
        } catch (e: Exception) {
            LinearSystemResult.Error(e.message ?: "Ошибка решения системы")
        }
    }

    /**
     * Решение полиномиального уравнения по массиву коэффициентов:
     * 3 элемента [a, b, c] для ax² + bx + c = 0
     * 4 элемента [a, b, c, d] для ax³ + bx² + cx + d = 0
     */
    fun solvePolynomialFromCoeffs(coeffs: List<String>): SolveResult {
        return try {
            val n = coeffs.size
            if (n !in 3..4) {
                return SolveResult("Ожидалось 3 или 4 коэффициента", null)
            }
            val parsed = coeffs.map { parseCellToFraction(it) }
            val eq = if (n == 3) {
                "(${parsed[0]})*x^2 + (${parsed[1]})*x + (${parsed[2]}) = 0"
            } else {
                "(${parsed[0]})*x^3 + (${parsed[1]})*x^2 + (${parsed[2]})*x + (${parsed[3]}) = 0"
            }
            isolateVariableFull(eq, "x")
        } catch (e: Exception) {
            SolveResult("Ошибка: ${e.message ?: "неверный ввод"}", null)
        }
    }

    fun compilePlot(expr: String): CompiledPlot? = DesmosPlotCompiler.compile(expr)

    /**
     * Быстрый компилятор математических выражений от одной переменной x в функциональный объект.
     * Парсит выражение один раз в AST-дерево Node и возвращает лямбду (Double) -> Double,
     * рекурсивно вычисляющую значение узла без создания промежуточных Fraction.
     */
    fun compileFunction(expr: String): ((Double) -> Double)? {
        val trimmed = expr.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val ast = Parser(trimmed, lastAnswer).parse()
            val fn: (Double) -> Double = { xVal: Double -> evalNodeAtX(ast, xVal) }
            fn
        } catch (_: Exception) {
            null
        }
    }

    fun hasPhysicalConstant(node: Node): Boolean {
        return when (node) {
            is Node.Num, is Node.Approx -> false
            is Node.Variable -> isConstant(node.name)
            is Node.Add -> hasPhysicalConstant(node.l) || hasPhysicalConstant(node.r)
            is Node.Sub -> hasPhysicalConstant(node.l) || hasPhysicalConstant(node.r)
            is Node.Mul -> hasPhysicalConstant(node.l) || hasPhysicalConstant(node.r)
            is Node.Div -> hasPhysicalConstant(node.l) || hasPhysicalConstant(node.r)
            is Node.Pow -> hasPhysicalConstant(node.base) || hasPhysicalConstant(node.exp)
            is Node.Sqrt -> hasPhysicalConstant(node.inner)
            is Node.Func -> hasPhysicalConstant(node.arg)
            is Node.Comb -> hasPhysicalConstant(node.n) || hasPhysicalConstant(node.k)
        }
    }

    fun evalNodeAtX(node: Node, xVal: Double): Double {
        return when (node) {
            is Node.Num -> node.value.toDouble()
            is Node.Approx -> node.value
            is Node.Variable -> {
                val constVal = getConstantValue(node.name)
                if (constVal != null) {
                    constVal
                } else when (node.name.lowercase()) {
                    "x" -> xVal
                    else -> Double.NaN
                }
            }
            is Node.Add -> evalNodeAtX(node.l, xVal) + evalNodeAtX(node.r, xVal)
            is Node.Sub -> evalNodeAtX(node.l, xVal) - evalNodeAtX(node.r, xVal)
            is Node.Mul -> evalNodeAtX(node.l, xVal) * evalNodeAtX(node.r, xVal)
            is Node.Div -> {
                val r = evalNodeAtX(node.r, xVal)
                if (r == 0.0 || r.isNaN()) Double.NaN
                else evalNodeAtX(node.l, xVal) / r
            }
            is Node.Pow -> {
                val b = evalNodeAtX(node.base, xVal)
                val e = evalNodeAtX(node.exp, xVal)
                b.pow(e)
            }
            is Node.Sqrt -> {
                val v = evalNodeAtX(node.inner, xVal)
                if (v < 0.0 || v.isNaN()) Double.NaN else sqrt(v)
            }
            is Node.Func -> {
                val a = evalNodeAtX(node.arg, xVal)
                if (a.isNaN()) return Double.NaN
                when (node.fn.lowercase()) {
                    "sin" -> sin(if (isDegreeMode) Math.toRadians(a) else a)
                    "cos" -> cos(if (isDegreeMode) Math.toRadians(a) else a)
                    "tan" -> {
                        val rad = if (isDegreeMode) Math.toRadians(a) else a
                        val c = cos(rad)
                        if (abs(c) < 1e-15) Double.NaN else tan(rad)
                    }
                    "asin" -> {
                        if (a < -1.0 || a > 1.0) Double.NaN
                        else {
                            val rad = asin(a)
                            if (isDegreeMode) Math.toDegrees(rad) else rad
                        }
                    }
                    "acos" -> {
                        if (a < -1.0 || a > 1.0) Double.NaN
                        else {
                            val rad = acos(a)
                            if (isDegreeMode) Math.toDegrees(rad) else rad
                        }
                    }
                    "atan" -> {
                        val rad = atan(a)
                        if (isDegreeMode) Math.toDegrees(rad) else rad
                    }
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
            is Node.Comb -> {
                val nD = evalNodeAtX(node.n, xVal)
                val kD = evalNodeAtX(node.k, xVal)
                if (nD.isNaN() || kD.isNaN() || nD < 0.0 || kD < 0.0 || nD % 1.0 != 0.0 || kD % 1.0 != 0.0) {
                    throw ArithmeticException("Индексы должны быть целыми неотрицательными числами")
                }
                if (kD > nD) {
                    throw ArithmeticException("Верхний индекс k не может быть больше нижнего n")
                }
                val resNode = evalCombinatorics(node.isPerm, nD.toLong(), kD.toLong())
                when (resNode) {
                    is Node.Num -> resNode.value.toDouble()
                    is Node.Approx -> resNode.value
                    else -> Double.NaN
                }
            }
        }
    }

    // --- МАТРИЧНЫЕ ВЫЧИСЛЕНИЯ НА ДРОБЯХ (2x2 и 3x3) ---

    /**
     * Точный расчёт det A для 2×2 и 3×3 на объектах Fraction.
     */
    fun matrixDeterminant(a: List<List<String>>): MatrixResult {
        return try {
            val n = a.size
            if (n !in 2..3) {
                return MatrixResult.Error("Поддерживаются только матрицы 2×2 и 3×3")
            }
            for (r in a) {
                if (r.size != n) {
                    return MatrixResult.Error("Матрица должна быть квадратной ${n}×${n}")
                }
            }
            val m = a.map { row -> row.map { parseCellToFraction(it) } }
            val det = if (n == 2) {
                (m[0][0] * m[1][1]) - (m[0][1] * m[1][0])
            } else {
                val d00 = (m[1][1] * m[2][2]) - (m[1][2] * m[2][1])
                val d01 = (m[1][0] * m[2][2]) - (m[1][2] * m[2][0])
                val d02 = (m[1][0] * m[2][1]) - (m[1][1] * m[2][0])
                (m[0][0] * d00) - (m[0][1] * d01) + (m[0][2] * d02)
            }.simplify()

            val formatted = if (det.den == 1L) {
                "${det.num}"
            } else {
                "$det  (≈ ${Fraction.formatDoubleNorm1(det.toDouble())})"
            }
            MatrixResult.Scalar("det(A)", det, formatted)
        } catch (e: Exception) {
            MatrixResult.Error(e.message ?: "Ошибка вычисления определителя")
        }
    }

    /**
     * Обратная матрица A⁻¹ методом Гаусса-Жордана на Fraction.
     * Если матрица вырожденная (det A = 0), вернуть MatrixResult.Error("Матрица вырождена (det A = 0), обратной не существует").
     */
    fun matrixInverse(a: List<List<String>>): MatrixResult {
        return try {
            val n = a.size
            if (n !in 2..3) {
                return MatrixResult.Error("Поддерживаются только матрицы 2×2 и 3×3")
            }
            for (r in a) {
                if (r.size != n) {
                    return MatrixResult.Error("Матрица должна быть квадратной ${n}×${n}")
                }
            }

            val detResult = matrixDeterminant(a)
            if (detResult is MatrixResult.Error) return detResult
            if (detResult is MatrixResult.Scalar && detResult.value.num == 0L) {
                return MatrixResult.Error("Матрица вырождена (det A = 0), обратной не существует")
            }

            val aug = Array(n) { i ->
                Array(2 * n) { j ->
                    if (j < n) parseCellToFraction(a[i][j])
                    else if (j - n == i) Fraction(1L)
                    else Fraction(0L)
                }
            }

            var leadRow = 0
            for (col in 0 until n) {
                var pivotRow = leadRow
                var maxVal = abs(aug[leadRow][col].toDouble())
                for (r in leadRow + 1 until n) {
                    val curVal = abs(aug[r][col].toDouble())
                    if (curVal > maxVal) {
                        maxVal = curVal
                        pivotRow = r
                    }
                }

                if (aug[pivotRow][col].num == 0L) {
                    return MatrixResult.Error("Матрица вырождена (det A = 0), обратной не существует")
                }

                if (pivotRow != leadRow) {
                    val temp = aug[leadRow]
                    aug[leadRow] = aug[pivotRow]
                    aug[pivotRow] = temp
                }

                val pivot = aug[leadRow][col]
                for (j in 0 until 2 * n) {
                    aug[leadRow][j] = (aug[leadRow][j] / pivot).simplify()
                }

                for (r in 0 until n) {
                    if (r != leadRow) {
                        val factor = aug[r][col]
                        if (factor.num != 0L) {
                            for (j in 0 until 2 * n) {
                                aug[r][j] = (aug[r][j] - (factor * aug[leadRow][j])).simplify()
                            }
                        }
                    }
                }

                leadRow++
            }

            val invRows = (0 until n).map { i ->
                (0 until n).map { j ->
                    aug[i][n + j].simplify()
                }
            }
            val formattedRows = invRows.map { row -> row.map { it.toString() } }
            MatrixResult.MatrixValue("A⁻¹", invRows, formattedRows)
        } catch (e: Exception) {
            MatrixResult.Error(e.message ?: "Ошибка обращения матрицы")
        }
    }

    /**
     * Транспонирование Aᵀ.
     */
    fun matrixTranspose(a: List<List<String>>): MatrixResult {
        return try {
            val n = a.size
            if (n !in 2..3) {
                return MatrixResult.Error("Поддерживаются только матрицы 2×2 и 3×3")
            }
            for (r in a) {
                if (r.size != n) {
                    return MatrixResult.Error("Матрица должна быть квадратной ${n}×${n}")
                }
            }
            val m = a.map { row -> row.map { parseCellToFraction(it) } }
            val transRows = (0 until n).map { i ->
                (0 until n).map { j ->
                    m[j][i].simplify()
                }
            }
            val formattedRows = transRows.map { row -> row.map { it.toString() } }
            MatrixResult.MatrixValue("Aᵀ", transRows, formattedRows)
        } catch (e: Exception) {
            MatrixResult.Error(e.message ?: "Ошибка транспонирования матрицы")
        }
    }

    /**
     * Бинарные операции "+", "-", "*" над квадратными матрицами 2×2 или 3×3.
     */
    fun matrixBinaryOp(a: List<List<String>>, b: List<List<String>>, op: String): MatrixResult {
        return try {
            val n = a.size
            if (n !in 2..3 || b.size != n) {
                return MatrixResult.Error("Операции возможны только между матрицами одинакового порядка (2×2 или 3×3)")
            }
            for (r in a) {
                if (r.size != n) return MatrixResult.Error("Матрица A должна быть квадратной ${n}×${n}")
            }
            for (r in b) {
                if (r.size != n) return MatrixResult.Error("Матрица B должна быть квадратной ${n}×${n}")
            }

            val ma = a.map { row -> row.map { parseCellToFraction(it) } }
            val mb = b.map { row -> row.map { parseCellToFraction(it) } }

            val (label, resRows) = when (op) {
                "+", "A + B" -> {
                    val rows = (0 until n).map { i ->
                        (0 until n).map { j ->
                            (ma[i][j] + mb[i][j]).simplify()
                        }
                    }
                    "A + B" to rows
                }
                "-", "−", "A - B", "A − B" -> {
                    val rows = (0 until n).map { i ->
                        (0 until n).map { j ->
                            (ma[i][j] - mb[i][j]).simplify()
                        }
                    }
                    "A − B" to rows
                }
                "*", "×", "·", "A * B", "A × B" -> {
                    val rows = (0 until n).map { i ->
                        (0 until n).map { j ->
                            var sum = Fraction(0L)
                            for (k in 0 until n) {
                                sum = sum + (ma[i][k] * mb[k][j])
                            }
                            sum.simplify()
                        }
                    }
                    "A × B" to rows
                }
                else -> return MatrixResult.Error("Неизвестная операция: $op")
            }

            val formattedRows = resRows.map { row -> row.map { it.toString() } }
            MatrixResult.MatrixValue(label, resRows, formattedRows)
        } catch (e: Exception) {
            MatrixResult.Error(e.message ?: "Ошибка бинарной операции над матрицами")
        }
    }
}

// --- МОДЕЛИ РЕЗУЛЬТАТА СЛАУ ---

sealed class LinearSystemResult {
    open val detailedSteps: List<SystemStep> get() = emptyList()

    data class UniqueSolution(
        val values: List<CasEngine.Fraction>,
        val formattedLines: List<String>,
        override val detailedSteps: List<SystemStep> = emptyList()
    ) : LinearSystemResult()

    data class NoSolution(
        override val detailedSteps: List<SystemStep> = emptyList()
    ) : LinearSystemResult()

    data class InfiniteSolutions(
        override val detailedSteps: List<SystemStep> = emptyList()
    ) : LinearSystemResult()

    data class Error(
        val message: String,
        override val detailedSteps: List<SystemStep> = emptyList()
    ) : LinearSystemResult()
}

// --- МОДЕЛИ РЕЗУЛЬТАТА МАТРИЦ ---

sealed class MatrixResult {
    data class Scalar(val label: String, val value: Fraction, val formatted: String) : MatrixResult()
    data class MatrixValue(val label: String, val rows: List<List<Fraction>>, val formattedRows: List<List<String>>) : MatrixResult()
    data class Error(val message: String) : MatrixResult()
}
