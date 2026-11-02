package com.hershaleh.videostop

/** חסימה לפי כתובת אתר בדפדפן. כלל יכול להיות דומיין ("youtube.com") או דומיין + נתיב ("facebook.com/watch"). */
object UrlRules {

    private val BLOCKED = listOf(
        "youtube.com", "youtu.be", "youtubekids.com",
        "vimeo.com", "tiktok.com", "twitch.tv", "dailymotion.com",
        "netflix.com", "primevideo.com", "disneyplus.com", "hulu.com", "max.com",
        "crunchyroll.com", "rumble.com", "bitchute.com", "odysee.com",
        "fb.watch", "facebook.com/watch", "facebook.com/reel", "facebook.com/videos",
        "instagram.com/reel", "instagram.com/reels", "instagram.com/tv"
    )

    /** מקבל את הטקסט משורת הכתובת (עם או בלי http/www/נתיב). */
    fun isBlocked(raw: String): Boolean {
        var s = raw.trim().lowercase()
        if (s.isEmpty() || s.contains(' ')) return false
        s = s.substringAfter("://").removePrefix("www.")
        val slash = s.indexOf('/')
        val host = (if (slash >= 0) s.substring(0, slash) else s).substringBefore(':')
        val path = if (slash >= 0) s.substring(slash) else ""
        if (!host.contains('.')) return false

        for (rule in BLOCKED) {
            val ruleHost = rule.substringBefore('/')
            val rulePath = if (rule.contains('/')) "/" + rule.substringAfter('/') else ""
            val hostMatches = host == ruleHost || host.endsWith(".$ruleHost")
            if (hostMatches && (rulePath.isEmpty() || path.startsWith(rulePath))) return true
        }
        return false
    }
}
