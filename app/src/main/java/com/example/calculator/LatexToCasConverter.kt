package com.example.calculator

/**
 * Converter from raw LaTeX (from OCR engines such as Pix2Text-MFR 1.5) into MB-CAL's
 * native 2D CasEngine / InequalitySolverEngine / CasioDisplayView syntax.
 */
object LatexToCasConverter {

    fun convert(rawLatex: String): ConvertedFormula {
        val warnings = mutableListOf<String>()
        val cleaned = preCleanup(rawLatex, warnings)
        val casExpr = translateLatex(cleaned, warnings)
        val target = recommendTarget(casExpr)
        return ConvertedFormula(
            rawLatex = rawLatex,
            casExpression = casExpr,
            recommendedTarget = target,
            warnings = warnings
        )
    }

    fun recommendTarget(casExpr: String): ScanTarget {
        val hasIneq = casExpr.any { it in listOf('≤', '≥', '<', '>') } ||
                casExpr.contains("<=") || casExpr.contains(">=")
        if (hasIneq) return ScanTarget.INEQUALITY

        val hasEquals = casExpr.contains("=")
        val trigFunctions = listOf("sin", "cos", "tan", "ctg", "asin", "acos", "atan")
        val hasTrig = trigFunctions.any { fn ->
            casExpr.contains(Regex("""\b$fn\b"""))
        }

        if (hasEquals && hasTrig) return ScanTarget.TRIG
        return ScanTarget.CALCULATOR
    }

    fun preCleanup(rawLatex: String, warnings: MutableList<String> = mutableListOf()): String {
        var s = rawLatex.trim()

        // Strip outer math delimiters: $$, $, \[, \], \(, \)
        if (s.startsWith("$$") && s.endsWith("$$") && s.length >= 4) {
            s = s.substring(2, s.length - 2).trim()
        }
        if (s.startsWith("\\[") && s.endsWith("\\]") && s.length >= 4) {
            s = s.substring(2, s.length - 2).trim()
        }
        if (s.startsWith("\\(") && s.endsWith("\\)") && s.length >= 4) {
            s = s.substring(2, s.length - 2).trim()
        }
        if (s.startsWith("$") && s.endsWith("$") && s.length >= 2 && !s.startsWith("$$")) {
            s = s.substring(1, s.length - 1).trim()
        }

        // Strip trailing sentence punctuation at end of formula
        while (s.endsWith(".") || s.endsWith(",") || s.endsWith(";") || s.endsWith(":")) {
            s = s.dropLast(1).trim()
        }

        // Strip LaTeX layout commands
        val layoutCmds = listOf(
            "\\displaystyle", "\\textstyle", "\\limits",
            "\\qquad", "\\quad",
            "\\,", "\\;", "\\:", "\\!", "~",
            "\\Bigg", "\\bigg", "\\Big", "\\big"
        )
        for (cmd in layoutCmds) {
            s = s.replace(cmd, " ")
        }

        // Strip LaTeX environment wrappers
        s = s.replace(Regex("""\\begin\{(?:aligned|align\*?|gathered|equation\*?|matrix|pmatrix|bmatrix)\}"""), " ")
        s = s.replace(Regex("""\\end\{(?:aligned|align\*?|gathered|equation\*?|matrix|pmatrix|bmatrix)\}"""), " ")
        s = s.replace(Regex("""\\begin\{array\}\s*\{[^}]*\}"""), " ")
        s = s.replace(Regex("""\\end\{array\}"""), " ")

        // Multi-row array row filter before replacing \\ with spaces
        if (s.contains("\\\\")) {
            val rawRows = s.split("\\\\").map { it.trim() }.filter { it.isNotEmpty() }
            if (rawRows.size >= 2) {
                val garbageTokens = listOf("\\stackrel", "\\sqcup", "\\therefore", "\\lim", "\\infty", "\\neq", "\\mathcal")
                val relationTokens = listOf("=", "\\le", "\\ge", "\\leqslant", "\\geqslant", "<", ">")

                fun hasGarbage(r: String) = garbageTokens.any { r.contains(it) }
                fun hasRelation(r: String) = relationTokens.any { r.contains(it) }

                val cleanFormulaRow = rawRows.firstOrNull { hasRelation(it) && !hasGarbage(it) }
                if (cleanFormulaRow != null && rawRows.any { hasGarbage(it) }) {
                    s = cleanFormulaRow
                }
            }
        }

        // Replace alignment tabs & and LaTeX line breaks \\ with spaces
        s = s.replace("&", " ").replace("\\\\", " ")

        // Strip leading task labels
        s = stripLeadingTaskLabel(s)

        // Strip \left and \right layout modifiers (with negative lookahead to preserve \leftarrow, \rightarrow, etc.)
        s = s.replace(Regex("""\\left(?![a-zA-Z])\s*"""), "")
        s = s.replace(Regex("""\\right(?![a-zA-Z])\s*"""), "")

        // Strip legacy TeX font and size declaration commands
        val legacyFontRegex = Regex("""\\(?:bf|rm|it|tt|sf|sl|sc|cal|mit|boldmath|unboldmath|tiny|scriptsize|footnotesize|small|normalsize|large|Large|LARGE|huge|Huge|scriptstyle|scriptscriptstyle|mathnormal|mathstrut|protect|relax)(?![a-zA-Z])""")
        s = s.replace(legacyFontRegex, " ")

        // Unwrap text and font commands using brace-aware helper
        s = unwrapTextCommandsBraceAware(s)

        // Remove empty brace groups {} until stable
        val emptyBraces = Regex("""\{\s*\}""")
        while (emptyBraces.containsMatchIn(s)) {
            s = emptyBraces.replace(s, "")
        }

        // Decimal comma normalization inside numbers: e.g. 0,5 -> 0.5, {,} -> .
        s = s.replace("{,}", ".")
        s = s.replace(Regex("""(?<=\d),(?=\d)"""), ".")

        // Recover handwritten log OCR misreads (10g_, 1og_, l0g_, I0g_, Iog_, log_ when followed by _)
        s = s.replace(Regex("""(?<![a-zA-Z0-9\\])(?:1\s*0\s*g|1\s*o\s*g|l\s*0\s*g|I\s*0\s*g|I\s*o\s*g|l\s*o\s*g)\s*_"""), Regex.escapeReplacement("\\log_"))

        // Cyrillic x normalization
        s = s.replace("х", "x").replace("Х", "x")

        return s.trim()
    }

