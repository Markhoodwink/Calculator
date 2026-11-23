package com.example.calculator

import com.example.calculator.CasEngine.Fraction
import com.example.calculator.CasEngine.Node
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.*

enum class IneqOp(val symbol: String) {
    LT("<"),
    LE("≤"),
    GT(">"),
    GE("≥")
}

data class CriticalPoint(
    val value: Double,
    val label: String,
    val isIncluded: Boolean,
    val isOdzExcluded: Boolean,
    val reason: String
)

data class IntervalSegment(
    val start: Double,
    val end: Double,
    val startLabel: String,
    val endLabel: String,
    val startClosed: Boolean,
    val endClosed: Boolean,
    val sign: Int,
    val isInDomain: Boolean,
    val isSolution: Boolean,
    val sampleLabel: String = "",
    val sampleValueLabel: String = ""
)

data class InequalityResult(
    val answerFormula: String,
    val intervals: List<IntervalSegment>,
    val criticalPoints: List<CriticalPoint>,
    val odzConstraints: List<String>,
    val detailedSteps: List<CasStep>,
    val errorMessage: String? = null,
    val isPeriodic: Boolean = false
)

object InequalitySolverEngine {

    private enum class OdzType {
        DENOMINATOR_NOT_ZERO,
        SQRT_NON_NEGATIVE,
        LOG_ARG_POSITIVE,
        LOG_BASE_POSITIVE,
        LOG_BASE_NOT_ONE
    }

    private data class OdzRule(
        val node: Node,
        val type: OdzType,
        val exprString: String,
        val description: String
    )

    private data class OpMatch(val op: IneqOp, val index: Int, val matchedLength: Int)

