package com.example.calculator

import com.example.calculator.CasEngine.Fraction
import com.example.calculator.CasEngine.Node
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
                if (x.isNaN()) return false
                val snapTrigX = if (isPeriodicTrig) {
                    val mult = round(x / Math.PI)
                    if (abs(x - mult * Math.PI) < 1e-11) mult * Math.PI else x
                } else x

                for (rule in uniqueOdzRules) {
                    var v = solveEngine.evalNodeAtX(rule.node, snapTrigX)
                    if (isPeriodicTrig && rule.node.toExprString().contains("sin")) {
                        val mult = round(snapTrigX / Math.PI)
                        if (abs(snapTrigX - mult * Math.PI) < 1e-11) {
                            v = 0.0
                        }
                    }
                    if (v.isNaN()) return false
                    when (rule.type) {
                        OdzType.DENOMINATOR_NOT_ZERO -> if (abs(v) <= 1e-12) return false
                        OdzType.SQRT_NON_NEGATIVE -> if (v < -1e-11) return false
                        OdzType.LOG_ARG_POSITIVE -> if (v <= 1e-12) return false
                        OdzType.LOG_BASE_POSITIVE -> if (v <= 1e-12) return false
                        OdzType.LOG_BASE_NOT_ONE -> if (abs(v - 1.0) <= 1e-12) return false
                    }
                }
                val diffVal = solveEngine.evalNodeAtX(diffAst, snapTrigX)
                return !diffVal.isNaN()
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
                    for (k in 0 until compScanSteps) {
                        val x1 = k * compStep
                        val x2 = x1 + compStep
                        val y1 = solveEngine.evalNodeAtX(node, x1)
                        val y2 = solveEngine.evalNodeAtX(node, x2)
                        if (!y1.isNaN() && !y2.isNaN() && y1 * y2 <= 0) {
                            val root = bisectionRoot(node, x1, x2, solveEngine)
                            rawCandidates.add(root)
                        } else {
                            val minX = goldenSectionMin(node, x1, x2, solveEngine)
                            if (minX > min(x1, x2) + 1e-5 && minX < max(x1, x2) - 1e-5) {
                                val yMin = solveEngine.evalNodeAtX(node, minX)
                                if (!yMin.isNaN() && abs(yMin) < 1e-7) {
                                    rawCandidates.add(minX)
                                }
                            }
                        }
                    }
                }

                // 2. Multi-scale scan on diffAst on [0, 2π]
                val periodicSteps = 4000
                val periodicStep = (2 * Math.PI) / periodicSteps
                for (k in 0 until periodicSteps) {
                    val x1 = k * periodicStep
                    val x2 = x1 + periodicStep

                    val odz1 = isInOdz(x1)
                    val odz2 = isInOdz(x2)

                    if (odz1 != odz2) {
                        val bnd = bisectionOdzBoundary(x1, x2, ::isInOdz)
                        rawCandidates.add(bnd)
                    }

                    if (odz1 && odz2) {
                        val y1 = solveEngine.evalNodeAtX(diffAst, x1)
                        val y2 = solveEngine.evalNodeAtX(diffAst, x2)
                        if (y1 * y2 <= 0) {
                            val r = bisectionRoot(diffAst, x1, x2, solveEngine)
                            rawCandidates.add(r)
                        } else {
                            val minX = goldenSectionMin(diffAst, x1, x2, solveEngine)
                            if (minX > min(x1, x2) + 1e-5 && minX < max(x1, x2) - 1e-5) {
                                val yMin = solveEngine.evalNodeAtX(diffAst, minX)
                                if (!yMin.isNaN() && abs(yMin) < 1e-7) {
                                    rawCandidates.add(minX)
                                }
                            }
                        }
                    }
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
                        if (!valSol.isNaN() && !valSol.isInfinite()) {
                            rawCandidates.add(valSol)
                        }
                    }

                    // Component scan on [-40, 40]
                    val compScanSteps = 1600
                    val compStep = 80.0 / compScanSteps
                    for (k in 0 until compScanSteps) {
                        val x1 = -40.0 + k * compStep
                        val x2 = x1 + compStep
                        val y1 = solveEngine.evalNodeAtX(node, x1)
                        val y2 = solveEngine.evalNodeAtX(node, x2)
                        if (!y1.isNaN() && !y2.isNaN() && y1 * y2 <= 0) {
                            val root = bisectionRoot(node, x1, x2, solveEngine)
                            rawCandidates.add(root)
                        } else {
                            val minX = goldenSectionMin(node, x1, x2, solveEngine)
                            if (minX > min(x1, x2) + 1e-5 && minX < max(x1, x2) - 1e-5) {
                                val yMin = solveEngine.evalNodeAtX(node, minX)
                                if (!yMin.isNaN() && abs(yMin) < 1e-7) {
                                    rawCandidates.add(minX)
                                }
                            }
                        }
                    }
                }

                // 2. Multi-scale scan on diffAst
                val denseSteps = 16000
                val denseStep = 80.0 / denseSteps
                for (k in 0 until denseSteps) {
                    val x1 = -40.0 + k * denseStep
                    val x2 = x1 + denseStep

                    val odz1 = isInOdz(x1)
                    val odz2 = isInOdz(x2)

                    if (odz1 != odz2) {
                        val bnd = bisectionOdzBoundary(x1, x2, ::isInOdz)
                        rawCandidates.add(bnd)
                    }

                    if (odz1 && odz2) {
                        val y1 = solveEngine.evalNodeAtX(diffAst, x1)
                        val y2 = solveEngine.evalNodeAtX(diffAst, x2)
                        if (y1 * y2 <= 0) {
                            val r = bisectionRoot(diffAst, x1, x2, solveEngine)
                            rawCandidates.add(r)
                        } else {
                            val minX = goldenSectionMin(diffAst, x1, x2, solveEngine)
                            if (minX > min(x1, x2) + 1e-5 && minX < max(x1, x2) - 1e-5) {
                                val yMin = solveEngine.evalNodeAtX(diffAst, minX)
                                if (!yMin.isNaN() && abs(yMin) < 1e-7) {
                                    rawCandidates.add(minX)
                                }
                            }
                        }
                    }
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
                        val y1 = solveEngine.evalNodeAtX(diffAst, x1)
                        val y2 = solveEngine.evalNodeAtX(diffAst, x2)
                        if (y1 * y2 <= 0) {
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

            // Deduplicate and snap critical points
            val snappedPoints = mutableListOf<Pair<Double, String>>()
            for (raw in rawCandidates) {
                if (raw.isNaN() || raw.isInfinite()) continue
                if (isPeriodicTrig && (raw < -1e-6 || raw > 2 * Math.PI + 1e-6)) continue
                val (snappedVal, label) = formatExactLabel(raw, hasExpOrLog, isPeriodicTrig, knownBases)
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
                    val fVal = solveEngine.evalNodeAtX(diffAst, v)
                    if (!fVal.isNaN() && abs(fVal) < 1e-5) {
                        true
                    } else {
                        val fLeft = solveEngine.evalNodeAtX(diffAst, v - 1e-3)
                        val fRight = solveEngine.evalNodeAtX(diffAst, v + 1e-3)
                        !fLeft.isNaN() && !fRight.isNaN() && odzLeft && odzRight &&
                                ((fLeft > 1e-7 && fRight < -1e-7) || (fLeft < -1e-7 && fRight > 1e-7))
                    }
                } else false

                isTrueOdzBoundary || isTrueZeroOrSignChange
            }

            // Evaluate critical points
            val criticalPoints = filteredPoints.map { (v, label) ->
                val inDomain = isInOdz(v)
                val isOdzExcluded = !inDomain
                val fVal = if (inDomain) solveEngine.evalNodeAtX(diffAst, v) else Double.NaN

                val isIncluded: Boolean
                val reason: String

                if (isOdzExcluded) {
                    isIncluded = false
                    reason = getOdzExclusionReason(v, uniqueOdzRules, solveEngine)
                } else {
                    val fAbs = abs(fVal)
                    val matchesOp = when (op) {
                        IneqOp.LE -> fVal <= 1e-7
                        IneqOp.GE -> fVal >= -1e-7
                        IneqOp.LT -> fVal < -1e-7
                        IneqOp.GT -> fVal > 1e-7
                    }
                    isIncluded = matchesOp
                    reason = if (fAbs < 1e-7) "Нуль функции: f($label) = 0"
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
                    val fVal = if (inDomain) solveEngine.evalNodeAtX(diffAst, sampleX) else Double.NaN
                    val sign = if (!inDomain || fVal.isNaN()) 0 else if (fVal > 1e-7) 1 else if (fVal < -1e-7) -1 else 0
                    val isSol = if (!inDomain || fVal.isNaN()) false else when (op) {
                        IneqOp.GT -> sign > 0
                        IneqOp.GE -> sign >= 0
                        IneqOp.LT -> sign < 0
                        IneqOp.LE -> sign <= 0
                    }
                    val sampleLabel = formatExactLabel(sampleX, hasExpOrLog, true, knownBases).second
                    val sampleValueLabel = if (fVal.isNaN()) "—" else Fraction.formatDoubleNorm1(fVal)
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
                        val fVal = if (inDomain) solveEngine.evalNodeAtX(diffAst, sampleX) else Double.NaN

                        val sign = if (!inDomain || fVal.isNaN()) 0 else if (fVal > 1e-7) 1 else if (fVal < -1e-7) -1 else 0
                        val isSol = if (!inDomain || fVal.isNaN()) false else when (op) {
                            IneqOp.GT -> sign > 0
                            IneqOp.GE -> sign >= 0
                            IneqOp.LT -> sign < 0
                            IneqOp.LE -> sign <= 0
                        }

                        val sampleLabel = formatExactLabel(sampleX, hasExpOrLog, true, knownBases).second
                        val sampleValueLabel = if (!inDomain || fVal.isNaN()) "—" else Fraction.formatDoubleNorm1(fVal)

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
                    val fVal = if (inDomain) solveEngine.evalNodeAtX(diffAst, sampleX) else Double.NaN

                    val sign: Int
                    val isSolution: Boolean

                    if (!inDomain || fVal.isNaN()) {
                        sign = 0
                        isSolution = false
                    } else {
                        if (abs(fVal) < 1e-7) {
                            sign = 0
                            isSolution = (op == IneqOp.LE || op == IneqOp.GE)
                        } else if (fVal > 1e-7) {
                            sign = 1
                            isSolution = (op == IneqOp.GT || op == IneqOp.GE)
                        } else {
                            sign = -1
                            isSolution = (op == IneqOp.LT || op == IneqOp.LE)
                        }
                    }

                    val sampleLabel = if (inDomain) formatExactLabel(sampleX, hasExpOrLog, false, knownBases).second else ""
                    val sampleValueLabel = if (!inDomain || fVal.isNaN()) "—" else if (fVal.isInfinite()) (if (fVal > 0) "+∞" else "-∞") else Fraction.formatDoubleNorm1(fVal)

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

    private fun collectExpAndLogInfo(node: Node, knownBases: MutableSet<Long>): Boolean {
        var hasExpOrLog = false
        when (node) {
            is Node.LogBase -> {
                hasExpOrLog = true
                if (node.base is Node.Num) {
                    val bVal = node.base.value.toDouble()
                    if (abs(bVal - round(bVal)) < 1e-6) {
                        val bLong = round(bVal).toLong()
                        if (bLong >= 2L) knownBases.add(bLong)
                    }
                }
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
                if (node.base is Node.Num) {
                    val bVal = node.base.value.toDouble()
                    if (abs(bVal - round(bVal)) < 1e-6) {
                        val bLong = round(bVal).toLong()
                        if (bLong >= 2L) knownBases.add(bLong)
                    }
                }
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
        var fa = casEngine.evalNodeAtX(node, low)
        var fb = casEngine.evalNodeAtX(node, high)
        if (fa.isNaN() || fb.isNaN()) return (low + high) / 2.0
        if (abs(fa) < 1e-12) return low
        if (abs(fb) < 1e-12) return high
        for (_iter in 0..60) {
            val mid = (low + high) / 2.0
            val fmid = casEngine.evalNodeAtX(node, mid)
            if (fmid.isNaN() || abs(fmid) < 1e-12 || (high - low) < 1e-12) return mid
            if (fa * fmid <= 0) {
                high = mid
                fb = fmid
            } else {
                low = mid
                fa = fmid
            }
        }
        return (low + high) / 2.0
    }

    private fun bisectionOdzBoundary(a: Double, b: Double, isInOdzCheck: (Double) -> Boolean): Double {
        var low = min(a, b)
        var high = max(a, b)
        val stateLow = isInOdzCheck(low)
        for (_iter in 0..60) {
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
        var fc = abs(casEngine.evalNodeAtX(node, c).let { if (it.isNaN()) 1e9 else it })
        var fd = abs(casEngine.evalNodeAtX(node, d).let { if (it.isNaN()) 1e9 else it })
        for (_iter in 0..50) {
            if ((r - l) < 1e-12) break
            if (fc < fd) {
                r = d
                d = c
                fd = fc
                c = r - phi * (r - l)
                fc = abs(casEngine.evalNodeAtX(node, c).let { if (it.isNaN()) 1e9 else it })
            } else {
                l = c
                c = d
                fc = fd
                d = l + phi * (r - l)
                fd = abs(casEngine.evalNodeAtX(node, d).let { if (it.isNaN()) 1e9 else it })
            }
        }
        return (l + r) / 2.0
    }

    fun formatExactLabel(
        x: Double,
        hasExpOrLog: Boolean = false,
        isPeriodicTrig: Boolean = false,
        knownBases: Set<Long> = emptySet()
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

        // 4. Quadratic Irrationals ((p ± k√d) / r)
        for (r in 1..8) {
            for (p in -25..25) {
                val diff = r.toDouble() * x - p.toDouble()
                val sq = diff * diff
                val m = round(sq).toLong()
                if (m in 2L..200L && abs(sq - m.toDouble()) < 1e-5 * max(1.0, sq)) {
                    val sqrtM = round(sqrt(m.toDouble())).toLong()
                    if (sqrtM * sqrtM != m) { // m is NOT a perfect square
                        val signDiff = if (diff >= 0) 1.0 else -1.0
                        val calcX = (p.toDouble() + signDiff * sqrt(m.toDouble())) / r.toDouble()
                        if (abs(x - calcX) < 1e-6) {
                            var k = 1L
                            var d = m
                            for (factor in 14 downTo 2) {
                                val factorSq = (factor * factor).toLong()
                                if (d % factorSq == 0L) {
                                    k *= factor
                                    d /= factorSq
                                }
                            }
                            val kSign = k * (if (diff >= 0) 1L else -1L)
                            val g = gcd(gcd(abs(p.toLong()), abs(kSign)), r.toLong())
                            val pSim = p.toLong() / g
                            val kSim = kSign / g
                            val rSim = r.toLong() / g
                            val valSnapped = (pSim.toDouble() + kSim.toDouble() * sqrt(d.toDouble())) / rSim.toDouble()

                            val label: String
                            if (pSim == 0L) {
                                val radPart = if (kSim == 1L) "√$d" else if (kSim == -1L) "-√$d" else "${kSim}√$d"
                                label = if (rSim == 1L) radPart else "($radPart)/$rSim"
                            } else {
                                val radPart = if (kSim > 0) "+ " + (if (kSim == 1L) "√$d" else "${kSim}√$d")
                                else "- " + (if (kSim == -1L) "√$d" else "${-kSim}√$d")
                                label = if (rSim == 1L) "$pSim $radPart" else "($pSim $radPart)/$rSim"
                            }
                            return Pair(valSnapped, label)
                        }
                    }
                }
            }
        }

        // 5. Exact Logarithms (ONLY when hasExpOrLog == true)
        if (hasExpOrLog && abs(x) < 20.0) {
            val bases = (knownBases + listOf(2L, 3L, 5L, 10L)).filter { it >= 2L }.distinct()
            for (b in bases) {
                val v = b.toDouble().pow(x)
                if (!v.isNaN() && !v.isInfinite()) {
                    val roundN = round(v).toLong()
                    if (roundN in 2L..120L) {
                        val targetX = ln(roundN.toDouble()) / ln(b.toDouble())
                        if (abs(x - targetX) < 1e-6) {
                            var isPower = false
                            var temp = roundN
                            while (temp % b == 0L && temp > 1L) {
                                temp /= b
                            }
                            if (temp == 1L) isPower = true

                            if (!isPower) {
                                val label = "log" + toSubscript(b) + "($roundN)"
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
                                    if (abs(x - targetX) < 1e-6) {
                                        val label = "log" + toSubscript(b) + "($pSim/$qSim)"
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
                    if (abs(x - targetXE) < 1e-6 && roundNE != 1L) {
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
                                if (abs(x - targetXE) < 1e-6) {
                                    val label = "ln($pSim/$qSim)"
                                    return Pair(targetXE, label)
                                }
                            }
                        }
                    }
                }
            }
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
            val valNode = casEngine.evalNodeAtX(rule.node, x)
            when (rule.type) {
                OdzType.DENOMINATOR_NOT_ZERO -> if (abs(valNode) <= 1e-9) return "Знаменатель равен 0 (${rule.exprString})"
                OdzType.SQRT_NON_NEGATIVE -> if (valNode < -1e-9) return "Подкоренное выражение < 0 (${rule.exprString})"
                OdzType.LOG_ARG_POSITIVE -> if (valNode <= 1e-9) return "Аргумент логарифма ≤ 0 (${rule.exprString})"
                OdzType.LOG_BASE_POSITIVE -> if (valNode <= 1e-9) return "Основание логарифма ≤ 0 (${rule.exprString})"
                OdzType.LOG_BASE_NOT_ONE -> if (abs(valNode - 1.0) <= 1e-9) return "Основание логарифма равно 1 (${rule.exprString})"
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
