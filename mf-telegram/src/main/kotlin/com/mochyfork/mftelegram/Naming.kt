// artist/title pulled out of file names and captions when a channel's audio has no tags
package com.mochyfork.mftelegram

// "artist - title" split rules, kept deliberately simple and predictable
object Naming {
    sealed interface Parsed {
        data class Both(val artist: String, val title: String) : Parsed
        data class TitleOnly(val title: String) : Parsed
        data object Nothing : Parsed
    }

    // ext, track numbers ("01 - "), urls in brackets and download-site noise all drop first
    fun parseName(raw: String): Parsed {
        var t = raw.trim()
        if (t.isEmpty()) return Parsed.Nothing
        // extension like .mp3/.m4a/.flac: strip a short tail after the last dot
        t = t.replace(Regex("""\.[A-Za-z0-9]{1,12}$"""), "")
        t = t.replace(Regex("""^\s*\d{1,3}\s*[-.)]\s*"""), " ")
        t = t.replace(Regex("""[(\[](?:www\.[^)\]]*|\.m4a|\.mp3|\.flac|\.ogg|320kbps?|128kbps?)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("""\bwww\.\S+""", RegexOption.IGNORE_CASE), " ")
        t = t.trim(' ', '_', '-')
        if (t.isEmpty()) return Parsed.Nothing
        val parts = t.split(Regex("""\s+[-–—]\s+"""))
        return when {
            parts.size >= 2 && parts[0].isNotBlank() && parts.last().isNotBlank() ->
                Parsed.Both(parts[0].trim(), parts.drop(1).joinToString(" - ").trim())
            else -> Parsed.TitleOnly(t)
        }
    }

    // a caption is stricter than a file name: no extensions to strip
    fun parseCaption(raw: String): Parsed {
        var t = raw.trim()
        if (t.isEmpty()) return Parsed.Nothing
        t = t.replace(Regex("""[(\[](?:www\.[^)\]]*)[)\]]""", RegexOption.IGNORE_CASE), " ")
        t = t.trim(' ', '_', '-')
        if (t.isEmpty()) return Parsed.Nothing
        val parts = t.split(Regex("""\s+[-–—]\s+"""))
        return when {
            parts.size >= 2 && parts[0].isNotBlank() && parts.last().isNotBlank() ->
                Parsed.Both(parts[0].trim(), parts.drop(1).joinToString(" - ").trim())
            else -> Parsed.TitleOnly(t)
        }
    }
}