    private data class SignedLog(
        val sign: Int,          // -1, 0, +1
        val logAbs: Double,     // ln(|value|), Double.NEGATIVE_INFINITY when sign == 0
        val maxTermLog: Double = logAbs, // scale of largest additive term (to distinguish true cancellation-zero from tiny underflow!)
        val isValid: Boolean = true
    ) {
        val relativeLog: Double
            get() = if (sign == 0) Double.NEGATIVE_INFINITY else logAbs - maxTermLog

        fun isEffectivelyZero(relThreshold: Double = -24.0): Boolean =
            isValid && (sign == 0 || (maxTermLog.isFinite() && (logAbs - maxTermLog) < relThreshold))

        fun toDouble(): Double {
            if (!isValid) return Double.NaN
            if (sign == 0) return 0.0
            if (logAbs > 709.0) return if (sign > 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
            if (logAbs < -745.0) return 0.0
            return sign * exp(logAbs)
        }

        companion object {
            val INVALID = SignedLog(0, Double.NaN, Double.NaN, isValid = false)
            val ZERO = SignedLog(0, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, isValid = true)
            fun fromDouble(v: Double): SignedLog {
                if (v.isNaN() || v.isInfinite()) return INVALID
                if (v == 0.0) return ZERO
                val s = if (v > 0.0) 1 else -1
                val l = ln(abs(v))
                return SignedLog(s, l, l, isValid = true)
            }
        }
    }

    private fun addSignedLog(a: SignedLog, b: SignedLog): SignedLog {
        if (!a.isValid || !b.isValid) return SignedLog.INVALID
        if (a.sign == 0) return b.copy(maxTermLog = maxOf(a.maxTermLog, b.maxTermLog))
        if (b.sign == 0) return a.copy(maxTermLog = maxOf(a.maxTermLog, b.maxTermLog))
        val (big, small) = if (a.logAbs >= b.logAbs) Pair(a, b) else Pair(b, a)
        val maxT = maxOf(a.maxTermLog, b.maxTermLog)
        val diff = small.logAbs - big.logAbs // <= 0.0
        if (big.sign == small.sign) {
            val resLog = if (diff < -40.0) big.logAbs else big.logAbs + ln1p(exp(diff))
            return SignedLog(big.sign, resLog, maxT, true)
        } else { // big.sign != small.sign
            if (diff >= -1e-15) return SignedLog(0, Double.NEGATIVE_INFINITY, maxT, true) // exact cancellation!
            val resLog = if (diff < -40.0) big.logAbs else big.logAbs + ln(-expm1(diff))
            return SignedLog(big.sign, resLog, maxT, true)
        }
    }

    private fun evalSignedLog(node: Node, x: Double, casEngine: CasEngine): SignedLog {
        return when (node) {
            is Node.Num -> SignedLog.fromDouble(node.value.toDouble())
            is Node.Approx -> SignedLog.fromDouble(node.value)
            is Node.Variable -> {
                when (node.name.lowercase()) {
                    "x" -> SignedLog.fromDouble(x)
                    "π", "pi" -> SignedLog.fromDouble(Math.PI)
                    "e" -> SignedLog.fromDouble(Math.E)
                    else -> {
                        val constVal = CasEngine.getConstantValue(node.name)
                        if (constVal != null) SignedLog.fromDouble(constVal)
                        else SignedLog.fromDouble(casEngine.evalNodeAtX(node, x))
                    }
                }
            }
            is Node.Add -> addSignedLog(evalSignedLog(node.l, x, casEngine), evalSignedLog(node.r, x, casEngine))
            is Node.Sub -> addSignedLog(
                evalSignedLog(node.l, x, casEngine),
                evalSignedLog(node.r, x, casEngine).let { if (it.isValid) it.copy(sign = -it.sign) else it }
            )
            is Node.Mul -> {
                val l = evalSignedLog(node.l, x, casEngine)
                val r = evalSignedLog(node.r, x, casEngine)
                if (!l.isValid || !r.isValid) SignedLog.INVALID
                else if (l.sign == 0 || r.sign == 0) {
                    SignedLog(0, Double.NEGATIVE_INFINITY, maxOf(l.maxTermLog + r.maxTermLog, Double.NEGATIVE_INFINITY), true)
                } else {
                    SignedLog(l.sign * r.sign, l.logAbs + r.logAbs, l.maxTermLog + r.maxTermLog, true)
                }
            }
            is Node.Div -> {
                val l = evalSignedLog(node.l, x, casEngine)
                val r = evalSignedLog(node.r, x, casEngine)
                if (!l.isValid || !r.isValid || r.sign == 0) SignedLog.INVALID
                else if (l.sign == 0) {
                    SignedLog(0, Double.NEGATIVE_INFINITY, l.maxTermLog - r.logAbs, true)
                } else {
                    SignedLog(l.sign * r.sign, l.logAbs - r.logAbs, l.maxTermLog - r.logAbs, true)
                }
            }
            is Node.Pow -> {
                val b = evalSignedLog(node.base, x, casEngine)
                val e = evalSignedLog(node.exp, x, casEngine)
                if (!b.isValid || !e.isValid) SignedLog.INVALID
                else {
                    val eVal = e.toDouble()
                    if (eVal.isNaN() || eVal.isInfinite()) SignedLog.INVALID
                    else if (b.sign > 0) {
                        val newLog = eVal * b.logAbs
                        val newMaxT = if (eVal > 0.0 && b.maxTermLog.isFinite()) eVal * b.maxTermLog else newLog
                        SignedLog(1, newLog, newMaxT, true)
                    } else if (b.sign == 0) {
                        if (eVal > 0.0) {
                            SignedLog(0, Double.NEGATIVE_INFINITY, if (b.maxTermLog.isFinite()) eVal * b.maxTermLog else Double.NEGATIVE_INFINITY, true)
                        } else if (eVal == 0.0) {
                            SignedLog.fromDouble(1.0)
                        } else {
                            SignedLog.INVALID
                        }
                    } else { // b.sign < 0
                        if (abs(eVal - round(eVal)) < 1e-9) {
                            val k = round(eVal).toLong()
                            val s = if (k % 2L == 0L) 1 else -1
                            val newLog = eVal * b.logAbs
                            val newMaxT = if (eVal > 0.0 && b.maxTermLog.isFinite()) eVal * b.maxTermLog else newLog
                            SignedLog(s, newLog, newMaxT, true)
                        } else {
                            var oddDenomMatch = false
                            var s = 1
                            for (q in 1..99) {
                                if (abs(eVal * q - round(eVal * q)) < 1e-9) {
                                    val p = round(eVal * q).toLong()
                                    if (q % 2 != 0) {
                                        oddDenomMatch = true
                                        s = if (p % 2L == 0L) 1 else -1
                                        break
                                    }
                                }
                            }
                            if (oddDenomMatch) {
                                val newLog = eVal * b.logAbs
                                val newMaxT = if (eVal > 0.0 && b.maxTermLog.isFinite()) eVal * b.maxTermLog else newLog
                                SignedLog(s, newLog, newMaxT, true)
                            } else {
                                SignedLog.INVALID
                            }
                        }
                    }
                }
            }
            is Node.Sqrt -> {
                val u = evalSignedLog(node.inner, x, casEngine)
                if (!u.isValid || u.sign < 0) SignedLog.INVALID
                else if (u.sign == 0) {
                    SignedLog(0, Double.NEGATIVE_INFINITY, if (u.maxTermLog.isFinite()) 0.5 * u.maxTermLog else Double.NEGATIVE_INFINITY, true)
                } else {
                    SignedLog(1, 0.5 * u.logAbs, 0.5 * u.maxTermLog, true)
                }
            }
            is Node.LogBase -> {
                val b = evalSignedLog(node.base, x, casEngine)
                val a = evalSignedLog(node.arg, x, casEngine)
                if (!b.isValid || !a.isValid || b.sign <= 0 || a.sign <= 0) SignedLog.INVALID
                else if (abs(b.logAbs) < 1e-12) SignedLog.INVALID
                else SignedLog.fromDouble(a.logAbs / b.logAbs)
            }
            is Node.Func -> {
                val u = evalSignedLog(node.arg, x, casEngine)
                when (node.fn.lowercase()) {
                    "abs" -> if (!u.isValid) SignedLog.INVALID else u.copy(sign = if (u.sign == 0) 0 else 1)
                    "ln" -> if (!u.isValid || u.sign <= 0) SignedLog.INVALID else SignedLog.fromDouble(u.logAbs)
                    "log", "lg" -> if (!u.isValid || u.sign <= 0) SignedLog.INVALID else SignedLog.fromDouble(u.logAbs / ln(10.0))
                    "sqrt" -> {
                        if (!u.isValid || u.sign < 0) SignedLog.INVALID
                        else if (u.sign == 0) SignedLog(0, Double.NEGATIVE_INFINITY, if (u.maxTermLog.isFinite()) 0.5 * u.maxTermLog else Double.NEGATIVE_INFINITY, true)
                        else SignedLog(1, 0.5 * u.logAbs, 0.5 * u.maxTermLog, true)
                    }
                    "cbrt" -> {
                        if (!u.isValid) SignedLog.INVALID
                        else if (u.sign == 0) SignedLog.ZERO
                        else SignedLog(u.sign, u.logAbs / 3.0, u.maxTermLog / 3.0, true)
                    }
                    "sin", "cos", "tan", "tg" -> {
                        val v = casEngine.evalNodeAtX(node, x)
                        if (v.isNaN()) SignedLog.INVALID
                        else if (abs(v) < 1e-14) SignedLog.ZERO
                        else SignedLog.fromDouble(v)
                    }
                    else -> SignedLog.fromDouble(casEngine.evalNodeAtX(node, x))
                }
            }
            is Node.Comb -> {
                SignedLog.fromDouble(try { casEngine.evalNodeAtX(node, x) } catch (_: Exception) { Double.NaN })
            }
        }
    }

    private fun formatSampleValue(s: SignedLog): String {
        if (!s.isValid) return "—"
        if (s.isEffectivelyZero(relThreshold = -20.0)) return "0"
        val d = s.toDouble()
        if (d.isFinite() && d != 0.0) {
            return Fraction.formatDoubleNorm1(d)
        }
        if (d.isInfinite()) {
            return if (s.sign > 0) "+∞" else "-∞"
        }
        if (s.maxTermLog.isFinite() && s.logAbs.isFinite()) {
            val log10Val = s.logAbs / ln(10.0)
            val exp10 = floor(log10Val).toInt()
            var mantissa = s.sign * exp(s.logAbs - exp10 * ln(10.0))
            if (abs(mantissa) >= 10.0) {
                mantissa /= 10.0
                val expCorrected = exp10 + 1
                val bd = BigDecimal(mantissa, MathContext(4, RoundingMode.HALF_UP)).stripTrailingZeros()
                val sup = Fraction.toSuperscript(expCorrected)
                return "${bd.toPlainString()} · 10$sup"
            }
            val bd = BigDecimal(mantissa, MathContext(4, RoundingMode.HALF_UP)).stripTrailingZeros()
            val sup = Fraction.toSuperscript(exp10)
            return "${bd.toPlainString()} · 10$sup"
        }
        return if (s.sign > 0) "> 0" else "< 0"
    }

    fun solve(input: String, casEngine: CasEngine = CasEngine()): InequalityResult {
        try {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) {
                return InequalityResult("", emptyList(), emptyList(), emptyList(), emptyList(), "Пустой ввод", false)
            }

            val opMatch = findIneqOperator(trimmed)
                ?: return InequalityResult("", emptyList(), emptyList(), emptyList(), emptyList(), "Не найден знак неравенства (<, >, <=, >=, ≤, ≥)", false)

            val op = opMatch.op
            val leftRaw = trimmed.substring(0, opMatch.index).trim()
            val rightRaw = trimmed.substring(opMatch.index + opMatch.matchedLength).trim()

            if (leftRaw.isEmpty() || rightRaw.isEmpty()) {
                return InequalityResult("", emptyList(), emptyList(), emptyList(), emptyList(), "Левая или правая часть неравенства пуста", false)
            }

            val leftNorm = normalizeInput(leftRaw)
            val rightNorm = normalizeInput(rightRaw)

            val leftAst = CasEngine.Parser(leftNorm).parse()
            val rightAst = CasEngine.Parser(rightNorm).parse()
            val diffAst = Node.Sub(leftAst, rightAst)

            // Detect 2π periodicity
            val isPeriodicTrig = containsTrigFunc(diffAst) && allVarsInTrig(diffAst, false)
            val solveEngine = if (isPeriodicTrig) {
                CasEngine().apply {
                    isDegreeMode = false
                    lastAnswer = casEngine.lastAnswer
                }
            } else {
                casEngine
            }

            // Collect bases and exp/log flags
            val knownBases = mutableSetOf<Long>()
            val hasExpOrLog = collectExpAndLogInfo(diffAst, knownBases)

            // Extract ODZ rules
            val odzRules = mutableListOf<OdzRule>()
            collectOdzRules(leftAst, odzRules)
            collectOdzRules(rightAst, odzRules)
            val uniqueOdzRules = odzRules.distinctBy { Pair(it.exprString, it.type) }

            fun isInOdz(x: Double): Boolean {
                if (x.isNaN() || x.isInfinite()) return false
                val snapTrigX = if (isPeriodicTrig) {
                    val mult = round(x / Math.PI)
                    if (abs(x - mult * Math.PI) < 1e-11) mult * Math.PI else x
                } else x

                for (rule in uniqueOdzRules) {
                    val sl = evalSignedLog(rule.node, snapTrigX, solveEngine)
                    if (!sl.isValid) return false
                    var v = sl.toDouble()
                    if (isPeriodicTrig && rule.node.toExprString().contains("sin")) {
                        val mult = round(snapTrigX / Math.PI)
                        if (abs(snapTrigX - mult * Math.PI) < 1e-11) {
                            v = 0.0
                        }
                    }
                    when (rule.type) {
                        OdzType.DENOMINATOR_NOT_ZERO -> if (sl.isEffectivelyZero(relThreshold = -24.0) || abs(v) <= 1e-12) return false
                        OdzType.SQRT_NON_NEGATIVE -> if (sl.sign < 0 && !sl.isEffectivelyZero(relThreshold = -24.0)) return false
                        OdzType.LOG_ARG_POSITIVE -> if (sl.sign <= 0 || sl.isEffectivelyZero(relThreshold = -24.0)) return false
                        OdzType.LOG_BASE_POSITIVE -> if (sl.sign <= 0 || sl.isEffectivelyZero(relThreshold = -24.0)) return false
                        OdzType.LOG_BASE_NOT_ONE -> if (sl.sign > 0 && abs(sl.logAbs) <= 1e-12) return false
                    }
                }
                val diffSl = evalSignedLog(diffAst, snapTrigX, solveEngine)
                return diffSl.isValid
            }

            fun isBaseOdz(x: Double): Boolean {
                if (x.isNaN() || x.isInfinite()) return false
                val snapTrigX = if (isPeriodicTrig) {
                    val mult = round(x / Math.PI)
                    if (abs(x - mult * Math.PI) < 1e-11) mult * Math.PI else x
                } else x

                for (rule in uniqueOdzRules) {
                    if (rule.type == OdzType.DENOMINATOR_NOT_ZERO) continue
                    val sl = evalSignedLog(rule.node, snapTrigX, solveEngine)
                    if (!sl.isValid) return false
                    when (rule.type) {
                        OdzType.SQRT_NON_NEGATIVE -> if (sl.sign < 0 && !sl.isEffectivelyZero(relThreshold = -24.0)) return false
                        OdzType.LOG_ARG_POSITIVE -> if (sl.sign <= 0 || sl.isEffectivelyZero(relThreshold = -24.0)) return false
                        OdzType.LOG_BASE_POSITIVE -> if (sl.sign <= 0 || sl.isEffectivelyZero(relThreshold = -24.0)) return false
                        OdzType.LOG_BASE_NOT_ONE -> if (sl.sign > 0 && abs(sl.logAbs) <= 1e-12) return false
                        else -> {}
                    }
                }
                return true
            }

            // Collect candidate nodes for critical point discovery
            val candidateNodes = mutableListOf<Node>()
            for (rule in uniqueOdzRules) {
                candidateNodes.add(rule.node)
                if (rule.type == OdzType.LOG_BASE_NOT_ONE) {
                    candidateNodes.add(Node.Sub(rule.node, Node.Num(Fraction(1))))
                }
            }
            collectAbsInnerNodes(leftAst, candidateNodes)
            collectAbsInnerNodes(rightAst, candidateNodes)
            collectTopLevelMultiplicativeFactors(diffAst, candidateNodes)
            val uniqueCandidateNodes = candidateNodes.distinctBy { it.toExprString() }

            val rawCandidates = mutableListOf<Double>()

            if (isPeriodicTrig) {
                rawCandidates.add(0.0)
                rawCandidates.add(Math.PI)
                rawCandidates.add(2 * Math.PI)

                // 1. Component scan on [0, 2π]
                for (node in uniqueCandidateNodes) {
                    if (!node.containsVar("x")) continue
                    val compScanSteps = 1600
                    val compStep = (2 * Math.PI) / compScanSteps
                    var xPrev = Double.NaN
                    var sPrev = SignedLog.INVALID
                    var xCurr = Double.NaN
                    var sCurr = SignedLog.INVALID

                    for (k in 0..compScanSteps) {
                        val xNext = k * compStep
                        val sNext = evalSignedLog(node, xNext, solveEngine)

                        if (!xCurr.isNaN() && !xNext.isNaN() && sCurr.isValid && sNext.isValid) {
                            if (sCurr.sign == 0 || sNext.sign == 0 || sCurr.sign * sNext.sign < 0) {
                                val root = bisectionRoot(node, xCurr, xNext, solveEngine)
                                if (isBaseOdz(root)) {
                                    rawCandidates.add(root)
                                }
                            }
                        }

                        if (!xPrev.isNaN() && !xCurr.isNaN() && !xNext.isNaN() &&
                            sPrev.isValid && sCurr.isValid && sNext.isValid
                        ) {
                            if (sCurr.relativeLog < -0.5 && sCurr.relativeLog < sPrev.relativeLog && sCurr.relativeLog < sNext.relativeLog) {
                                val minX = goldenSectionMin(node, xPrev, xNext, solveEngine)
                                val sMin = evalSignedLog(node, minX, solveEngine)
                                val minNeighborLog = minOf(sPrev.relativeLog, sNext.relativeLog)
                                if (sMin.isValid && (sMin.sign == 0 || (sMin.relativeLog < -11.5 && sMin.relativeLog < minNeighborLog - 5.0)) && isBaseOdz(minX)) {
                                    rawCandidates.add(minX)
                                }
                            }
                        }

                        xPrev = xCurr
                        sPrev = sCurr
                        xCurr = xNext
                        sCurr = sNext
                    }
                }

                // 2. Multi-scale scan on diffAst on [0, 2π]
                val periodicSteps = 4000
                val periodicStep = (2 * Math.PI) / periodicSteps
                var dxPrev = Double.NaN
                var dsPrev = SignedLog.INVALID
                var dxCurr = Double.NaN
                var dsCurr = SignedLog.INVALID

                for (k in 0..periodicSteps) {
                    val xNext = k * periodicStep
                    val odzNext = isInOdz(xNext)
                    val sNext = if (odzNext) evalSignedLog(diffAst, xNext, solveEngine) else SignedLog.INVALID

                    if (!dxCurr.isNaN() && !xNext.isNaN()) {
                        val odzCurr = isInOdz(dxCurr)
                        if (odzCurr != odzNext) {
                            val bnd = bisectionOdzBoundary(dxCurr, xNext, ::isInOdz)
                            rawCandidates.add(bnd)
                        }

                        if (odzCurr && odzNext && dsCurr.isValid && sNext.isValid) {
                            if (dsCurr.sign == 0 || sNext.sign == 0 || dsCurr.sign * sNext.sign < 0) {
                                val r = bisectionRoot(diffAst, dxCurr, xNext, solveEngine)
                                rawCandidates.add(r)
                            }
                        }
                    }

                    if (!dxPrev.isNaN() && !dxCurr.isNaN() && !xNext.isNaN() &&
                        dsPrev.isValid && dsCurr.isValid && sNext.isValid
                    ) {
                        if (dsCurr.relativeLog < -0.5 && dsCurr.relativeLog < dsPrev.relativeLog && dsCurr.relativeLog < sNext.relativeLog) {
                            val minX = goldenSectionMin(diffAst, dxPrev, xNext, solveEngine)
                            val sMin = evalSignedLog(diffAst, minX, solveEngine)
                            val minNeighborLog = minOf(dsPrev.relativeLog, sNext.relativeLog)
                            if (sMin.isValid && (sMin.sign == 0 || (sMin.relativeLog < -11.5 && sMin.relativeLog < minNeighborLog - 5.0)) && isInOdz(minX)) {
                                rawCandidates.add(minX)
                            }
                        }
                    }

                    dxPrev = dxCurr
                    dsPrev = dsCurr
                    dxCurr = xNext
                    dsCurr = sNext
                }
            } else {
                // 1. Symbolic isolation & scanning on component expressions
                for (node in uniqueCandidateNodes) {
                    if (!node.containsVar("x")) continue
                    val exprStr = node.toExprString()
                    val eqStr = "$exprStr = 0"
                    val solRes = solveEngine.isolateVariableFull(eqStr, "x")
                    if (solRes.primaryNode != null) {
                        val valSol = solveEngine.evalNodeAtX(solRes.primaryNode, 0.0)
                        if (!valSol.isNaN() && !valSol.isInfinite() && isBaseOdz(valSol)) {
                            rawCandidates.add(valSol)
                        }
                    }

                    // Component scan on [-40, 40]
                    val compScanSteps = 1600
                    val compStep = 80.0 / compScanSteps
                    var xPrev = Double.NaN
                    var sPrev = SignedLog.INVALID
                    var xCurr = Double.NaN
                    var sCurr = SignedLog.INVALID

                    for (k in 0..compScanSteps) {
                        val xNext = -40.0 + k * compStep
                        val sNext = evalSignedLog(node, xNext, solveEngine)

                        if (!xCurr.isNaN() && !xNext.isNaN() && sCurr.isValid && sNext.isValid) {
                            if (sCurr.sign == 0 || sNext.sign == 0 || sCurr.sign * sNext.sign < 0) {
                                val root = bisectionRoot(node, xCurr, xNext, solveEngine)
                                if (isBaseOdz(root)) {
                                    rawCandidates.add(root)
                                }
                            }
                        }

                        if (!xPrev.isNaN() && !xCurr.isNaN() && !xNext.isNaN() &&
                            sPrev.isValid && sCurr.isValid && sNext.isValid
                        ) {
                            if (sCurr.relativeLog < -0.5 && sCurr.relativeLog < sPrev.relativeLog && sCurr.relativeLog < sNext.relativeLog) {
                                val minX = goldenSectionMin(node, xPrev, xNext, solveEngine)
                                val sMin = evalSignedLog(node, minX, solveEngine)
                                val minNeighborLog = minOf(sPrev.relativeLog, sNext.relativeLog)
                                if (sMin.isValid && (sMin.sign == 0 || (sMin.relativeLog < -11.5 && sMin.relativeLog < minNeighborLog - 5.0)) && isBaseOdz(minX)) {
                                    rawCandidates.add(minX)
                                }
                            }
                        }

                        xPrev = xCurr
                        sPrev = sCurr
                        xCurr = xNext
                        sCurr = sNext
                    }
                }

                // 2. Multi-scale scan on diffAst
                val denseSteps = 16000
                val denseStep = 80.0 / denseSteps
                var dxPrev = Double.NaN
                var dsPrev = SignedLog.INVALID
                var dxCurr = Double.NaN
                var dsCurr = SignedLog.INVALID

                for (k in 0..denseSteps) {
                    val xNext = -40.0 + k * denseStep
                    val odzNext = isInOdz(xNext)
                    val sNext = if (odzNext) evalSignedLog(diffAst, xNext, solveEngine) else SignedLog.INVALID

                    if (!dxCurr.isNaN() && !xNext.isNaN()) {
                        val odzCurr = isInOdz(dxCurr)
                        if (odzCurr != odzNext) {
                            val bnd = bisectionOdzBoundary(dxCurr, xNext, ::isInOdz)
                            rawCandidates.add(bnd)
                        }

                        if (odzCurr && odzNext && dsCurr.isValid && sNext.isValid) {
                            if (dsCurr.sign == 0 || sNext.sign == 0 || dsCurr.sign * sNext.sign < 0) {
                                val r = bisectionRoot(diffAst, dxCurr, xNext, solveEngine)
                                rawCandidates.add(r)
                            }
                        }
                    }

                    if (!dxPrev.isNaN() && !dxCurr.isNaN() && !xNext.isNaN() &&
                        dsPrev.isValid && dsCurr.isValid && sNext.isValid
                    ) {
                        if (dsCurr.relativeLog < -0.5 && dsCurr.relativeLog < dsPrev.relativeLog && dsCurr.relativeLog < sNext.relativeLog) {
                            val minX = goldenSectionMin(diffAst, dxPrev, xNext, solveEngine)
                            val sMin = evalSignedLog(diffAst, minX, solveEngine)
                            val minNeighborLog = minOf(dsPrev.relativeLog, sNext.relativeLog)
                            if (sMin.isValid && (sMin.sign == 0 || (sMin.relativeLog < -11.5 && sMin.relativeLog < minNeighborLog - 5.0)) && isInOdz(minX)) {
                                rawCandidates.add(minX)
                            }
                        }
                    }

                    dxPrev = dxCurr
                    dsPrev = dsCurr
                    dxCurr = xNext
                    dsCurr = sNext
                }

                // Capped wide scan range within [-120.0, 120.0]
                val widePoints = listOf(
                    -120.0, -90.0, -70.0, -55.0, -45.0,
                    45.0, 55.0, 70.0, 90.0, 120.0
                )
                for (idx in 0 until widePoints.size - 1) {
                    val x1 = widePoints[idx]
                    val x2 = widePoints[idx + 1]
                    val odz1 = isInOdz(x1)
                    val odz2 = isInOdz(x2)
                    if (odz1 != odz2) {
                        val bnd = bisectionOdzBoundary(x1, x2, ::isInOdz)
                        rawCandidates.add(bnd)
                    }
                    if (odz1 && odz2) {
                        val s1 = evalSignedLog(diffAst, x1, solveEngine)
                        val s2 = evalSignedLog(diffAst, x2, solveEngine)
                        if (s1.isValid && s2.isValid && (s1.sign == 0 || s2.sign == 0 || s1.sign * s2.sign < 0)) {
                            val r = bisectionRoot(diffAst, x1, x2, solveEngine)
                            rawCandidates.add(r)
                        }
                    }
                }
            }

            // Extra dense local scan around all discovered raw candidates
            val knownCandidates = rawCandidates.toList()
            for (cand in knownCandidates) {
                if (cand.isNaN() || cand.isInfinite()) continue
                val offsets = listOf(-1.0, -0.1, -1e-2, -1e-3, -1e-4, -1e-5, -1e-6, 0.0, 1e-6, 1e-5, 1e-4, 1e-3, 1e-2, 0.1, 1.0)
                for (o in offsets) {
                    val pt = cand + o
                    val ptOdz = isInOdz(pt)
                    val nextPtOdz = isInOdz(pt + 1e-5)
                    if (ptOdz != nextPtOdz) {
                        val bnd = bisectionOdzBoundary(pt, pt + 1e-5, ::isInOdz)
                        rawCandidates.add(bnd)
                    }
                }
            }

            val exactValidator: (Double) -> Boolean = { cand ->
                val sDiff = evalSignedLog(diffAst, cand, solveEngine)
                if (sDiff.isValid && sDiff.isEffectivelyZero(relThreshold = -14.0)) {
                    true
                } else {
                    uniqueOdzRules.any { rule ->
                        val sRule = evalSignedLog(rule.node, cand, solveEngine)
                        sRule.isValid && sRule.isEffectivelyZero(relThreshold = -14.0)
                    }
                }
            }

            // Deduplicate and snap critical points
            val snappedPoints = mutableListOf<Pair<Double, String>>()
            for (raw in rawCandidates) {
                if (raw.isNaN() || raw.isInfinite()) continue
                if (isPeriodicTrig && (raw < -1e-6 || raw > 2 * Math.PI + 1e-6)) continue
                val (snappedVal, label) = formatExactLabel(raw, hasExpOrLog, isPeriodicTrig, knownBases, exactValidator)
                if (isPeriodicTrig && (snappedVal < -1e-6 || snappedVal > 2 * Math.PI + 1e-6)) continue
                if (snappedPoints.none { abs(it.first - snappedVal) < 1e-5 }) {
                    snappedPoints.add(Pair(snappedVal, label))
                }
            }
            snappedPoints.sortBy { it.first }

            // Strict Post-Filter for Real Critical Points
            val filteredPoints = snappedPoints.filter { (v, _) ->
                val inDomain = isInOdz(v)
                val odzLeft = isInOdz(v - 1e-3)
                val odzRight = isInOdz(v + 1e-3)

                val isTrueOdzBoundary = (!inDomain && (odzLeft || odzRight)) || (odzLeft != odzRight)

                val isTrueZeroOrSignChange = if (inDomain) {
                    val sVal = evalSignedLog(diffAst, v, solveEngine)
                    if (sVal.isValid && sVal.isEffectivelyZero(relThreshold = -12.0)) {
                        true
                    } else {
                        val sLeft = evalSignedLog(diffAst, v - 1e-3, solveEngine)
                        val sRight = evalSignedLog(diffAst, v + 1e-3, solveEngine)
                        sLeft.isValid && sRight.isValid && odzLeft && odzRight && (sLeft.sign * sRight.sign < 0)
                    }
                } else false

                isTrueOdzBoundary || isTrueZeroOrSignChange
            }

            // Evaluate critical points
            val criticalPoints = filteredPoints.map { (v, label) ->
                val inDomain = isInOdz(v)
                val isOdzExcluded = !inDomain
                val sVal = if (inDomain) evalSignedLog(diffAst, v, solveEngine) else SignedLog.INVALID

                val isIncluded: Boolean
                val reason: String

                if (isOdzExcluded) {
                    isIncluded = false
                    reason = getOdzExclusionReason(v, uniqueOdzRules, solveEngine)
                } else {
                    val isZero = sVal.isValid && sVal.isEffectivelyZero(relThreshold = -12.0)
                    val matchesOp = when (op) {
                        IneqOp.LE -> isZero || sVal.sign < 0
                        IneqOp.GE -> isZero || sVal.sign > 0
                        IneqOp.LT -> !isZero && sVal.sign < 0
                        IneqOp.GT -> !isZero && sVal.sign > 0
                    }
                    isIncluded = matchesOp
                    reason = if (isZero) "Нуль функции: f($label) = 0"
                    else if (isIncluded) "Удовлетворяет условию ${op.symbol}"
                    else "Не удовлетворяет условию ${op.symbol}"
                }

                CriticalPoint(
                    value = v,
                    label = label,
                    isIncluded = isIncluded,
                    isOdzExcluded = isOdzExcluded,
                    reason = reason
                )
            }

            // Partition into open intervals
            val intervals = mutableListOf<IntervalSegment>()
            val numCp = criticalPoints.size

            if (isPeriodicTrig) {
                // Periodic partition on [0, 2π]
                val pointsOnPeriod = criticalPoints.filter { it.value in -1e-6..(2 * Math.PI + 1e-6) }
                val numP = pointsOnPeriod.size
                if (numP == 0) {
                    val sampleX = Math.PI
                    val inDomain = isInOdz(sampleX)
                    val sVal = if (inDomain) evalSignedLog(diffAst, sampleX, solveEngine) else SignedLog.INVALID
                    val sign = if (!inDomain || !sVal.isValid) 0 else if (sVal.isEffectivelyZero(relThreshold = -20.0)) 0 else sVal.sign
                    val isSol = if (!inDomain || !sVal.isValid) false else when (op) {
                        IneqOp.GT -> sign > 0
                        IneqOp.GE -> sign >= 0
                        IneqOp.LT -> sign < 0
                        IneqOp.LE -> sign <= 0
                    }
                    val sampleLabel = formatExactLabel(sampleX, hasExpOrLog, true, knownBases).second
                    val sampleValueLabel = if (!inDomain || !sVal.isValid) "—" else formatSampleValue(sVal)
                    intervals.add(
                        IntervalSegment(
                            start = 0.0,
                            end = 2 * Math.PI,
                            startLabel = "0",
                            endLabel = "2π",
                            startClosed = false,
                            endClosed = false,
                            sign = sign,
                            isInDomain = inDomain,
                            isSolution = isSol,
                            sampleLabel = sampleLabel,
                            sampleValueLabel = sampleValueLabel
                        )
                    )
                } else {
                    val framePoints = mutableListOf<Pair<Double, String>>()
                    if (pointsOnPeriod.none { abs(it.value - 0.0) < 1e-6 }) {
                        framePoints.add(Pair(0.0, "0"))
                    }
                    pointsOnPeriod.forEach { framePoints.add(Pair(it.value, it.label)) }
                    if (pointsOnPeriod.none { abs(it.value - 2 * Math.PI) < 1e-6 }) {
                        framePoints.add(Pair(2 * Math.PI, "2π"))
                    }
                    framePoints.sortBy { it.first }

                    for (i in 0 until framePoints.size - 1) {
                        val a = framePoints[i].first
                        val b = framePoints[i + 1].first
                        if (b - a < 1e-6) continue
                        val startLabel = framePoints[i].second
                        val endLabel = framePoints[i + 1].second

                        val sampleX = (a + b) / 2.0
                        val inDomain = isInOdz(sampleX)
                        val sVal = if (inDomain) evalSignedLog(diffAst, sampleX, solveEngine) else SignedLog.INVALID

                        val sign: Int
                        val isSol: Boolean

                        if (!inDomain || !sVal.isValid) {
                            sign = 0
                            isSol = false
                        } else {
                            val isZero = sVal.isEffectivelyZero(relThreshold = -20.0)
                            if (isZero) {
                                sign = 0
                                isSol = (op == IneqOp.LE || op == IneqOp.GE)
                            } else if (sVal.sign > 0) {
                                sign = 1
                                isSol = (op == IneqOp.GT || op == IneqOp.GE)
                            } else {
                                sign = -1
                                isSol = (op == IneqOp.LT || op == IneqOp.LE)
                            }
                        }

                        val sampleLabel = formatExactLabel(sampleX, hasExpOrLog, true, knownBases).second
                        val sampleValueLabel = if (!inDomain || !sVal.isValid) "—" else formatSampleValue(sVal)

                        val startCp = criticalPoints.find { abs(it.value - a) < 1e-6 }
                        val endCp = criticalPoints.find { abs(it.value - b) < 1e-6 }
                        val startClosed = startCp?.isIncluded == true
                        val endClosed = endCp?.isIncluded == true

                        intervals.add(
                            IntervalSegment(
                                start = a,
                                end = b,
                                startLabel = startLabel,
                                endLabel = endLabel,
                                startClosed = startClosed,
                                endClosed = endClosed,
                                sign = sign,
                                isInDomain = inDomain,
                                isSolution = isSol,
                                sampleLabel = sampleLabel,
                                sampleValueLabel = sampleValueLabel
                            )
                        )
                    }
                }
            } else {
                // Non-periodic partition on (-∞, +∞)
                for (i in 0..numCp) {
                    val startVal = if (i == 0) Double.NEGATIVE_INFINITY else criticalPoints[i - 1].value
                    val endVal = if (i == numCp) Double.POSITIVE_INFINITY else criticalPoints[i].value

                    val startLabel = if (i == 0) "-∞" else criticalPoints[i - 1].label
                    val endLabel = if (i == numCp) "+∞" else criticalPoints[i].label

                    // Select clean interior sample point
                    val sampleX = selectCleanSamplePoint(startVal, endVal)
                    val inDomain = isInOdz(sampleX)
                    val sVal = if (inDomain) evalSignedLog(diffAst, sampleX, solveEngine) else SignedLog.INVALID

                    val sign: Int
                    val isSolution: Boolean

                    if (!inDomain || !sVal.isValid) {
                        sign = 0
                        isSolution = false
                    } else {
                        val isZero = sVal.isEffectivelyZero(relThreshold = -20.0)
                        if (isZero) {
                            sign = 0
                            isSolution = (op == IneqOp.LE || op == IneqOp.GE)
                        } else if (sVal.sign > 0) {
                            sign = 1
                            isSolution = (op == IneqOp.GT || op == IneqOp.GE)
                        } else {
                            sign = -1
                            isSolution = (op == IneqOp.LT || op == IneqOp.LE)
                        }
                    }

                    val sampleLabel = if (inDomain) formatExactLabel(sampleX, hasExpOrLog, false, knownBases).second else ""
                    val sampleValueLabel = if (!inDomain || !sVal.isValid) "—" else formatSampleValue(sVal)

                    intervals.add(
                        IntervalSegment(
                            start = startVal,
                            end = endVal,
                            startLabel = startLabel,
                            endLabel = endLabel,
                            startClosed = false,
                            endClosed = false,
                            sign = sign,
                            isInDomain = inDomain,
                            isSolution = isSolution,
                            sampleLabel = sampleLabel,
                            sampleValueLabel = sampleValueLabel
                        )
                    )
                }
            }

            val answerFormula = buildAnswerFormula(intervals, criticalPoints, isPeriodicTrig)

            // Step cards generation
            val detailedSteps = mutableListOf<CasStep>()
            val odzStrings = uniqueOdzRules.map { it.exprString }.distinct()
            val odzConstraintList = if (odzStrings.isNotEmpty()) odzStrings else listOf("x ∈ ℝ")

            val odzFormula = if (odzStrings.isNotEmpty()) {
                val bullets = odzStrings.joinToString("\n") { "• $it" }
                val excludedByOdz = criticalPoints.filter { it.isOdzExcluded }
                if (excludedByOdz.isNotEmpty()) {
                    bullets + "\nТочки, исключённые по ОДЗ: " + excludedByOdz.joinToString(", ") { "x ≠ ${it.label}" }
                } else {
                    bullets
                }
            } else {
                "x ∈ ℝ"
            }

            detailedSteps.add(
                CasStep(
                    badge = "ОДЗ",
                    title = "Область допустимых значений",
                    formula = odzFormula,
                    explanation = if (odzStrings.isNotEmpty()) "Найдём ограничения на переменную x." else "Ограничений на область допустимых значений нет."
                )
            )

            val step1Explanation = if (isPeriodicTrig) {
                "Перенесём все слагаемые в левую часть. Неравенство является 2π-периодическим; решение проводится на основном периоде [0; 2π)."
            } else {
                "Перенесём все слагаемые в левую часть неравенства."
            }

            val step1Formula = if (rightRaw.trim() == "0" || (rightAst is Node.Num && rightAst.value.num == 0L)) {
                "${leftAst.toExprString()} ${op.symbol} 0"
            } else {
                "${diffAst.toExprString()} ${op.symbol} 0"
            }

            detailedSteps.add(
                CasStep(
                    badge = "Шаг 1",
                    title = "Приведение неравенства к виду f(x) ⋚ 0",
                    formula = step1Formula,
                    explanation = step1Explanation
                )
            )

            val cpLines = criticalPoints.map { cp ->
                when {
                    cp.isOdzExcluded -> "• x = ${cp.label}  —  вне ОДЗ (○ выколота)"
                    cp.isIncluded -> "• x = ${cp.label}  —  нуль функции (● входит)"
                    else -> "• x = ${cp.label}  —  нуль функции, строгое неравенство (○ выколота)"
                }
            }
            val cpFormula = if (cpLines.isNotEmpty()) cpLines.joinToString("\n") else "Нет критических точек"

            detailedSteps.add(
                CasStep(
                    badge = "Шаг 2",
                    title = "Нули функции и особые точки",
                    formula = cpFormula,
                    explanation = "Найдём нули функции f(x) = 0 и точки разрыва / границы ОДЗ."
                )
            )

            val step3Lines = mutableListOf<String>()

            if (isPeriodicTrig) {
                for (seg in intervals) {
                    if (!seg.isInDomain) continue
                    val signStr = if (seg.sign > 0) "«+»" else if (seg.sign < 0) "«−»" else "«0»"
                    val sampleProof = if (seg.sampleLabel.isNotEmpty() && seg.sampleValueLabel.isNotEmpty()) {
                        "  (f(${seg.sampleLabel}) = ${seg.sampleValueLabel})"
                    } else ""
                    val checkMark = if (seg.isSolution) "  ✓" else ""
                    step3Lines.add("• (${seg.startLabel}; ${seg.endLabel})  →  знак $signStr$sampleProof$checkMark")
                }
            } else {
                val numCp = criticalPoints.size
                for (i in 0..numCp) {
                    val seg = intervals[i]
                    if (seg.isInDomain) {
                        val signStr = if (seg.sign > 0) "«+»" else if (seg.sign < 0) "«−»" else "«0»"
                        val sampleProof = if (seg.sampleLabel.isNotEmpty() && seg.sampleValueLabel.isNotEmpty()) {
                            "  (f(${seg.sampleLabel}) = ${seg.sampleValueLabel})"
                        } else ""
                        val checkMark = if (seg.isSolution) "  ✓" else ""
                        step3Lines.add("• (${seg.startLabel}; ${seg.endLabel})  →  знак $signStr$sampleProof$checkMark")
                    }

                    if (i < numCp) {
                        val cp = criticalPoints[i]
                        val prevIsSol = if (i > 0) intervals[i - 1].isSolution else false
                        val nextIsSol = intervals[i + 1].isSolution
                        if (cp.isIncluded && !prevIsSol && !nextIsSol) {
                            step3Lines.add("• {${cp.label}}  →  f(${cp.label}) = 0  ✓")
                        }
                    }
                }
            }

            val intervalsFormula = if (step3Lines.isNotEmpty()) {
                step3Lines.joinToString("\n")
            } else {
                "Нет допустимых интервалов"
            }

            detailedSteps.add(
                CasStep(
                    badge = "Шаг 3",
                    title = "Определение знаков на интервалах",
                    formula = intervalsFormula,
                    explanation = "Методом пробных точек определим знак функции f(x) на каждом интервале."
                )
            )

            detailedSteps.add(
                CasStep(
                    badge = "Ответ",
                    title = "Записываем ответ",
                    formula = answerFormula,
                    explanation = "Итоговое множество решений неравенства."
                )
            )

            return InequalityResult(
                answerFormula = answerFormula,
                intervals = intervals,
                criticalPoints = criticalPoints,
                odzConstraints = odzConstraintList,
                detailedSteps = detailedSteps,
                errorMessage = null,
                isPeriodic = isPeriodicTrig
            )
        } catch (e: Exception) {
            return InequalityResult(
                answerFormula = "Ошибка",
                intervals = emptyList(),
                criticalPoints = emptyList(),
                odzConstraints = emptyList(),
                detailedSteps = emptyList(),
                errorMessage = "Ошибка при решении неравенства: ${e.message ?: "неверный синтаксис"}",
                isPeriodic = false
            )
        }
    }

