package mihon.domain.dictionary.interactor

import dev.esnault.wanakana.core.Wanakana
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.repository.DictionaryRepository
import mihon.domain.dictionary.service.Candidate
import mihon.domain.dictionary.service.InflectionType
import mihon.domain.dictionary.service.JapaneseDeinflector
import java.util.LinkedHashMap

/**
 * Interactor for searching dictionary terms with multilingual support.
 * The parser (Japanese deinflection vs. direct lookup) is chosen automatically
 * by detecting the script of the query text, so no language hint is needed.
 */
class SearchDictionaryTerms(
    private val dictionaryRepository: DictionaryRepository,
) {
    data class FirstWordMatch(
        val word: String,
        val sourceOffset: Int,
        val sourceLength: Int,
        val isDictionaryMatch: Boolean = false,
    )

    private val dictionaryScriptCache = java.util.concurrent.ConcurrentHashMap<Long, Set<Script>>()

    /** Script families used to select the right search/segmentation pipeline. */
    private enum class Script { JAPANESE, KOREAN, CHINESE, ENGLISH, FRENCH }

    private suspend fun getAllowedScripts(dictionaryIds: List<Long>): Set<Script>? {
        val allowed = mutableSetOf<Script>()
        for (id in dictionaryIds) {
            val scripts = dictionaryScriptCache.getOrPut(id) {
                val dict = dictionaryRepository.getDictionary(id) ?: return@getOrPut emptySet()
                val src = dict.sourceLanguage.orEmpty()

                if (src.isEmpty() || src == "unrestricted") {
                    emptySet()
                } else {
                    val srcScript = mapLanguageToScript(src)
                    setOfNotNull(srcScript)
                }
            }
            if (scripts.isEmpty()) return null // emptySet represents unrestricted, so return null
            allowed.addAll(scripts)
        }
        return allowed.ifEmpty { null }
    }

    private fun mapLanguageToScript(language: String): Script? {
        val code = language.lowercase().substringBefore('-')
        return when (code) {
            "ja", "jpn" -> Script.JAPANESE
            "ko", "kor" -> Script.KOREAN
            "zh", "zho", "chi" -> Script.CHINESE
            "en", "eng" -> Script.ENGLISH
            "fr", "fra", "fre" -> Script.FRENCH
            else -> null
        }
    }

    /**
     * Detects the dominant script of [text] by scanning up to [SCRIPT_DETECT_WINDOW] meaningful characters.
     */
    private fun detectScript(text: String, allowedScripts: Set<Script>?): Script {
        var hasCjk = false
        var scanned = 0
        val punctuationChars = PUNCTUATION_CHARS.filterIsInstance<Char>().toSet()
        for (ch in text) {
            if (ch in punctuationChars || ch.isWhitespace()) continue
            if (ch in '\u3041'..'\u309F' || ch in '\u30A0'..'\u30FF') return Script.JAPANESE
            if (ch in '\uAC00'..'\uD7A3' || ch in '\u1100'..'\u11FF') return Script.KOREAN
            if (ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF') {
                hasCjk = true
            } else if (ch.isLetter() && ch.code < 0x300) {
                return when {
                    allowedScripts == null -> Script.ENGLISH
                    Script.JAPANESE in allowedScripts && allowedScripts.size == 1 -> Script.JAPANESE
                    Script.FRENCH in allowedScripts -> Script.FRENCH
                    else -> Script.ENGLISH
                }
            }
            if (++scanned >= SCRIPT_DETECT_WINDOW) break
        }
        return if (hasCjk) {
            when {
                allowedScripts == null -> Script.JAPANESE
                Script.JAPANESE in allowedScripts -> Script.JAPANESE
                Script.CHINESE in allowedScripts -> Script.CHINESE
                Script.KOREAN in allowedScripts -> Script.KOREAN
                else -> Script.JAPANESE
            }
        } else {
            allowedScripts?.firstOrNull() ?: Script.JAPANESE
        }
    }

    /**
     * Returns the [Script] to use, honouring [override] when it is not [ParserLanguage.AUTO].
     * When [override] is [ParserLanguage.AUTO] the script is detected from [text] automatically.
     */
    private suspend fun resolveScript(text: String, override: ParserLanguage, dictionaryIds: List<Long>): Script =
        when (override) {
            ParserLanguage.AUTO -> detectScript(text, getAllowedScripts(dictionaryIds))
            ParserLanguage.JAPANESE -> Script.JAPANESE
            ParserLanguage.KOREAN -> Script.KOREAN
            ParserLanguage.CHINESE -> Script.CHINESE
            ParserLanguage.ENGLISH -> Script.ENGLISH
            ParserLanguage.FRENCH -> Script.FRENCH
        }

    /**
     * Searches for dictionary terms matching [query].
     * The parser is chosen automatically from the query's script unless [parserLanguage]
     * is set to a specific value.
     * For Latin text, direct search runs first; if empty, the Japanese parser is used to cover romaji.
     */
    suspend fun search(
        query: String,
        dictionaryIds: List<Long>,
        parserLanguage: ParserLanguage = ParserLanguage.AUTO,
    ): List<DictionaryTerm> {
        if (dictionaryIds.isEmpty()) return emptyList()

        val charsToTrim = PUNCTUATION_CHARS.filterIsInstance<Char>().toSet()
        val normalizedQuery = query.trim { it in charsToTrim || it.isWhitespace() }.removeSuffix("...")

        return when (resolveScript(normalizedQuery, parserLanguage, dictionaryIds)) {
            Script.JAPANESE -> searchJa(normalizedQuery, dictionaryIds)
            Script.ENGLISH, Script.FRENCH -> searchDirect(normalizedQuery, dictionaryIds).ifEmpty {
                searchJa(normalizedQuery, dictionaryIds)
            }
            else -> searchDirect(normalizedQuery, dictionaryIds)
        }
    }

    /** Japanese search: romaji -> kana conversion + deinflection. */
    private suspend fun searchJa(query: String, dictionaryIds: List<Long>): List<DictionaryTerm> {
        // Converts romaji to kana to support romaji input
        val formattedQuery = convertToKana(query.trim())

        // Gather possible deinflections for the term
        val candidateQueries = JapaneseDeinflector.deinflect(formattedQuery)
        if (candidateQueries.isEmpty()) return emptyList()

        // Group candidates with the same word result, but different inflection reasons
        val candidatesByTerm = candidateQueries.groupBy { it.term }

        val results = LinkedHashMap<Long, DictionaryTerm>(minOf(candidateQueries.size * 4, MAX_RESULTS * 2))

        candidateLoop@ for (candidate in candidateQueries) {
            val term = candidate.term
            if (term.isBlank()) continue

            val matches = dictionaryRepository.searchTerms(term, dictionaryIds)
            for (dbTerm in matches) {
                // Avoid duplicates
                if (dbTerm.id in results) continue

                // Validate parts of speech: match candidate conditions against DB entry's rules
                // Falls back to reading for better coverage
                val candidatesForTerm = candidatesByTerm[dbTerm.expression]
                    ?: candidatesByTerm[dbTerm.reading]

                if (candidatesForTerm != null && isValidMatch(dbTerm, candidatesForTerm)) {
                    results[dbTerm.id] = dbTerm
                    if (results.size >= MAX_RESULTS) break@candidateLoop
                }
            }
        }

        return results.values.toList()
    }

    /** Direct search (no deinflection/kana). Also tries lowercase for case-insensitivity. */
    private suspend fun searchDirect(query: String, dictionaryIds: List<Long>): List<DictionaryTerm> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val results = LinkedHashMap<Long, DictionaryTerm>(MAX_RESULTS * 2)
        val matches = dictionaryRepository.searchTerms(trimmed, dictionaryIds)
        for (dbTerm in matches) {
            if (dbTerm.id !in results) {
                results[dbTerm.id] = dbTerm
                if (results.size >= MAX_RESULTS) break
            }
        }

        // Also try lowercase for case-insensitive fallback
        val lowered = trimmed.lowercase()
        if (lowered != trimmed && results.size < MAX_RESULTS) {
            val lowerMatches = dictionaryRepository.searchTerms(lowered, dictionaryIds)
            for (dbTerm in lowerMatches) {
                if (dbTerm.id !in results) {
                    results[dbTerm.id] = dbTerm
                    if (results.size >= MAX_RESULTS) break
                }
            }
        }

        return results.values.toList()
    }

    /** Returns the first matched word of [sentence]. See [findFirstWordMatch]. */
    suspend fun findFirstWord(
        sentence: String,
        dictionaryIds: List<Long>,
        parserLanguage: ParserLanguage = ParserLanguage.AUTO,
    ): String = findFirstWordMatch(sentence, dictionaryIds, parserLanguage).word

    /** Segments [sentence] by finding the longest dictionary match prefix. */
    suspend fun findFirstWordMatch(
        sentence: String,
        dictionaryIds: List<Long>,
        parserLanguage: ParserLanguage = ParserLanguage.AUTO,
    ): FirstWordMatch {
        if (sentence.isBlank() || dictionaryIds.isEmpty()) return FirstWordMatch("", 0, 0)

        val script = resolveScript(sentence, parserLanguage, dictionaryIds)
        return when (script) {
            Script.JAPANESE -> firstWordJa(sentence, dictionaryIds)
            Script.ENGLISH, Script.FRENCH -> {
                val directResult = firstWordDirect(sentence, dictionaryIds, script)
                val jaResult = firstWordJa(sentence, dictionaryIds)

                if (jaResult.isDictionaryMatch && directResult.isDictionaryMatch) {
                    if (jaResult.sourceLength >= directResult.sourceLength) jaResult else directResult
                } else if (jaResult.isDictionaryMatch) {
                    jaResult
                } else if (directResult.isDictionaryMatch) {
                    directResult
                } else {
                    if (jaResult.sourceLength >= directResult.sourceLength) jaResult else directResult
                }
            }
            else -> firstWordDirect(sentence, dictionaryIds, script)
        }
    }

    private suspend fun dictionaryContains(word: String, dictionaryIds: List<Long>): Boolean {
        val matches = dictionaryRepository.searchTerms(word, dictionaryIds)
        if (matches.isNotEmpty()) return true
        val lowered = word.lowercase()
        return if (lowered != word) dictionaryRepository.searchTerms(lowered, dictionaryIds).isNotEmpty() else false
    }

    /** Japanese segmentation: strips leading punctuation, converts romaji, then deinflects. */
    private suspend fun firstWordJa(sentence: String, dictionaryIds: List<Long>): FirstWordMatch {
        // Remove leading punctuation and brackets, while preserving offset in source text
        val punctuationChars = PUNCTUATION_CHARS.filterIsInstance<Char>().toSet()
        val leadingTrimmedCount = sentence.indexOfFirst { it !in punctuationChars }
            .let { if (it == -1) sentence.length else it }
        val sanitized = sentence.drop(leadingTrimmedCount)
        if (sanitized.isEmpty()) return FirstWordMatch("", leadingTrimmedCount, 0)

        // Convert romaji to kana
        val normalized = convertToKana(sanitized)

        val maxLength = minOf(normalized.length, MAX_WORD_LENGTH)

        for (len in maxLength downTo 1) {
            val substring = normalized.take(len)
            val candidates = JapaneseDeinflector.deinflect(substring)

            for (candidate in candidates) {
                val term = candidate.term
                if (term.isBlank()) continue

                // Check if this candidate exists in the dictionary
                val matches = dictionaryRepository.searchTerms(term, dictionaryIds)
                if (matches.isNotEmpty()) {
                    val candidatesForTerm = candidates.filter { c ->
                        c.term == term || matches.any { m -> m.reading == c.term }
                    }
                    val validMatch = matches.any { dbTerm ->
                        isValidMatch(dbTerm, candidatesForTerm)
                    }
                    if (validMatch) {
                        val sourceLength = mapSourceLength(sanitized, substring)
                        return FirstWordMatch(
                            word = substring,
                            sourceOffset = leadingTrimmedCount,
                            sourceLength = sourceLength,
                            isDictionaryMatch = true,
                        )
                    }
                }
            }
        }

        // No dictionary match found - return the first character as fallback
        val fallbackWord = normalized.take(1)
        return FirstWordMatch(
            word = fallbackWord,
            sourceOffset = leadingTrimmedCount,
            sourceLength = mapSourceLength(sanitized, fallbackWord),
            isDictionaryMatch = false,
        )
    }

    /** Direct segmentation (Character-by-character longest match for non-Japanese scripts) */
    private suspend fun firstWordDirect(sentence: String, dictionaryIds: List<Long>, script: Script): FirstWordMatch {
        val punctuationChars = PUNCTUATION_CHARS.filterIsInstance<Char>().toSet()
        val leadingTrimmedCount = sentence.indexOfFirst { it !in punctuationChars }
            .let { if (it == -1) sentence.length else it }
        val sanitized = sentence.drop(leadingTrimmedCount)
        if (sanitized.isEmpty()) return FirstWordMatch("", leadingTrimmedCount, 0)

        val maxLength = minOf(sanitized.length, 40)

        for (len in maxLength downTo 1) {
            val substring = sanitized.take(len)

            // Optimization for spaces at the end: don't look up if ending in space, unless it's only 1 char
            if (len > 1 && substring.last().isWhitespace()) continue

            val matches = dictionaryRepository.searchTerms(substring, dictionaryIds)
            if (matches.isNotEmpty()) {
                return FirstWordMatch(substring, leadingTrimmedCount, len, true)
            }

            if (script == Script.ENGLISH || script == Script.FRENCH) {
                val lowered = substring.lowercase()
                if (lowered != substring) {
                    val lowerMatches = dictionaryRepository.searchTerms(lowered, dictionaryIds)
                    if (lowerMatches.isNotEmpty()) {
                        return FirstWordMatch(substring, leadingTrimmedCount, len, true)
                    }
                }
            }
        }

        // No match found: calculate fallback word length based on script boundaries
        val isLatin = script == Script.ENGLISH || script == Script.FRENCH
        val fallbackLength = if (isLatin) {
            var i = 0
            while (i < sanitized.length && !isBoundary(sanitized[i], script == Script.FRENCH)) {
                i++
            }
            if (i == 0) 1 else i
        } else {
            1
        }

        val fallbackWord = sanitized.take(fallbackLength)
        return FirstWordMatch(fallbackWord, leadingTrimmedCount, fallbackLength, false)
    }

    private fun isBoundary(c: Char, isFrench: Boolean): Boolean {
        val punctuationChars = PUNCTUATION_CHARS.filterIsInstance<Char>().toSet()
        if (c.isWhitespace()) return true
        if (isFrench && (c == '\'' || c == '\u2019')) return true
        if (c in punctuationChars && c != '\'' && c != '\u2019') return true
        return false
    }

    /** Validates that a dictionary term matches at least one candidate condition. */
    private fun isValidMatch(term: DictionaryTerm, candidates: List<Candidate>): Boolean {
        val dbRuleMask = InflectionType.parseRules(term.rules)

        for (candidate in candidates) {
            if (candidate.conditions == InflectionType.ALL) return true
            if (dbRuleMask == InflectionType.UNSPECIFIED) return true
            if (InflectionType.conditionsMatch(candidate.conditions, dbRuleMask)) return true
        }
        return false
    }

    suspend fun getTermMeta(
        expressions: List<String>,
        dictionaryIds: List<Long>,
    ): Map<String, List<DictionaryTermMeta>> {
        val allMeta = mutableMapOf<String, MutableList<DictionaryTermMeta>>()

        expressions.forEach { expression ->
            val meta = dictionaryRepository.getTermMetaForExpression(expression, dictionaryIds)
            allMeta[expression] = meta.toMutableList()
        }

        return allMeta
    }

    private fun convertToKana(input: String): String {
        return input.trim().let {
            if (it.any(Char::isLatinLetter) || Wanakana.isRomaji(it) || Wanakana.isMixed(it)) {
                Wanakana.toKana(it)
            } else {
                it
            }
        }
    }

    /*
     * Maps the length of the normalized prefix back to the source string, accounting for romaji
     */
    private fun mapSourceLength(source: String, normalizedPrefix: String): Int {
        if (normalizedPrefix.isEmpty()) return 0

        for (index in 1..source.length) {
            val convertedPrefix = convertToKana(source.take(index))
            if (convertedPrefix.length >= normalizedPrefix.length &&
                convertedPrefix.startsWith(normalizedPrefix)
            ) {
                return index
            }
        }

        return minOf(source.length, normalizedPrefix.length)
    }
}

private fun Char.isLatinLetter(): Boolean =
    (this in 'a'..'z') || (this in 'A'..'Z')

private const val MAX_RESULTS = 100
private const val MAX_WORD_LENGTH = 20
private const val SCRIPT_DETECT_WINDOW = 30
private val PUNCTUATION_CHARS = setOf(
    '「', '」', '『', '』', '（', '）', '(', ')', '【', '】',
    '〔', '〕', '《', '》', '〈', '〉',
    '・', '、', '。', '！', '？', '：', '；',
    ' ', '\t', '\n', '\r', '\u3000', // whitespace characters
    '\u201C', '\u201D', // double quotation marks
    '\u2018', '\u2019', // single quotation marks
    '"', '\'', // ASCII quotes
    '.', ',', '…', "...", // punctuation and ellipsis
    '-', '\u2010', '\u2013', '\u2014', // hyphen variants
    '«', '»', '<', '>', '[', ']', '{', '}', '/', '\\',
    '〜', '\u301C', '\uFF5E', // tildes / wave dash
)
