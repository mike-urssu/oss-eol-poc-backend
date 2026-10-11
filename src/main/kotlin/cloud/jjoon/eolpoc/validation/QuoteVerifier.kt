package cloud.jjoon.eolpoc.validation

import cloud.jjoon.eolpoc.lifecycle.domain.PreciseDate
import java.text.Normalizer

/** VAL-08: AI가 준 근거 문구가 원문에 실제로 있고, 값이 그 문구에서 확인되는지 검사한다. */
object QuoteVerifier {

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .replace(Regex("[‐‑‒–—―]"), "-")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()

    fun containsQuote(contentText: String, quote: String): Boolean = normalize(contentText).contains(normalize(quote))

    fun quoteMentionsDate(quote: String, date: PreciseDate): Boolean = date in DateMentionParser.parse(quote)

    fun quoteMentionsVersion(quote: String, version: String): Boolean =
        Regex("""(?<![\w.])${Regex.escape(version.lowercase())}(?![\w.])""").containsMatchIn(normalize(quote))
}