    private fun selectCleanSamplePoint(startVal: Double, endVal: Double): Double {
        if (startVal.isInfinite() && endVal.isInfinite()) return 0.0
        if (startVal.isInfinite()) {
            val endFloor = floor(endVal)
            val candidateInt = if (endFloor < endVal - 1e-4) endFloor else endFloor - 1.0
            return candidateInt
        }
        if (endVal.isInfinite()) {
            val startCeil = ceil(startVal)
            val candidateInt = if (startCeil > startVal + 1e-4) startCeil else startCeil + 1.0
            return candidateInt
        }

        val a = startVal + 1e-4
        val b = endVal - 1e-4
        val mid = (startVal + endVal) / 2.0

        val minInt = ceil(a).toLong()
        val maxInt = floor(b).toLong()
        if (minInt <= maxInt) {
            var bestInt = minInt
            var minDiff = abs(minInt.toDouble() - mid)
            for (k in minInt..maxInt) {
                val diff = abs(k.toDouble() - mid)
                if (diff < minDiff) {
                    minDiff = diff
                    bestInt = k
                }
            }
            return bestInt.toDouble()
        }

        // Try half-integers k + 0.5
        val minHalf = ceil(a * 2.0).toLong()
        val maxHalf = floor(b * 2.0).toLong()
        if (minHalf <= maxHalf) {
            var bestHalf = minHalf.toDouble() / 2.0
            var minDiff = abs(bestHalf - mid)
            for (k in minHalf..maxHalf) {
                val valHalf = k.toDouble() / 2.0
                val diff = abs(valHalf - mid)
                if (diff < minDiff) {
                    minDiff = diff
                    bestHalf = valHalf
                }
            }
            return bestHalf
        }

        return mid
    }

