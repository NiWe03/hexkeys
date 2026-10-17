package io.github.tieo.hexkeys

import android.content.Context
import java.text.Normalizer

/**
 * Emoji by name, in English and every language in use: Unicode CLDR's name
 * and keywords for each emoji (assets/emoji-names, from
 * tools/emoji_names.py). A query matches an emoji when each of its words
 * starts a word of one of the emoji's names, in any language; emoji whose
 * name itself starts with the query come first, then those it begins a
 * name or keyword of, each in Unicode's order.
 */
class EmojiSearch(context: Context) {

    /** Every emoji in Unicode's order, with its names from all languages, folded for matching. */
    private val entries: List<Pair<String, List<String>>>

    init {
        val order = context.assets.open("emoji.tsv").bufferedReader().readLines()
            .flatMap { line -> line.split('\t').getOrNull(1)?.split(' ').orEmpty() }
        val names = LinkedHashMap<String, MutableList<String>>()
        for (emoji in order) names[emoji] = mutableListOf()
        val available = context.assets.list("emoji-names").orEmpty().toSet()
        for (language in languages(context)) {
            if ("$language.tsv" !in available) continue
            context.assets.open("emoji-names/$language.tsv").bufferedReader().forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) return@forEachLine
                names[line.substring(0, tab)]?.addAll(line.substring(tab + 1).split('|').map(::fold))
            }
        }
        entries = names.entries.filter { it.value.isNotEmpty() }.map { it.key to it.value.distinct() }
    }

    /** The emoji `query` names, best first, at most `limit`. */
    fun find(query: String, limit: Int = 48): List<String> {
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val whole = words.joinToString(" ")
        return entries.mapNotNull { (emoji, names) ->
            val matches = words.all { word -> names.any { name -> startsWord(name, word) } }
            if (!matches) return@mapNotNull null
            val rank = when {
                names.first().startsWith(whole) -> 0
                names.any { it.startsWith(whole) } -> 1
                else -> 2
            }
            rank to emoji
        }.sortedBy { it.first }.take(limit).map { it.second }
    }

    private fun startsWord(name: String, word: String): Boolean {
        var at = name.indexOf(word)
        while (at >= 0) {
            if (at == 0 || !name[at - 1].isLetterOrDigit()) return true
            at = name.indexOf(word, at + 1)
        }
        return false
    }

    private companion object {
        /** Lower case without accents, so "hande" finds "hände". */
        fun fold(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

        /**
         * English and the languages of the enabled dictionaries, by the name
         * their file has: the catalog locale without its country, except
         * where CLDR has a file for the variant itself.
         */
        fun languages(context: Context): List<String> {
            val enabled = Dictionaries.enabled(context)
            val own = Dictionaries.catalog(context).filter { it.file in enabled }.map { language ->
                val locale = language.locale.lowercase()
                if (locale in VARIANTS) locale else locale.substringBefore('_')
            }
            return (listOf("en") + own).distinct()
        }

        /** Catalog locales with names of their own rather than their base language's. */
        val VARIANTS = setOf("pt_pt", "hi_zz", "sr_zz")
    }
}