    private fun translateLatex(latex: String, warnings: MutableList<String>): String {
        val sb = StringBuilder()
        var i = 0
        val n = latex.length
        var absOpenCount = 0

        while (i < n) {
            val c = latex[i]

            if (c.isWhitespace()) {
                i++
                continue
            }

            if (c == '\\') {
                i++
                if (i >= n) break
                val nameSb = StringBuilder()
                while (i < n && latex[i].isLetter()) {
                    nameSb.append(latex[i])
                    i++
                }
                val cmd = nameSb.toString()

                if (cmd.isEmpty()) {
                    if (i < n) {
                        val sym = latex[i]
                        i++
                        when (sym) {
                            '{' -> sb.append("(")
                            '}' -> sb.append(")")
                            '|' -> {
                                sb.append("|")
                                absOpenCount = if (absOpenCount == 0) 1 else 0
                            }
                            else -> sb.append(sym)
                        }
                    }
                    continue
                }

                when (cmd) {
                    "frac", "dfrac", "tfrac", "cfrac", "sfrac" -> {
                        val (num, i1) = parseArg(latex, i)
                        val (den, i2) = parseArg(latex, i1)
                        i = i2
                        val convNum = translateLatex(num, warnings)
                        val convDen = translateLatex(den, warnings)
                        appendImplicitMulIfNeeded(sb, absOpenCount)
                        sb.append("(($convNum)/($convDen))")
                    }
                    "sqrt" -> {
                        var currI = skipWhitespace(latex, i)
                        var deg: String? = null
                        if (currI < n && latex[currI] == '[') {
                            val (degStr, nextI) = parseBracketed(latex, currI)
                            deg = degStr
                            currI = nextI
                        }
                        val (inner, nextI) = parseArg(latex, currI)
                        i = nextI
                        val convInner = translateLatex(inner, warnings)
                        appendImplicitMulIfNeeded(sb, absOpenCount)
                        if (deg == null) {
                            sb.append("√($convInner)")
                        } else if (deg.trim() == "3") {
                            sb.append("cbrt($convInner)")
                        } else {
                            val convDeg = translateLatex(deg, warnings)
                            sb.append("($convInner)^(1/($convDeg))")
                        }
                    }
                    "log" -> {
                        val comp = parseLogComponents(latex, i, warnings)
                        i = comp.nextIdx
                        val convArg = translateLatex(comp.arg, warnings)
                        appendImplicitMulIfNeeded(sb, absOpenCount)
                        if (comp.base != null) {
                            val convBase = translateLatex(comp.base, warnings)
                            val logExpr = "log(($convBase),($convArg))"
                            if (comp.power != null) {
                                val convPower = translateLatex(comp.power, warnings)
                                sb.append("($logExpr)^($convPower)")
                            } else {
                                sb.append(logExpr)
                            }
                        } else {
                            val logExpr = "log((10),($convArg))"
                            if (comp.power != null) {
                                val convPower = translateLatex(comp.power, warnings)
                                sb.append("($logExpr)^($convPower)")
                            } else {
                                sb.append(logExpr)
                            }
                        }
                    }
                    "ln" -> {
                        val comp = parseFnPowerAndArg(latex, i, warnings, isLogFunction = true)
                        i = comp.nextIdx
                        val convArg = translateLatex(comp.arg, warnings)
                        appendImplicitMulIfNeeded(sb, absOpenCount)
                        val fnExpr = "ln($convArg)"
                        if (comp.power != null) {
                            val convPower = translateLatex(comp.power, warnings)
                            sb.append("($fnExpr)^($convPower)")
                        } else {
                            sb.append(fnExpr)
                        }
                    }
                    "lg" -> {
                        val comp = parseFnPowerAndArg(latex, i, warnings, isLogFunction = true)
                        i = comp.nextIdx
                        val convArg = translateLatex(comp.arg, warnings)
                        appendImplicitMulIfNeeded(sb, absOpenCount)
                        val fnExpr = "log((10),($convArg))"
                        if (comp.power != null) {
                            val convPower = translateLatex(comp.power, warnings)
                            sb.append("($fnExpr)^($convPower)")
                        } else {
                            sb.append(fnExpr)
                        }
                    }
                    "sin", "cos", "tan", "tg", "cot", "ctg", "arcsin", "arccos", "arctan", "arctg" -> {
                        val stdName = when (cmd) {
                            "tg" -> "tan"
                            "cot" -> "ctg"
                            "arcsin" -> "asin"
                            "arccos" -> "acos"
                            "arctan", "arctg" -> "atan"
                            else -> cmd
                        }
                        val comp = parseFnPowerAndArg(latex, i, warnings, isLogFunction = false)
                        i = comp.nextIdx
                        val convArg = translateLatex(comp.arg, warnings)
                        appendImplicitMulIfNeeded(sb, absOpenCount)
                        val fnExpr = "$stdName($convArg)"
                        if (comp.power != null) {
                            val convPower = translateLatex(comp.power, warnings)
                            sb.append("($fnExpr)^($convPower)")
                        } else {
                            sb.append(fnExpr)
                        }
                    }
                    "le", "leq", "leqslant" -> sb.append(" ≤ ")
                    "ge", "geq", "geqslant" -> sb.append(" ≥ ")
                    "pm" -> sb.append(" ± ")
                    "mp" -> sb.append(" ∓ ")
                    "cdot", "times", "ast", "star" -> sb.append(" * ")
                    "circ" -> {
                        // Ignore cleanly
                    }
                    "div" -> sb.append(" / ")
                    "pi" -> {
                        appendImplicitMulIfNeeded(sb, absOpenCount)
                        sb.append("π")
                    }
                    "lvert", "rvert" -> {
                        sb.append("|")
                        absOpenCount = if (absOpenCount == 0) 1 else 0
                    }
                    "overset", "underset", "stackrel" -> {
                        val (_, i1) = parseArg(latex, i)
                        val (base, i2) = parseArg(latex, i1)
                        i = i2
                        val convBase = translateLatex(base, warnings)
                        appendImplicitMulBeforeExpr(sb, convBase, absOpenCount)
                        sb.append(convBase)
                    }
                    "overline", "underline", "overbrace", "underbrace", "bar", "hat", "vec", "tilde", "widetilde", "widehat" -> {
                        val (inner, i1) = parseArg(latex, i)
                        i = i1
                        val convInner = translateLatex(inner, warnings)
                        appendImplicitMulBeforeExpr(sb, convInner, absOpenCount)
                        sb.append(convInner)
                    }
                    "leftarrow", "rightarrow", "leftrightarrow", "Leftarrow", "Rightarrow", "to",
                    "longleftarrow", "longrightarrow", "longleftrightarrow", "Longleftarrow", "Longrightarrow", "Longleftrightarrow",
                    "mapsto", "uparrow", "downarrow" -> {
                        // Ignore decorative arrows cleanly
                    }
                    "noclubsuit", "clubsuit", "spadesuit", "heartsuit", "diamondsuit",
                    "blacksquare", "square", "Box", "checkmark", "bullet",
                    "triangle", "bigtriangleup", "bigtriangledown" -> {
                        warnings.add("Игнорирован декоративный символ: \\$cmd")
                    }
                    "dots", "cdots", "ldots", "vdots", "ddots" -> {
                        // Ignore dots cleanly
                    }
                    else -> {
                        warnings.add("Неизвестная команда LaTeX: \\$cmd")
                        sb.append(cmd)
                    }
                }
                continue
            }

            if (c == '^') {
                val (expStr, nextI) = parseArg(latex, i + 1)
                i = nextI
                val convExp = translateLatex(expStr, warnings)
                sb.append("^($convExp)")
                continue
            }

            if (c == '_') {
                val (subStr, nextI) = parseArg(latex, i + 1)
                i = nextI
                val convSub = translateLatex(subStr, warnings)
                sb.append("_$convSub")
                continue
            }

            if (c == '{') {
                val (inner, nextI) = parseBraced(latex, i)
                i = nextI
                val convInner = translateLatex(inner, warnings)
                if (convInner.isNotBlank()) {
                    val firstNonSpace = convInner.trimStart().firstOrNull()
                    val startsWithOp = firstNonSpace != null && firstNonSpace in listOf('=', '≤', '≥', '<', '>', '+', '-', '*', '/')
                    if (!startsWithOp) {
                        appendImplicitMulBeforeExpr(sb, convInner, absOpenCount)
                    }
                    if (needsParensForBareBraces(convInner)) {
                        sb.append("($convInner)")
                    } else {
                        sb.append(convInner)
                    }
                }
                continue
            }

            if (c == '[') {
                val (inner, nextI) = parseBracketed(latex, i)
                i = nextI
                val convInner = translateLatex(inner, warnings)
                appendImplicitMulIfNeeded(sb, absOpenCount)
                sb.append("($convInner)")
                continue
            }

            if (c == '|') {
                sb.append("|")
                absOpenCount = if (absOpenCount == 0) 1 else 0
                i++
                continue
            }

            if (c == '<') {
                if (i + 1 < n && latex[i + 1] == '=') {
                    sb.append(" ≤ ")
                    i += 2
                } else {
                    sb.append(" < ")
                    i++
                }
                continue
            }
            if (c == '>') {
                if (i + 1 < n && latex[i + 1] == '=') {
                    sb.append(" ≥ ")
                    i += 2
                } else {
                    sb.append(" > ")
                    i++
                }
                continue
            }

            appendImplicitMulIfNeededForChar(sb, c, absOpenCount)
            sb.append(c)
            i++
        }

        return cleanUpExpression(sb.toString())
    }