    private fun containsTrigFunc(node: Node): Boolean {
        return when (node) {
            is Node.Func -> node.fn.lowercase() in setOf("sin", "cos", "tan", "asin", "acos", "atan")
            is Node.Add -> containsTrigFunc(node.l) || containsTrigFunc(node.r)
            is Node.Sub -> containsTrigFunc(node.l) || containsTrigFunc(node.r)
            is Node.Mul -> containsTrigFunc(node.l) || containsTrigFunc(node.r)
            is Node.Div -> containsTrigFunc(node.l) || containsTrigFunc(node.r)
            is Node.Pow -> containsTrigFunc(node.base) || containsTrigFunc(node.exp)
            is Node.Sqrt -> containsTrigFunc(node.inner)
            is Node.LogBase -> containsTrigFunc(node.base) || containsTrigFunc(node.arg)
            else -> false
        }
    }

    private fun allVarsInTrig(node: Node, insideTrig: Boolean = false): Boolean {
        return when (node) {
            is Node.Variable -> if (node.name == "x") insideTrig else true
            is Node.Func -> {
                val isTrig = node.fn.lowercase() in setOf("sin", "cos", "tan", "asin", "acos", "atan")
                allVarsInTrig(node.arg, insideTrig || isTrig)
            }
            is Node.Add -> allVarsInTrig(node.l, insideTrig) && allVarsInTrig(node.r, insideTrig)
            is Node.Sub -> allVarsInTrig(node.l, insideTrig) && allVarsInTrig(node.r, insideTrig)
            is Node.Mul -> allVarsInTrig(node.l, insideTrig) && allVarsInTrig(node.r, insideTrig)
            is Node.Div -> allVarsInTrig(node.l, insideTrig) && allVarsInTrig(node.r, insideTrig)
            is Node.Pow -> allVarsInTrig(node.base, insideTrig) && allVarsInTrig(node.exp, insideTrig)
            is Node.Sqrt -> allVarsInTrig(node.inner, insideTrig)
            is Node.LogBase -> allVarsInTrig(node.base, insideTrig) && allVarsInTrig(node.arg, insideTrig)
            else -> true
        }
    }

