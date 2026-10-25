package com.englishai.app

/** Cleans model/UI formatting before text is sent to speech synthesis. */
object EnglishTtsManager {
    fun sanitizeText(input: String): String {
        var text = input
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("(?m)^\\s*===+\\s*$"), " ")
            .replace(Regex("(?m)^\\s*(English AI|English IA|Assistant|AI)\\s*:\\s*"), "")
            .replace(Regex("[*_~\\x60]+"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // A bilingual reply is separated with ===; never speak the Portuguese side.
        text = text.substringBefore("===")
        return text.replace(Regex("\\s+"), " ").trim()
    }

    fun greeting(userName: String): String {
        val safeName = sanitizeText(userName).take(24)
        return if (safeName.isBlank()) "Good morning! Ready for a little English practice?"
        else "Hi, $safeName! Good morning. Ready for a little English practice?"
    }
}