    private fun appendImplicitMulBeforeExpr(sb: StringBuilder, expr: String, absOpenCount: Int) {
        val trimmed = expr.trimStart()
        if (trimmed.isEmpty()) return
        val firstChar = trimmed.first()
        if (firstChar in listOf('=', '≤', '≥', '<', '>', '+', '-', '*', '/', '^', '_')) return
        val mathFns = listOf("sin(", "cos(", "tan(", "ctg(", "asin(", "acos(", "atan(", "log(", "ln(", "cbrt(")
        if (mathFns.any { trimmed.startsWith(it) }) {
            appendImplicitMulIfNeeded(sb, absOpenCount)
        } else {
            appendImplicitMulIfNeededForChar(sb, firstChar, absOpenCount)
        }
    }

    private fun appendImplicitMulIfNeeded(sb: StringBuilder, absOpenCount: Int) {
        val trimmed = sb.trimEnd()
        if (trimmed.isEmpty()) return
        val lastChar = trimmed.last()
        if (lastChar == '|' && absOpenCount == 1) {
            return
        }
        if (lastChar.isDigit() || lastChar.isLetter() || lastChar == ')' || lastChar == ']' || lastChar == '|' || lastChar == 'π') {
            sb.append(" * ")
        }
    }

    private fun appendImplicitMulIfNeededForChar(sb: StringBuilder, nextChar: Char, absOpenCount: Int) {
        val trimmed = sb.trimEnd()
        if (trimmed.isEmpty()) return
        val lastChar = trimmed.last()

        if (lastChar == '|' && absOpenCount == 1) {
            return
        }

        if (lastChar.isDigit() && (nextChar == '(' || nextChar == '√' || nextChar == 'π')) {
            sb.append(" * ")
        } else if ((lastChar == ')' || lastChar == ']' || lastChar == '|' || lastChar == 'π') &&
            (nextChar.isLetterOrDigit() || nextChar == '(' || nextChar == '√' || nextChar == 'π')) {
            sb.append(" * ")
        }
    }