    private fun addBaseToKnown(baseNode: Node, knownBases: MutableSet<Long>) {
        if (baseNode is Node.Num) {
            val bVal = baseNode.value.toDouble()
            if (abs(bVal - round(bVal)) < 1e-6) {
                val bLong = round(bVal).toLong()
                if (bLong >= 2L) knownBases.add(bLong)
            } else if (bVal > 0 && abs(1.0 / bVal - round(1.0 / bVal)) < 1e-6) {
                val bLong = round(1.0 / bVal).toLong()
                if (bLong >= 2L) knownBases.add(bLong)
            }
        } else if (baseNode is Node.Div) {
            if (baseNode.l is Node.Num && baseNode.l.value.num == 1L && baseNode.r is Node.Num) {
                val denVal = baseNode.r.value.toDouble()
                if (abs(denVal - round(denVal)) < 1e-6) {
                    val bLong = round(denVal).toLong()
                    if (bLong >= 2L) knownBases.add(bLong)
                }
            }
        }
    }

    private fun collectExpAndLogInfo(node: Node, knownBases: MutableSet<Long>): Boolean {
        var hasExpOrLog = false
        when (node) {
            is Node.LogBase -> {
                hasExpOrLog = true
                addBaseToKnown(node.base, knownBases)
                if (collectExpAndLogInfo(node.base, knownBases)) hasExpOrLog = true
                if (collectExpAndLogInfo(node.arg, knownBases)) hasExpOrLog = true
            }
            is Node.Func -> {
                if (node.fn.lowercase() in setOf("ln", "log")) {
                    hasExpOrLog = true
                }
                if (collectExpAndLogInfo(node.arg, knownBases)) hasExpOrLog = true
            }
            is Node.Pow -> {
                hasExpOrLog = true
                addBaseToKnown(node.base, knownBases)
                if (collectExpAndLogInfo(node.base, knownBases)) hasExpOrLog = true
                if (collectExpAndLogInfo(node.exp, knownBases)) hasExpOrLog = true
            }
            is Node.Add -> {
                val a = collectExpAndLogInfo(node.l, knownBases)
                val b = collectExpAndLogInfo(node.r, knownBases)
                hasExpOrLog = a || b
            }
            is Node.Sub -> {
                val a = collectExpAndLogInfo(node.l, knownBases)
                val b = collectExpAndLogInfo(node.r, knownBases)
                hasExpOrLog = a || b
            }
            is Node.Mul -> {
                val a = collectExpAndLogInfo(node.l, knownBases)
                val b = collectExpAndLogInfo(node.r, knownBases)
                hasExpOrLog = a || b
            }
            is Node.Div -> {
                val a = collectExpAndLogInfo(node.l, knownBases)
                val b = collectExpAndLogInfo(node.r, knownBases)
                hasExpOrLog = a || b
            }
            is Node.Sqrt -> {
                hasExpOrLog = collectExpAndLogInfo(node.inner, knownBases)
            }
            else -> {}
        }
        return hasExpOrLog
    }

    private fun findIneqOperator(str: String): OpMatch? {
        val multiOps = listOf(
            "<=" to IneqOp.LE,
            ">=" to IneqOp.GE,
            "≤" to IneqOp.LE,
            "≥" to IneqOp.GE
        )
        for ((sym, op) in multiOps) {
            val idx = str.indexOf(sym)
            if (idx != -1) return OpMatch(op, idx, sym.length)
        }
        val singleOps = listOf(
            "<" to IneqOp.LT,
            ">" to IneqOp.GT
        )
        for ((sym, op) in singleOps) {
            val idx = str.indexOf(sym)
            if (idx != -1) return OpMatch(op, idx, sym.length)
        }
        return null
    }

