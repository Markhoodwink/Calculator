package com.example.calculator

class BpeLatexTokenizer(
    val idToToken: Map<Int, String>,
    val specialTokenIds: Set<Int>
) {
    companion object {
        private val charToByte: Map<Int, Byte> = run {
            val bList = mutableListOf<Int>()
            for (b in '!'.code..'~'.code) bList.add(b)
            for (b in '¡'.code..'¬'.code) bList.add(b)
            for (b in '®'.code..'ÿ'.code) bList.add(b)
            val cList = bList.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (b !in bList) {
                    bList.add(b)
                    cList.add(256 + n)
                    n++
                }
            }
            bList.indices.associate { i -> cList[i] to bList[i].toByte() }
        }

        fun fromJsonString(jsonStr: String): BpeLatexTokenizer {
            val root = PureJsonParser.parse(jsonStr) as? Map<*, *> ?: emptyMap<String, Any?>()
            val idToTokenMap = mutableMapOf<Int, String>()
            val specialIdsSet = mutableSetOf<Int>()

            // 1. Parse added_tokens
            val addedTokens = root["added_tokens"] as? List<*>
            if (addedTokens != null) {
                for (item in addedTokens) {
                    if (item is Map<*, *>) {
                        val idNum = item["id"] as? Number
                        val content = item["content"] as? String
                        val isSpecial = (item["special"] as? Boolean) ?: false
                        if (idNum != null && content != null) {
                            val id = idNum.toInt()
                            idToTokenMap[id] = content
                            if (isSpecial || isDefaultSpecialToken(content)) {
                                specialIdsSet.add(id)
                            }
                        }
                    }
                }
            }

            // 2. Parse model.vocab
            val modelMap = root["model"] as? Map<*, *>
            val vocabMap = modelMap?.get("vocab") as? Map<*, *>
            if (vocabMap != null) {
                for ((tokKey, idVal) in vocabMap) {
                    val tokStr = tokKey as? String
                    val idNum = idVal as? Number
                    if (tokStr != null && idNum != null) {
                        val id = idNum.toInt()
                        if (!idToTokenMap.containsKey(id)) {
                            idToTokenMap[id] = tokStr
                        }
                        if (isDefaultSpecialToken(tokStr)) {
                            specialIdsSet.add(id)
                        }
                    }
                }
            }

            return BpeLatexTokenizer(idToTokenMap, specialIdsSet)
        }

        fun isDefaultSpecialToken(token: String): Boolean {
            return token in setOf("<s>", "</s>", "<pad>", "<unk>", "<mask>") ||
                    (token.startsWith("<") && token.endsWith(">")) ||
                    (token.startsWith("[") && token.endsWith("]"))
        }
    }

    fun decode(tokenIds: List<Int>, skipSpecialTokens: Boolean = true): String {
        val bytesList = mutableListOf<Byte>()
        for (id in tokenIds) {
            if (skipSpecialTokens && id in specialTokenIds) {
                continue
            }
            val tokStr = idToToken[id] ?: continue
            if (skipSpecialTokens && isDefaultSpecialToken(tokStr)) {
                continue
            }
            for (ch in tokStr) {
                val code = ch.code
                val b = charToByte[code]
                if (b != null) {
                    bytesList.add(b)
                } else if (ch == '\u2581') {
                    bytesList.add(' '.code.toByte())
                } else if (code <= 255) {
                    bytesList.add(code.toByte())
                } else {
                    val utfBytes = ch.toString().toByteArray(Charsets.UTF_8)
                    for (ub in utfBytes) {
                        bytesList.add(ub)
                    }
                }
            }
        }

        val decoded = ByteArray(bytesList.size) { i -> bytesList[i] }.toString(Charsets.UTF_8)
        return decoded.trim()
    }
}

object PureJsonParser {
    fun parse(jsonStr: String): Any? {
        var i = 0
        val len = jsonStr.length

        fun skipWs() {
            while (i < len && jsonStr[i].isWhitespace()) i++
        }

        fun parseString(): String {
            val sb = StringBuilder()
            i++ // skip initial "
            while (i < len) {
                val c = jsonStr[i++]
                if (c == '"') return sb.toString()
                if (c == '\\' && i < len) {
                    val esc = jsonStr[i++]
                    when (esc) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 <= len) {
                                val hex = jsonStr.substring(i, i + 4)
                                try {
                                    sb.append(hex.toInt(16).toChar())
                                } catch (_: Exception) {
                                    sb.append("\\u").append(hex)
                                }
                                i += 4
                            }
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        lateinit var parseValue: () -> Any?

        fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // skip {
            skipWs()
            if (i < len && jsonStr[i] == '}') {
                i++
                return map
            }
            while (i < len) {
                skipWs()
                if (i >= len || jsonStr[i] != '"') break
                val key = parseString()
                skipWs()
                if (i < len && jsonStr[i] == ':') i++
                skipWs()
                val value = parseValue()
                map[key] = value
                skipWs()
                if (i < len && jsonStr[i] == ',') {
                    i++
                } else if (i < len && jsonStr[i] == '}') {
                    i++
                    break
                }
            }
            return map
        }

        fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            i++ // skip [
            skipWs()
            if (i < len && jsonStr[i] == ']') {
                i++
                return list
            }
            while (i < len) {
                skipWs()
                val value = parseValue()
                list.add(value)
                skipWs()
                if (i < len && jsonStr[i] == ',') {
                    i++
                } else if (i < len && jsonStr[i] == ']') {
                    i++
                    break
                }
            }
            return list
        }

        fun parseNumber(): Any {
            val start = i
            if (jsonStr[i] == '-') i++
            while (i < len && (jsonStr[i].isDigit() || jsonStr[i] == '.' || jsonStr[i] == 'e' || jsonStr[i] == 'E' || jsonStr[i] == '+' || jsonStr[i] == '-')) {
                i++
            }
            val numStr = jsonStr.substring(start, i)
            return if (numStr.contains('.') || numStr.contains('e') || numStr.contains('E')) {
                numStr.toDoubleOrNull() ?: 0.0
            } else {
                numStr.toLongOrNull() ?: 0L
            }
        }

        parseValue = {
            skipWs()
            if (i >= len) null
            else {
                when (val c = jsonStr[i]) {
                    '"' -> parseString()
                    '{' -> parseObject()
                    '[' -> parseArray()
                    't' -> {
                        if (i + 4 <= len && jsonStr.substring(i, i + 4) == "true") {
                            i += 4
                            true
                        } else null
                    }
                    'f' -> {
                        if (i + 5 <= len && jsonStr.substring(i, i + 5) == "false") {
                            i += 5
                            false
                        } else null
                    }
                    'n' -> {
                        if (i + 4 <= len && jsonStr.substring(i, i + 4) == "null") {
                            i += 4
                            null
                        } else null
                    }
                    else -> if (c.isDigit() || c == '-') parseNumber() else { i++; null }
                }
            }
        }

        return parseValue()
    }
}