    private fun cleanUpExpression(expr: String): String {
        var s = expr.replace(Regex("""\s+"""), " ").trim()
        s = s.replace(" * * ", " * ")
            .replace(" + + ", " + ")
            .replace(" - - ", " - ")
            .replace("( * ", "(")
            .replace(" * )", ")")
        return s
    }

    private fun isFullyGrouped(str: String): Boolean {
        val s = str.trim()
        if (s.isEmpty()) return false
        if ((s.startsWith("(") && s.endsWith(")")) || (s.startsWith("[") && s.endsWith("]"))) {
            var depth = 0
            val openChar = s.first()
            val closeChar = s.last()
            for (idx in s.indices) {
                val ch = s[idx]
                if (ch == openChar) depth++
                else if (ch == closeChar) depth--
                if (depth == 0 && idx < s.length - 1) {
                    return false
                }
            }
            return depth == 0
        }
        return false
    }

    private fun needsParensForBareBraces(convInner: String): Boolean {
        val s = convInner.trim()
        if (s.isEmpty()) return false
        if (isFullyGrouped(s)) return false

        val firstChar = s.first()
        if (firstChar in listOf('=', '≤', '≥', '<', '>')) return false

        if (firstChar == '(') {
            var depth = 0
            var matchIdx = -1
            for (idx in s.indices) {
                val ch = s[idx]
                if (ch == '(') depth++
                else if (ch == ')') depth--
                if (depth == 0) {
                    matchIdx = idx
                    break
                }
            }
            if (matchIdx > 0 && matchIdx < s.length - 1) {
                val rest = s.substring(matchIdx + 1).trim()
                if (rest.startsWith("^") || rest.startsWith("_")) {
                    return false
                }
            }
        }

        var depth = 0
        for (idx in s.indices) {
            val c = s[idx]
            if (c == '(' || c == '[' || c == '{') depth++
            else if (c == ')' || c == ']' || c == '}') depth--
            else if (depth == 0) {
                if (c in listOf('+', '-', '=', '≤', '≥', '<', '>')) {
                    return true
                }
            }
        }
        return false
    }