    fun normalizeInput(raw: String): String {
        val s1 = raw.replace("х", "x").replace("Х", "x")
            .replace("÷", "/")
            .replace("×", "*")
            .replace("·", "*")
            .replace("⋅", "*")
            .replace("∙", "*")
            .replace("−", "-")

        val commaRegex = Regex("""(?<=\d),(?=\d)""")
        val s2 = commaRegex.replace(s1, ".")
        val s3 = transformTrigPowers(s2)
        val s4 = transformAbs(s3)
        val s5 = transformLogShorthand(s4)
        return s5
    }

    fun transformTrigPowers(rawInput: String): String {
        var s = rawInput.replace(Regex("""\btg\b"""), "tan")
            .replace(Regex("""\bctg\((.*?)\)""")) { match -> "(1/tan(${match.groupValues[1]}))" }
            .replace(Regex("""\bctg\b"""), "(1/tan)")
            .replace(Regex("""\bcot\((.*?)\)""")) { match -> "(1/tan(${match.groupValues[1]}))" }

        val funcs = listOf("sin", "cos", "tan")
        for (fn in funcs) {
            val pattern = Regex("""\b$fn\^(\d+|\{[^}]+\}|\([^)]+\))\s*\(""")
            var match = pattern.find(s)
            while (match != null) {
                val expRaw = match.groupValues[1].removePrefix("{").removeSuffix("}")
                val fnStart = match.range.first
                val parenStart = match.range.last // index of '('
                val (argStr, parenEnd) = extractMatchingParens(s, parenStart)
                if (argStr.isNotEmpty()) {
                    val replacement = "($fn($argStr))^$expRaw"
                    s = s.substring(0, fnStart) + replacement + s.substring(parenEnd)
                    match = pattern.find(s)
                } else {
                    break
                }
            }
        }
        return s
    }

    fun transformAbs(input: String): String {
        val sb = StringBuilder()
        var i = 0
        val n = input.length
        var openCount = 0
        while (i < n) {
            val c = input[i]
            if (c == '|') {
                val prevChar = sb.trimEnd().lastOrNull()
                val needsImplicitMul = prevChar != null && (prevChar.isDigit() || prevChar.isLetter() || prevChar == ')' || prevChar == 'π')
                if (openCount == 0) {
                    if (needsImplicitMul) sb.append("*abs(") else sb.append("abs(")
                    openCount++
                } else {
                    var nextChar: Char? = null
                    for (j in (i + 1) until n) {
                        if (!input[j].isWhitespace()) {
                            nextChar = input[j]
                            break
                        }
                    }
                    val remainingBars = input.substring(i + 1).count { it == '|' }
                    val isNestedOpen = (prevChar == null || prevChar in "+-*/(^<>=≤≥,") ||
                            (needsImplicitMul && remainingBars >= 2 && nextChar != null && (nextChar.isLetterOrDigit() || nextChar == '(' || nextChar == '√'))

                    if (isNestedOpen) {
                        if (needsImplicitMul) sb.append("*abs(") else sb.append("abs(")
                        openCount++
                    } else {
                        sb.append(")")
                        openCount--
                    }
                }
            } else {
                sb.append(c)
            }
            i++
        }
        while (openCount > 0) {
            sb.append(")")
            openCount--
        }
        return sb.toString()
    }

