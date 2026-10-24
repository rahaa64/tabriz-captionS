package com.turkishfacts.captions

import org.json.JSONObject
import java.util.Locale

/**
 * Whisper Latin Azeri (Baku/Turkey spelling) -> spoken Tabriz Azeri in Persian script.
 * 1:1 port of tools/tabriz.py on the same tabriz_rules.json (checked by unit tests on vectors.json).
 */
class TabrizConverter(json: String) {
    private val wordMap = HashMap<String, String>()
    private val fa = HashMap<String, String>()
    private val letters = HashMap<Char, String>()
    private val initial = HashMap<Char, String>()
    private val finalMap = HashMap<Char, String>()
    private val punct = HashMap<Char, String>()
    private val suffix = ArrayList<Pair<Regex, String>>()
    /** Words the user corrected in the editor: normalized Latin word -> Persian spelling. Wins over everything. */
    private val user = HashMap<String, String>()

    fun loadUser(json: String) {
        try {
            val o = JSONObject(json); val it = o.keys()
            while (it.hasNext()) { val k = it.next(); user[k] = o.getString(k) }
        } catch (e: Exception) { }
    }
    fun learn(latinWord: String, persian: String) { user[latinWord] = persian }
    fun userJson(): String = JSONObject(user as Map<*, *>).toString()
    fun userCount() = user.size

    init {
        val o = JSONObject(json)
        fun strMap(name: String, into: HashMap<String, String>) {
            val m = o.getJSONObject(name)
            val it = m.keys()
            while (it.hasNext()) { val k = it.next(); into[k] = m.getString(k) }
        }
        fun charMap(name: String, into: HashMap<Char, String>) {
            val m = o.getJSONObject(name)
            val it = m.keys()
            while (it.hasNext()) { val k = it.next(); if (k.length == 1) into[k[0]] = m.getString(k) }
        }
        strMap("word_map", wordMap)
        strMap("fa", fa)
        charMap("letters", letters)
        charMap("initial", initial)
        charMap("final", finalMap)
        charMap("punct", punct)
        val sr = o.getJSONArray("suffix_rules")
        val backref = Regex("\\\\(\\d)")
        for (i in 0 until sr.length()) {
            val a = sr.getJSONArray(i)
            val rep = backref.replace(a.getString(1)) { "\$" + it.groupValues[1] }
            suffix += Regex(a.getString(0)) to rep
        }
    }

    private fun lower(w: String): String {
        val sb = StringBuilder()
        for (c in w) sb.append(
            when (c) { 'I' -> 'ı'; 'İ' -> 'i'; else -> c }
        )
        return sb.toString().lowercase(Locale.ROOT)
    }

    fun normalize(word: String): String {
        var w = wordMap[word] ?: word
        for ((re, rep) in suffix) if (!fa.containsKey(w)) w = re.replace(w, rep)
        return w
    }

    private fun letters(w: String, wordInitial: Boolean = true): String {
        val out = StringBuilder()
        val n = w.length
        for (i in 0 until n) {
            val c = w[i]
            val first = wordInitial && i == 0
            val last = i == n - 1
            if (c !in VOW && i > 0 && w[i - 1] == c) continue // doubled consonant is written once
            if (c == 'y' && i > 1 && w[i - 1] == 'i') continue // siyasət -> سیاست
            if (c in VOW) {
                if (first) { out.append(initial[c] ?: letters[c] ?: ""); continue }
                if (i > 0 && w[i - 1] in VOW) { out.append(if (c !in "oöuü") "ئ" else "ئو"); continue }
                if (last && finalMap.containsKey(c)) { out.append(finalMap[c]); continue }
                out.append(letters[c] ?: "")
            } else out.append(letters[c] ?: c.toString())
        }
        return out.toString()
    }

    private fun join(stem: String, suf: String): String {
        if (suf.isEmpty()) return stem
        if (stem.last() in "هعء" || (stem.endsWith("ی") && suf.startsWith("ی"))) return stem + ZWNJ + suf
        return stem + suf
    }

    private fun isDigits(s: String) = s.isNotEmpty() && s.all { it.isDigit() }

    private fun faDigits(s: String) = buildString { for (c in s) append(if (c in '0'..'9') FA_DIG[c - '0'] else c) }

    fun wordFa(w: String): String {
        if (isDigits(w)) return faDigits(w)
        user[w]?.let { return it }
        fa[w]?.let { return it }
        for (k in w.length - 1 downTo 3) {
            val stem = fa[w.substring(0, k)]
            if (stem != null) return join(stem, letters(w.substring(k), wordInitial = false))
        }
        return letters(w)
    }

    /** returns (tabriz latin, persian script) */
    fun convert(text: String): Pair<String, String> {
        val lat = ArrayList<String>(); val far = ArrayList<String>()
        for (part in text.split(WS)) {
            if (part.isEmpty()) continue
            val lp = StringBuilder(); val fp = StringBuilder()
            for (m in TOKEN.findAll(part)) {
                val t = m.value
                if (FIRST_LETTER.matches(t.substring(0, 1))) {
                    val w = normalize(lower(t).replace("'", ""))
                    lp.append(w); fp.append(wordFa(w))
                } else if (isDigits(t)) {
                    lp.append(t); fp.append(faDigits(t))
                } else {
                    lp.append(t); for (c in t) fp.append(punct[c] ?: c.toString())
                }
            }
            lat += lp.toString(); far += fp.toString()
        }
        return lat.joinToString(" ") to far.joinToString(" ")
    }

    fun toPersian(text: String) = convert(text).second

    companion object {
        private const val VOW = "aəeıioöuü"
        private const val ZWNJ = "‌"
        private const val FA_DIG = "۰۱۲۳۴۵۶۷۸۹"
        private val WS = Regex("\\s+")
        private val TOKEN = Regex("([A-Za-zƏəÖöÜüĞğİıIŞşÇçXxQq'-]+|\\d+|[^\\sA-Za-z\\d]+)")
        private val FIRST_LETTER = Regex("[A-Za-zƏəÖöÜüĞğİıIŞşÇçXxQq]")
        private const val EDGE_PUNCT = "،.,؟?!:;«»\"'()[]…-"
        /** Latin token as stored in Caption.latin -> dictionary key. */
        fun key(token: String) = token.trim { it in EDGE_PUNCT || it.isWhitespace() }.replace("'", "")
        fun strip(word: String) = word.trim { it in EDGE_PUNCT || it.isWhitespace() }
    }
}