    private fun unwrapTextCommandsBraceAware(input: String): String {
        val knownMathFns = setOf("sin", "cos", "tan", "tg", "cot", "ctg", "arcsin", "arccos", "arctan", "arctg", "log", "ln", "lg")
        val textCmds = listOf(
            "operatorname*", "operatorname", "mathrm", "textbf", "textit",
            "text", "mathbf", "mathit", "boldsymbol", "mathsf", "mathcal"
        )

        val sb = StringBuilder()
        var i = 0
        val n = input.length

        while (i < n) {
            if (input[i] == '\\') {
                val rest = input.substring(i + 1)
                var matchedCmd: String? = null
                for (cmd in textCmds) {
                    if (rest.startsWith(cmd)) {
                        val afterIdx = i + 1 + cmd.length
                        if (afterIdx >= n || (!input[afterIdx].isLetterOrDigit() && input[afterIdx] != '*')) {
                            matchedCmd = cmd
                            break
                        }
                    }
                }

                if (matchedCmd != null) {
                    val afterCmdIdx = i + 1 + matchedCmd.length
                    val idx = skipWhitespace(input, afterCmdIdx)
                    if (idx < n && input[idx] == '{') {
                        val (inner, nextIdx) = parseBraced(input, idx)
                        val unwrappedInner = unwrapTextCommandsBraceAware(inner)
                        val compact = unwrappedInner.replace(Regex("""\s+"""), "")
                        val lower = compact.lowercase()
                        if (lower in knownMathFns) {
                            sb.append("\\$lower ")
                        } else {
                            sb.append(" ").append(unwrappedInner).append(" ")
                        }
                        i = nextIdx
                    } else {
                        // Bare command without braces: skip command name
                        i = afterCmdIdx
                    }
                    continue
                }
            }
            sb.append(input[i])
            i++
        }
        return sb.toString()
    }