    fun transformLogShorthand(input: String): String {
        val sb = StringBuilder()
        var i = 0
        val n = input.length
        while (i < n) {
            if (input.startsWith("log_", i)) {
                val logStart = i
                i += 4
                if (i >= n) {
                    sb.append(input.substring(logStart))
                    break
                }
                val (baseStr, nextI) = extractGroupOrToken(input, i)
                i = nextI
                var expStr: String? = null
                if (i < n && input[i] == '^') {
                    i++
                    val (exp, expNextI) = extractGroupOrToken(input, i)
                    expStr = exp
                    i = expNextI
                }
                if (i < n && input[i] == '(') {
                    val (argStr, argNextI) = extractMatchingParens(input, i)
                    i = argNextI
                    val logExpr = "log($baseStr, $argStr)"
                    if (expStr != null) {
                        sb.append("($logExpr)^$expStr")
                    } else {
                        sb.append(logExpr)
                    }
                } else {
                    sb.append(input.substring(logStart, i))
                }
            } else {
                sb.append(input[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun extractGroupOrToken(s: String, startIndex: Int): Pair<String, Int> {
        var i = startIndex
        if (i >= s.length) return Pair("", i)
        val openChar = s[i]
        if (openChar == '{' || openChar == '(') {
            val closeChar = if (openChar == '{') '}' else ')'
            var depth = 1
            i++
            val startInner = i
            while (i < s.length && depth > 0) {
                if (s[i] == openChar) depth++
                else if (s[i] == closeChar) depth--
                i++
            }
            val inner = if (depth == 0) s.substring(startInner, i - 1) else s.substring(startInner)
            return Pair(inner, i)
        } else {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '.')) {
                i++
            }
            return Pair(s.substring(start, i), i)
        }
    }

    private fun extractMatchingParens(s: String, startIndex: Int): Pair<String, Int> {
        var i = startIndex
        if (i >= s.length || s[i] != '(') return Pair("", i)
        var depth = 1
        i++
        val startInner = i
        while (i < s.length && depth > 0) {
            if (s[i] == '(') depth++
            else if (s[i] == ')') depth--
            i++
        }
        val inner = if (depth == 0) s.substring(startInner, i - 1) else s.substring(startInner)
        return Pair(inner, i)
    }

    private fun collectOdzRules(node: Node, rules: MutableList<OdzRule>) {
        when (node) {
            is Node.Div -> {
                rules.add(OdzRule(node.r, OdzType.DENOMINATOR_NOT_ZERO, "${node.r.toExprString()} ≠ 0", "${node.r.toExprString()} ≠ 0"))
                collectOdzRules(node.l, rules)
                collectOdzRules(node.r, rules)
            }
            is Node.Sqrt -> {
                rules.add(OdzRule(node.inner, OdzType.SQRT_NON_NEGATIVE, "${node.inner.toExprString()} ≥ 0", "${node.inner.toExprString()} ≥ 0"))
                collectOdzRules(node.inner, rules)
            }
            is Node.Func -> {
                when (node.fn.lowercase()) {
                    "sqrt" -> {
                        rules.add(OdzRule(node.arg, OdzType.SQRT_NON_NEGATIVE, "${node.arg.toExprString()} ≥ 0", "${node.arg.toExprString()} ≥ 0"))
                    }
                    "ln", "log" -> {
                        rules.add(OdzRule(node.arg, OdzType.LOG_ARG_POSITIVE, "${node.arg.toExprString()} > 0", "${node.arg.toExprString()} > 0"))
                    }
                }
                collectOdzRules(node.arg, rules)
            }
            is Node.LogBase -> {
                rules.add(OdzRule(node.arg, OdzType.LOG_ARG_POSITIVE, "${node.arg.toExprString()} > 0", "${node.arg.toExprString()} > 0"))
                rules.add(OdzRule(node.base, OdzType.LOG_BASE_POSITIVE, "${node.base.toExprString()} > 0", "${node.base.toExprString()} > 0"))
                rules.add(OdzRule(node.base, OdzType.LOG_BASE_NOT_ONE, "${node.base.toExprString()} ≠ 1", "${node.base.toExprString()} ≠ 1"))
                collectOdzRules(node.base, rules)
                collectOdzRules(node.arg, rules)
            }
            is Node.Add -> {
                collectOdzRules(node.l, rules)
                collectOdzRules(node.r, rules)
            }
            is Node.Sub -> {
                collectOdzRules(node.l, rules)
                collectOdzRules(node.r, rules)
            }
            is Node.Mul -> {
                collectOdzRules(node.l, rules)
                collectOdzRules(node.r, rules)
            }
            is Node.Pow -> {
                collectOdzRules(node.base, rules)
                collectOdzRules(node.exp, rules)
            }
            else -> {}
        }
    }

    private fun collectAbsInnerNodes(node: Node, list: MutableList<Node>) {
        when (node) {
            is Node.Func -> {
                if (node.fn.lowercase() == "abs") {
                    list.add(node.arg)
                }
                collectAbsInnerNodes(node.arg, list)
            }
            is Node.Add -> { collectAbsInnerNodes(node.l, list); collectAbsInnerNodes(node.r, list) }
            is Node.Sub -> { collectAbsInnerNodes(node.l, list); collectAbsInnerNodes(node.r, list) }
            is Node.Mul -> { collectAbsInnerNodes(node.l, list); collectAbsInnerNodes(node.r, list) }
            is Node.Div -> { collectAbsInnerNodes(node.l, list); collectAbsInnerNodes(node.r, list) }
            is Node.Pow -> { collectAbsInnerNodes(node.base, list); collectAbsInnerNodes(node.exp, list) }
            is Node.Sqrt -> { collectAbsInnerNodes(node.inner, list) }
            is Node.LogBase -> { collectAbsInnerNodes(node.base, list); collectAbsInnerNodes(node.arg, list) }
            else -> {}
        }
    }

    private fun collectTopLevelMultiplicativeFactors(node: Node, list: MutableList<Node>) {
        list.add(node)
        when (node) {
            is Node.Mul -> {
                collectTopLevelMultiplicativeFactors(node.l, list)
                collectTopLevelMultiplicativeFactors(node.r, list)
            }
            is Node.Div -> {
                collectTopLevelMultiplicativeFactors(node.l, list)
                collectTopLevelMultiplicativeFactors(node.r, list)
            }
            else -> {}
        }
    }

    private fun bisectionRoot(node: Node, a: Double, b: Double, casEngine: CasEngine): Double {
        var low = min(a, b)
        var high = max(a, b)
        var sa = evalSignedLog(node, low, casEngine)
        var sb = evalSignedLog(node, high, casEngine)
        if (!sa.isValid || !sb.isValid) return (low + high) / 2.0
        if (sa.sign == 0 || sa.isEffectivelyZero()) return low
        if (sb.sign == 0 || sb.isEffectivelyZero()) return high
        for (_iter in 0..65) {
            val mid = (low + high) / 2.0
            val smid = evalSignedLog(node, mid, casEngine)
            if (!smid.isValid || smid.sign == 0 || smid.isEffectivelyZero() || (high - low) < 1e-12) return mid
            if (sa.sign * smid.sign <= 0) {
                high = mid
                sb = smid
            } else {
                low = mid
                sa = smid
            }
        }
        return (low + high) / 2.0
    }

    private fun bisectionOdzBoundary(a: Double, b: Double, isInOdzCheck: (Double) -> Boolean): Double {
        var low = min(a, b)
        var high = max(a, b)
        val stateLow = isInOdzCheck(low)
        for (_iter in 0..65) {
            val mid = (low + high) / 2.0
            if ((high - low) < 1e-12) return mid
            val stateMid = isInOdzCheck(mid)
            if (stateMid == stateLow) {
                low = mid
            } else {
                high = mid
            }
        }
        return (low + high) / 2.0
    }

    private fun goldenSectionMin(node: Node, a: Double, b: Double, casEngine: CasEngine): Double {
        var l = min(a, b)
        var r = max(a, b)
        val phi = (sqrt(5.0) - 1.0) / 2.0
        var c = r - phi * (r - l)
        var d = l + phi * (r - l)
        var sc = evalSignedLog(node, c, casEngine)
        var sd = evalSignedLog(node, d, casEngine)
        var rc = if (sc.isValid) sc.relativeLog else Double.POSITIVE_INFINITY
        var rd = if (sd.isValid) sd.relativeLog else Double.POSITIVE_INFINITY
        for (_iter in 0..60) {
            if ((r - l) < 1e-12) break
            if (rc < rd) {
                r = d
                d = c
                rd = rc
                c = r - phi * (r - l)
                sc = evalSignedLog(node, c, casEngine)
                rc = if (sc.isValid) sc.relativeLog else Double.POSITIVE_INFINITY
            } else {
                l = c
                c = d
                rc = rd
                d = l + phi * (r - l)
                sd = evalSignedLog(node, d, casEngine)
                rd = if (sd.isValid) sd.relativeLog else Double.POSITIVE_INFINITY
            }
        }
        return (l + r) / 2.0
    }

    private fun recognizeQuadraticSurd(x: Double, validator: ((Double) -> Boolean)? = null): String? {
        var bestScore = Double.MAX_VALUE
        var bestResult: String? = null

        for (A in 1..12) {
            for (B in -40..40) {
                val rawC = -(A.toDouble() * x * x + B.toDouble() * x)
                val C = round(rawC).toLong()
                if (abs(C) > 500L) continue

                val scale = A.toDouble() * x * x + abs(B.toDouble() * x) + abs(C.toDouble()) + 1.0
                if (abs(rawC - C.toDouble()) > 1e-7 * scale) continue

                val D = B.toLong() * B.toLong() - 4L * A.toLong() * C
                if (D <= 1L) continue

                val rt = sqrt(D.toDouble()).toLong()
                if (rt * rt == D) continue

                if (gcd(A.toLong(), gcd(abs(B.toLong()), abs(C))) != 1L) continue

                val sqrtD = sqrt(D.toDouble())
                val rPlus = (-B.toDouble() + sqrtD) / (2.0 * A.toDouble())
                val rMinus = (-B.toDouble() - sqrtD) / (2.0 * A.toDouble())

                val diffPlus = abs(rPlus - x)
                val diffMinus = abs(rMinus - x)

                val (exactRoot, isPlus) = if (diffPlus < diffMinus) {
                    Pair(rPlus, true)
                } else {
                    Pair(rMinus, false)
                }

                if (abs(exactRoot - x) >= 1e-9) continue

                if (validator != null && !validator(exactRoot)) continue

                val score = A.toDouble() * 20.0 + abs(B.toDouble()) * 3.0 + sqrt(abs(C.toDouble()))
                if (score < bestScore) {
                    var k = 1L
                    var m = D
                    var f = 2L
                    while (f * f <= m) {
                        val fSq = f * f
                        while (m % fSq == 0L) {
                            k *= f
                            m /= fSq
                        }
                        f++
                    }

                    val p = -B.toLong()
                    val q = 2L * A.toLong()

                    val g = gcd(abs(p), gcd(k, q))
                    val p1 = p / g
                    val k1 = k / g
                    val q1 = q / g

                    val radStr = if (k1 == 1L) "√$m" else "${k1}√$m"
                    val resStr = if (p1 == 0L) {
                        val signedRad = if (isPlus) radStr else "-$radStr"
                        if (q1 == 1L) signedRad else "$signedRad/$q1"
                    } else {
                        val op = if (isPlus) " + " else " - "
                        val numStr = "$p1$op$radStr"
                        if (q1 == 1L) numStr else "($numStr)/$q1"
                    }

                    bestScore = score
                    bestResult = resStr
                }
            }
        }
        return bestResult
    }

    private fun recognizeHigherRoot(x: Double, validator: ((Double) -> Boolean)? = null): String? {
        val maxN = if (validator != null) 6 else 4
        val maxD = if (validator != null) 6 else 4
        val minS = if (validator != null) -15 else -10
        val maxS = if (validator != null) 15 else 10
        val maxM = if (validator != null) 500 else 64
        val maxDen = if (validator != null) 16 else 8
        val tol = if (validator != null) 1e-8 else 1e-9

        var bestScore = Double.MAX_VALUE
        var bestResult: String? = null

        for (n in 3..maxN) {
            for (d in 1..maxD) {
                for (s in minS..maxS) {
                    val u = d.toDouble() * x - s.toDouble()
                    if (abs(u) < 1e-6 || abs(u) > 50.0) continue
                    val sign = if (u >= 0.0) 1 else -1
                    val absU = abs(u)
                    val powVal = absU.pow(n.toDouble())

                    // Case A (Integer radicand m >= 2):
                    if (powVal >= 1.5 && powVal <= maxM + 0.5) {
                        val m = round(powVal).toLong()
                        if (m in 2L..maxM.toLong()) {
                            val exactRoot = (s.toDouble() + sign * m.toDouble().pow(1.0 / n.toDouble())) / d.toDouble()
                            if (abs(exactRoot - x) < tol && (validator == null || validator(exactRoot))) {
                                val score = n * 15.0 + d * 12.0 + abs(s) * 2.0 + m * 0.5
                                if (score < bestScore) {
                                    val formatted = simplifyAndFormatHigherRoot(n, d, s, sign, m, isCaseB = false)
                                    if (formatted != null) {
                                        bestScore = score
                                        bestResult = formatted
                                    }
                                }
                            }
                        }
                    }

                    // Case B (Unit reciprocal 1 / den):
                    if (powVal > 0.04 && powVal < 0.9) {
                        val inv = 1.0 / powVal
                        val den = round(inv).toLong()
                        if (den in 2L..maxDen.toLong()) {
                            val exactRoot = (s.toDouble() + sign * (1.0 / den.toDouble()).pow(1.0 / n.toDouble())) / d.toDouble()
                            if (abs(exactRoot - x) < tol && (validator == null || validator(exactRoot))) {
                                val score = n * 15.0 + d * 12.0 + abs(s) * 2.0 + den * 0.5 + 5.0
                                if (score < bestScore) {
                                    val formatted = simplifyAndFormatHigherRoot(n, d, s, sign, den, isCaseB = true)
                                    if (formatted != null) {
                                        bestScore = score
                                        bestResult = formatted
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return bestResult
    }

    private fun simplifyAndFormatHigherRoot(
        n: Int,
        d: Int,
        s: Int,
        sign: Int,
        rawM: Long,
        isCaseB: Boolean
    ): String? {
        var curN = n
        var curM = rawM

        // 1. Reduce root degree if rawM is an exact power base^p with g = gcd(curN, p) > 1
        for (p in curN downTo 2) {
            val baseCandidate = round(curM.toDouble().pow(1.0 / p.toDouble())).toLong()
            if (baseCandidate >= 2L) {
                var pVal = 1L
                var overflow = false
                repeat(p) {
                    pVal *= baseCandidate
                    if (pVal > curM * 2) { overflow = true }
                }
                if (!overflow && pVal == curM) {
                    val g = gcd(curN.toLong(), p.toLong()).toInt()
                    if (g > 1) {
                        val newN = curN / g
                        if (newN == 1) return null
                        curN = newN
                        val newP = p / g
                        var newM = 1L
                        repeat(newP) { newM *= baseCandidate }
                        curM = newM
                        break
                    }
                }
            }
        }

        // 2. Extract maximal curN-th power factor k^curN dividing curM
        var k = 1L
        var r = curM
        var f = 2L
        while (true) {
            val fPow = f.toDouble().pow(curN.toDouble()).toLong()
            if (fPow > r) break
            while (r % fPow == 0L) {
                k *= f
                r /= fPow
            }
            f++
        }
        if (r < 2L) return null

        // 3. Reduce (s, k, d) by g = gcd(abs(s), gcd(k, d)) when Case A
        val (s1, k1, d1) = if (!isCaseB) {
            val g = gcd(abs(s.toLong()), gcd(k, d.toLong()))
            Triple(s.toLong() / g, k / g, d.toLong() / g)
        } else {
            Triple(s.toLong(), k, d.toLong())
        }

        // 4. Map degree superscript: 2 -> "", 3 -> "³", 4 -> "⁴", 5 -> "⁵", 6 -> "⁶"
        val sup = when (curN) {
            3 -> "³"
            4 -> "⁴"
            5 -> "⁵"
            6 -> "⁶"
            else -> ""
        }

        // 5. Format radical cleanly:
        val rad = if (!isCaseB) {
            "${if (k1 == 1L) "" else k1}${sup}√$r"
        } else {
            if (k1 == 1L) "1/${sup}√$r" else "1/($k1${sup}√$r)"
        }

        // 6. Combine with shift s1 and denominator d1:
        val num = when {
            s1 == 0L -> if (sign > 0) rad else "-$rad"
            sign > 0 && s1 < 0L -> "$rad - ${-s1}"
            sign > 0 -> "$rad + $s1"
            sign < 0 && s1 > 0L -> "$s1 - $rad"
            else -> "$s1 - $rad"
        }

        return if (d1 == 1L) num else if (s1 == 0L) "$num/$d1" else "($num)/$d1"
    }

    fun formatExactLabel(
        x: Double,
        hasExpOrLog: Boolean = false,
        isPeriodicTrig: Boolean = false,
        knownBases: Set<Long> = emptySet(),
        exactValidator: ((Double) -> Boolean)? = null
    ): Pair<Double, String> {
        // 1. Zero & Integers
        if (abs(x) < 1e-9) return Pair(0.0, "0")

        val rounded = round(x)
        if (abs(x - rounded) < 1e-6) {
            val l = rounded.toLong()
            return Pair(l.toDouble(), l.toString())
        }

        // 2. Small Rational Fractions
        for (q in 2..64) {
            val p = round(x * q).toLong()
            if (abs(x - p.toDouble() / q) < 1e-6) {
                val g = gcd(abs(p), q.toLong())
                val pSimple = p / g
                val qSimple = q.toLong() / g
                if (qSimple == 1L) return Pair(pSimple.toDouble(), pSimple.toString())
                val label = "$pSimple/$qSimple"
                val valSnapped = pSimple.toDouble() / qSimple
                return Pair(valSnapped, label)
            }
        }

        // 3. π-Fractions (ONLY when isPeriodicTrig == true)
        if (isPeriodicTrig) {
            val ratio = x / Math.PI
            for (q in listOf(1, 2, 3, 4, 6, 12)) {
                val p = round(ratio * q).toLong()
                if (abs(x - p.toDouble() * Math.PI / q) < 1e-3) {
                    val g = if (p == 0L) 1L else gcd(abs(p), q.toLong())
                    val pSimple = p / g
                    val qSimple = q.toLong() / g
                    val valSnapped = pSimple.toDouble() * Math.PI / qSimple
                    val label = when {
                        pSimple == 0L -> "0"
                        qSimple == 1L -> if (pSimple == 1L) "π" else if (pSimple == -1L) "-π" else "${pSimple}π"
                        else -> if (pSimple == 1L) "π/$qSimple" else if (pSimple == -1L) "-π/$qSimple" else "${pSimple}π/$qSimple"
                    }
                    return Pair(valSnapped, label)
                }
            }
        }

        // 4. Exact Logarithms (ONLY when hasExpOrLog == true)
        if (hasExpOrLog && abs(x) < 20.0) {
            val derivedBases = mutableSetOf<Long>()
            for (kb in knownBases) {
                if (kb >= 2L) {
                    derivedBases.add(kb)
                    for (k in 2..6) {
                        val r = round(kb.toDouble().pow(1.0 / k)).toLong()
                        if (r >= 2L) {
                            var p = 1L
                            repeat(k) { p *= r }
                            if (p == kb) derivedBases.add(r)
                        }
                    }
                }
            }
            val defaultBases = listOf(2L, 3L, 5L, 6L, 10L)
            val bases = (derivedBases + defaultBases).filter { it >= 2L }.distinct().sorted()

            for (b in bases) {
                val v = b.toDouble().pow(x)
                if (!v.isNaN() && !v.isInfinite()) {
                    val roundN = round(v).toLong()
                    if (roundN in 2L..120L) {
                        val targetX = ln(roundN.toDouble()) / ln(b.toDouble())
                        if (abs(x - targetX) < 1e-5) {
                            var isPower = false
                            var temp = roundN
                            while (temp % b == 0L && temp > 1L) {
                                temp /= b
                            }
                            if (temp == 1L) isPower = true

                            if (!isPower) {
                                var simpleB = b
                                var simpleN = roundN
                                for (k in 6 downTo 2) {
                                    val c = round(simpleB.toDouble().pow(1.0 / k)).toLong()
                                    val a = round(simpleN.toDouble().pow(1.0 / k)).toLong()
                                    if (c >= 2L && a >= 1L) {
                                        var pb = 1L; var pn = 1L
                                        repeat(k) { pb *= c; pn *= a }
                                        if (pb == simpleB && pn == simpleN) {
                                            simpleB = c
                                            simpleN = a
                                        }
                                    }
                                }
                                val label = "log" + toSubscript(simpleB) + "($simpleN)"
                                return Pair(targetX, label)
                            }
                        }
                    } else {
                        for (q in 2..16) {
                            val pVal = round(v * q).toLong()
                            if (pVal in 1L..30L) {
                                val g = gcd(pVal, q.toLong())
                                val pSim = pVal / g
                                val qSim = q.toLong() / g
                                if (qSim > 1L) {
                                    val targetX = ln(pSim.toDouble() / qSim.toDouble()) / ln(b.toDouble())
                                    if (abs(x - targetX) < 1e-5) {
                                        var simpleB = b
                                        var simpleP = pSim
                                        var simpleQ = qSim
                                        for (k in 6 downTo 2) {
                                            val c = round(simpleB.toDouble().pow(1.0 / k)).toLong()
                                            val a = round(simpleP.toDouble().pow(1.0 / k)).toLong()
                                            val d = round(simpleQ.toDouble().pow(1.0 / k)).toLong()
                                            if (c >= 2L && a >= 1L && d >= 1L) {
                                                var pb = 1L; var pn = 1L; var pd = 1L
                                                repeat(k) { pb *= c; pn *= a; pd *= d }
                                                if (pb == simpleB && pn == simpleP && pd == simpleQ) {
                                                    simpleB = c
                                                    simpleP = a
                                                    simpleQ = d
                                                }
                                            }
                                        }
                                        val label = "log" + toSubscript(simpleB) + "($simpleP/$simpleQ)"
                                        return Pair(targetX, label)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Check base e (ln)
            val vE = exp(x)
            if (!vE.isNaN() && !vE.isInfinite()) {
                val roundNE = round(vE).toLong()
                if (roundNE in 2L..120L) {
                    val targetXE = ln(roundNE.toDouble())
                    if (abs(x - targetXE) < 1e-5 && roundNE != 1L) {
                        val label = "ln($roundNE)"
                        return Pair(targetXE, label)
                    }
                } else {
                    for (q in 2..16) {
                        val pVal = round(vE * q).toLong()
                        if (pVal in 1L..30L) {
                            val g = gcd(pVal, q.toLong())
                            val pSim = pVal / g
                            val qSim = q.toLong() / g
                            if (qSim > 1L) {
                                val targetXE = ln(pSim.toDouble() / qSim.toDouble())
                                if (abs(x - targetXE) < 1e-5) {
                                    val label = "ln($pSim/$qSim)"
                                    return Pair(targetXE, label)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Quadratic Irrationals ((p ± k√m) / q)
        val surdLabel = recognizeQuadraticSurd(x, exactValidator)
        if (surdLabel != null) {
            return Pair(x, surdLabel)
        }

        // 5b. Higher-Order Radicals (ⁿ√m)
        val higherRootLabel = recognizeHigherRoot(x, exactValidator)
        if (higherRootLabel != null) {
            return Pair(x, higherRootLabel)
        }

        // 6. Fallback
        val norm1 = Fraction.formatDoubleNorm1(x)
        return Pair(x, norm1)
    }

    private fun toSubscript(n: Long): String {
        val subscriptDigits = mapOf(
            '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
            '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉'
        )
        return n.toString().map { subscriptDigits[it] ?: it }.joinToString("")
    }

    private fun gcd(a: Long, b: Long): Long {
        var x = a
        var y = b
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return abs(x)
    }

    private fun getOdzExclusionReason(x: Double, rules: List<OdzRule>, casEngine: CasEngine): String {
        for (rule in rules) {
            val sl = evalSignedLog(rule.node, x, casEngine)
            if (!sl.isValid) return "Вне области допустимых значений (ОДЗ)"
            val valNode = sl.toDouble()
            when (rule.type) {
                OdzType.DENOMINATOR_NOT_ZERO -> if (sl.isEffectivelyZero() || abs(valNode) <= 1e-9) return "Знаменатель равен 0 (${rule.exprString})"
                OdzType.SQRT_NON_NEGATIVE -> if (sl.sign < 0 && !sl.isEffectivelyZero()) return "Подкоренное выражение < 0 (${rule.exprString})"
                OdzType.LOG_ARG_POSITIVE -> if (sl.sign <= 0 || sl.isEffectivelyZero()) return "Аргумент логарифма ≤ 0 (${rule.exprString})"
                OdzType.LOG_BASE_POSITIVE -> if (sl.sign <= 0 || sl.isEffectivelyZero()) return "Основание логарифма ≤ 0 (${rule.exprString})"
                OdzType.LOG_BASE_NOT_ONE -> if (sl.sign > 0 && abs(sl.logAbs) <= 1e-9) return "Основание логарифма равно 1 (${rule.exprString})"
            }
        }
        return "Вне области допустимых значений (ОДЗ)"
    }

    fun buildAnswerFormula(
        intervals: List<IntervalSegment>,
        criticalPoints: List<CriticalPoint>,
        isPeriodicTrig: Boolean = false
    ): String {
        val solutionParts = mutableListOf<String>()

        if (isPeriodicTrig) {
            val solIntervals = intervals.filter { it.isSolution }
            if (solIntervals.isEmpty()) return "x ∈ ∅"

            val periodicParts = solIntervals.map { seg ->
                val startP = if (seg.startLabel == "0") "2πk" else "${seg.startLabel} + 2πk"
                val endP = if (seg.endLabel == "0") "2πk" else "${seg.endLabel} + 2πk"
                val leftBr = if (seg.startClosed) "[" else "("
                val rightBr = if (seg.endClosed) "]" else ")"
                "$leftBr$startP; $endP$rightBr"
            }
            return "x ∈ " + periodicParts.joinToString(" ∪ ") + ", k ∈ ℤ"
        }

        val n = criticalPoints.size

        if (n == 0) {
            val mainInt = intervals.firstOrNull()
            return if (mainInt?.isSolution == true) "x ∈ (-∞; +∞)" else "x ∈ ∅"
        }

        var i = 0
        while (i <= n) {
            val curInt = intervals[i]
            if (curInt.isSolution) {
                val segStartClosed = i > 0 && criticalPoints[i - 1].isIncluded
                val segStartLabel = if (segStartClosed) criticalPoints[i - 1].label else curInt.startLabel

                var curEndLabel = curInt.endLabel
                var curEndClosed = false

                var k = i
                while (k < n) {
                    val cp = criticalPoints[k]
                    val nextInt = intervals[k + 1]
                    if (cp.isIncluded && nextInt.isSolution) {
                        k++
                        curEndLabel = nextInt.endLabel
                        curEndClosed = false
                    } else if (cp.isIncluded) {
                        curEndLabel = cp.label
                        curEndClosed = true
                        k++
                        break
                    } else {
                        curEndLabel = cp.label
                        curEndClosed = false
                        break
                    }
                }
                val leftBracket = if (segStartClosed) "[" else "("
                val rightBracket = if (curEndClosed) "]" else ")"
                solutionParts.add("$leftBracket$segStartLabel; $curEndLabel$rightBracket")
                i = k + 1
            } else {
                if (i < n) {
                    val cp = criticalPoints[i]
                    val prevIsSol = if (i > 0) intervals[i - 1].isSolution else false
                    val nextIsSol = intervals[i + 1].isSolution

                    if (cp.isIncluded && !prevIsSol && !nextIsSol) {
                        solutionParts.add("{${cp.label}}")
                        i++
                    } else {
                        i++
                    }
                } else {
                    i++
                }
            }
        }

        if (solutionParts.isEmpty()) return "x ∈ ∅"
        return "x ∈ " + solutionParts.joinToString(" ∪ ")
    }
}