    private fun skipWhitespace(s: String, idx: Int): Int {
        var i = idx
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    private fun parseBraced(s: String, startIdx: Int): Pair<String, Int> {
        var i = skipWhitespace(s, startIdx)
        if (i >= s.length || s[i] != '{') return Pair("", i)
        val startInner = i + 1
        var depth = 1
        i++
        while (i < s.length && depth > 0) {
            if (s[i] == '\\' && i + 1 < s.length && (s[i + 1] == '{' || s[i + 1] == '}')) {
                i += 2
                continue
            }
            if (s[i] == '{') depth++
            else if (s[i] == '}') depth--
            i++
        }
        val inner = s.substring(startInner, if (depth == 0) i - 1 else s.length)
        return Pair(inner, i)
    }

    private fun parseBracketed(s: String, startIdx: Int): Pair<String, Int> {
        var i = skipWhitespace(s, startIdx)
        if (i >= s.length || s[i] != '[') return Pair("", i)
        val startInner = i + 1
        var depth = 1
        i++
        while (i < s.length && depth > 0) {
            if (s[i] == '[') depth++
            else if (s[i] == ']') depth--
            i++
        }
        val inner = s.substring(startInner, if (depth == 0) i - 1 else s.length)
        return Pair(inner, i)
    }

    private fun parseParenthesized(s: String, startIdx: Int): Pair<String, Int> {
        var i = skipWhitespace(s, startIdx)
        if (i >= s.length || s[i] != '(') return Pair("", i)
        val startInner = i + 1
        var depth = 1
        i++
        while (i < s.length && depth > 0) {
            if (s[i] == '(') depth++
            else if (s[i] == ')') depth--
            i++
        }
        val inner = s.substring(startInner, if (depth == 0) i - 1 else s.length)
        return Pair(inner, i)
    }

    private fun parseModulus(s: String, startIdx: Int): Pair<String, Int> {
        var i = skipWhitespace(s, startIdx)
        if (i >= s.length || s[i] != '|') return Pair("", i)
        val startMod = i
        i++
        var depth = 1
        while (i < s.length && depth > 0) {
            if (s[i] == '|') {
                depth--
            }
            i++
        }
        val inner = s.substring(startMod, i)
        return Pair(inner, i)
    }

    private fun parseArg(s: String, startIdx: Int): Pair<String, Int> {
        var i = skipWhitespace(s, startIdx)
        if (i >= s.length) return Pair("", i)

        if (s[i] == '{') return parseBraced(s, i)
        if (s[i] == '(') return parseParenthesized(s, i)
        if (s[i] == '[') return parseBracketed(s, i)
        if (s[i] == '|') return parseModulus(s, i)

        if (s[i] == '\\') {
            val cmdStart = i
            i++
            while (i < s.length && s[i].isLetter()) i++
            val cmdName = s.substring(cmdStart + 1, i)

            if (cmdName in listOf("frac", "dfrac", "tfrac", "cfrac", "sfrac", "overset", "underset", "stackrel")) {
                val (_, i1) = parseArg(s, i)
                val (_, i2) = parseArg(s, i1)
                return Pair(s.substring(cmdStart, i2), i2)
            } else if (cmdName in listOf("overline", "underline", "overbrace", "underbrace", "bar", "hat", "vec", "tilde", "widetilde", "widehat")) {
                val (_, i1) = parseArg(s, i)
                return Pair(s.substring(cmdStart, i1), i1)
            } else if (cmdName == "sqrt") {
                var currI = skipWhitespace(s, i)
                if (currI < s.length && s[currI] == '[') {
                    val (_, nxt) = parseBracketed(s, currI)
                    currI = nxt
                }
                val (_, nxt) = parseArg(s, currI)
                return Pair(s.substring(cmdStart, nxt), nxt)
            } else if (cmdName in listOf("log", "ln", "lg", "sin", "cos", "tan", "tg", "cot", "ctg", "arcsin", "arccos", "arctan", "arctg")) {
                var currI = skipWhitespace(s, i)
                while (currI < s.length && (s[currI] == '_' || s[currI] == '^')) {
                    val (_, nxt) = parseArg(s, currI + 1)
                    currI = nxt
                    currI = skipWhitespace(s, currI)
                }
                val (_, nxt) = parseArg(s, currI)
                return Pair(s.substring(cmdStart, nxt), nxt)
            } else {
                return Pair(s.substring(cmdStart, i), i)
            }
        }

        val startToken = i
        if (s[i].isDigit()) {
            i++
        } else if (s[i].isLetter()) {
            while (i < s.length && s[i].isLetter()) i++
        } else {
            i++
        }
        return Pair(s.substring(startToken, i), i)
    }

    private data class LogComponents(
        val base: String?,
        val power: String?,
        val arg: String,
        val nextIdx: Int
    )

    private fun parseLogComponents(
        latex: String,
        startIdx: Int,
        warnings: MutableList<String>
    ): LogComponents {
        var curr = skipWhitespace(latex, startIdx)
        var base: String? = null
        var power: String? = null

        for (_step in 0..1) {
            curr = skipWhitespace(latex, curr)
            if (curr < latex.length && latex[curr] == '_') {
                val (subStr, nxt) = parseArg(latex, curr + 1)
                base = subStr
                curr = nxt
            } else if (curr < latex.length && latex[curr] == '^') {
                val (powStr, nxt) = parseArg(latex, curr + 1)
                power = powStr
                curr = nxt
            }
        }

        curr = skipWhitespace(latex, curr)
        val (argStr, nxt) = parseBareFunctionArg(latex, curr, isLogFunction = true)
        return LogComponents(base, power, argStr, nxt)
    }

    private data class FnPowerAndArg(
        val power: String?,
        val arg: String,
        val nextIdx: Int
    )

    private fun parseFnPowerAndArg(
        latex: String,
        startIdx: Int,
        warnings: MutableList<String>,
        isLogFunction: Boolean = false
    ): FnPowerAndArg {
        var curr = skipWhitespace(latex, startIdx)
        var power: String? = null

        if (curr < latex.length && latex[curr] == '^') {
            val (powStr, nxt) = parseArg(latex, curr + 1)
            power = powStr
            curr = nxt
        }

        curr = skipWhitespace(latex, curr)
        val (argStr, nxt) = parseBareFunctionArg(latex, curr, isLogFunction = isLogFunction)
        return FnPowerAndArg(power, argStr, nxt)
    }

    private fun parseBareFunctionArg(
        s: String,
        startIdx: Int,
        isLogFunction: Boolean = false
    ): Pair<String, Int> {
        var i = skipWhitespace(s, startIdx)
        if (i >= s.length) return Pair("", i)

        if (s[i] in listOf('(', '{', '[', '|')) {
            if (isLogFunction) {
                val (inner, afterGroup) = parseArg(s, i)
                val preCheck = skipWhitespace(s, afterGroup)
                if (preCheck < s.length && s[preCheck] == '^') {
                    val (_, afterExp) = parseArg(s, preCheck + 1)
                    return Pair(s.substring(i, afterExp).trim(), afterExp)
                }
                return Pair(inner, afterGroup)
            } else {
                return parseArg(s, i)
            }
        }

        val argStart = i
        if (s[i] == '-') {
            i++
        }

        var atomCount = 0
        while (i < s.length) {
            i = skipWhitespace(s, i)
            if (i >= s.length) break

            val c = s[i]

            if (c in listOf('+', '=', '<', '>', '≤', '≥', ')', ']', '}', '&')) {
                break
            }
            if (c == '-' && atomCount > 0) {
                break
            }

            if (c == '\\') {
                val cmdStart = i
                i++
                val nameSb = StringBuilder()
                while (i < s.length && s[i].isLetter()) {
                    nameSb.append(s[i])
                    i++
                }
                val cmdName = nameSb.toString()

                if (cmdName in listOf("cdot", "times", "ast", "le", "leq", "leqslant", "ge", "geq", "geqslant")) {
                    i = cmdStart
                    break
                }

                val isTrigOrRoot = cmdName in listOf("sin", "cos", "tan", "tg", "cot", "ctg", "arcsin", "arccos", "arctan", "arctg", "sqrt")
                val isLog = cmdName in listOf("log", "ln", "lg")

                if (isTrigOrRoot || (isLog && !isLogFunction)) {
                    i = cmdStart
                    break
                }

                if (isLog) {
                    var currI = skipWhitespace(s, i)
                    while (currI < s.length && (s[currI] == '_' || s[currI] == '^')) {
                        val (_, nxt) = parseArg(s, currI + 1)
                        currI = nxt
                        currI = skipWhitespace(s, currI)
                    }
                    val (_, nxt) = parseArg(s, currI)
                    i = nxt
                    atomCount++
                } else if (cmdName in listOf("frac", "dfrac", "tfrac", "cfrac", "sfrac")) {
                    val (_, i1) = parseArg(s, i)
                    val (_, i2) = parseArg(s, i1)
                    i = i2
                    atomCount++
                } else {
                    atomCount++
                }
            } else if (c == '/') {
                val slashIdx = i
                i++
                i = skipWhitespace(s, i)
                if (i < s.length && s[i] == '\\') {
                    val (_, nxt) = parseArg(s, i)
                    i = nxt
                    atomCount++
                } else if (i < s.length && (s[i].isLetterOrDigit() || s[i] in listOf('(', '{'))) {
                    val (_, nxt) = parseArg(s, i)
                    i = nxt
                    atomCount++
                } else {
                    i = slashIdx
                    break
                }
            } else if (c.isDigit()) {
                while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) {
                    i++
                }
                atomCount++
            } else if (c.isLetter()) {
                i++
                atomCount++
            } else {
                break
            }

            val preCheck = skipWhitespace(s, i)
            if (preCheck < s.length && (s[preCheck] == '^' || s[preCheck] == '_')) {
                val (_, nxt) = parseArg(s, preCheck + 1)
                i = nxt
            }
        }

        if (i == argStart) {
            return parseArg(s, startIdx)
        }

        return Pair(s.substring(argStart, i).trim(), i)
    }

    internal fun hasMathBody(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return false
        val mathTokens = listOf(
            "=", "<", ">", "≤", "≥",
            "\\le", "\\ge", "\\leqslant", "\\geqslant",
            "\\frac", "\\cfrac", "\\sqrt",
            "\\sin", "\\cos", "\\tan", "\\log", "\\ln",
            "x", "y"
        )
        return mathTokens.any { trimmed.contains(it) }
    }

    internal fun hasRelation(body: String): Boolean {
        val relationTokens = listOf("=", "<", ">", "≤", "≥", "\\le", "\\ge", "\\leqslant", "\\geqslant")
        return relationTokens.any { body.contains(it) }
    }

    internal fun stripLeadingTaskLabel(input: String): String {
        var s = input
        val regexNo = Regex("""^\s*№\s*\d{1,3}[.)]?\s+""")
        val regexLetter = Regex("""^\s*[а-еА-Еa-eA-E]\)\s+""")
        val regexNumParen = Regex("""^\s*\d{1,2}\)\s+""")
        val regexNumDot = Regex("""^\s*\d{1,2}\.(?!\d)\s+""")

        for (_step in 0..1) {
            val matchNo = regexNo.find(s)
            if (matchNo != null && matchNo.range.first == 0) {
                val remaining = s.substring(matchNo.range.last + 1)
                if (hasMathBody(remaining)) {
                    s = remaining
                    continue
                }
            }

            val matchLetter = regexLetter.find(s)
            if (matchLetter != null && matchLetter.range.first == 0) {
                val remaining = s.substring(matchLetter.range.last + 1)
                if (s.count { it == ')' } > s.count { it == '(' } && hasMathBody(remaining)) {
                    s = remaining
                    continue
                }
            }

            val matchNumParen = regexNumParen.find(s)
            if (matchNumParen != null && matchNumParen.range.first == 0) {
                val remaining = s.substring(matchNumParen.range.last + 1)
                if (s.count { it == ')' } > s.count { it == '(' } && hasMathBody(remaining)) {
                    s = remaining
                    continue
                }
            }

            val matchNumDot = regexNumDot.find(s)
            if (matchNumDot != null && matchNumDot.range.first == 0) {
                val remaining = s.substring(matchNumDot.range.last + 1)
                if (hasRelation(remaining)) {
                    s = remaining
                    continue
                }
            }

            break
        }
        return s
    }
}
