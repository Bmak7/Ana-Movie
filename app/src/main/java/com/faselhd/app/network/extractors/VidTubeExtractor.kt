import android.os.Parcelable
import com.faselhd.app.models.Subtitle
import com.faselhd.app.models.Video
import kotlinx.parcelize.Parcelize
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class PackedComponents(val p: String, val a: Int, val c: Int, val kString: String)

class VidTubeExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String): List<Video> {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return emptyList()
            }

            val htmlContent = response.body?.string() ?: return emptyList()
            response.close()

            val videoUrls = extractVideoUrlsFromHtml(htmlContent)

            return videoUrls.mapIndexed { index, videoUrl ->
                val quality = determineQuality(videoUrl,index)
                Video(
                    url = videoUrl,
                    quality = quality,
                    videoUrl = videoUrl,
                    resolution = quality,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Referer" to url
                    ),
                    subtitles = null
                )
            }

        } catch (e: IOException) {
            println("Error fetching video: ${e.message}")
            return emptyList()
        }
    }

    private fun extractVideoUrlsFromHtml(htmlCode: String): List<String> {
        val evalStart = htmlCode.indexOf("eval(function(p,a,c,k,e,d)")

        if (evalStart == -1) {
            println("Could not find the packed JavaScript in the HTML.")
            return emptyList()
        }

        // Find the end of this eval statement
        var bracketCount = 0
        var evalEnd = evalStart

        for (i in evalStart until htmlCode.length) {
            when (htmlCode[i]) {
                '(' -> bracketCount++
                ')' -> {
                    bracketCount--
                    if (bracketCount == 0) {
                        evalEnd = i + 1
                        break
                    }
                }
            }
        }

        if (evalEnd <= evalStart) {
            println("Could not find end of eval statement")
            return emptyList()
        }

        val packedScript = htmlCode.substring(evalStart, evalEnd)
        val deobfuscatedCode = deobfuscatePackedJs(packedScript)

        if (deobfuscatedCode.isEmpty()) {
            println("Deobfuscation failed.")
            return emptyList()
        }

        return findAllUrls(deobfuscatedCode)
    }

    private fun findAllUrls(text: String, prefix: String = "file:\"", suffix: String = "\""): List<String> {
        val urls = mutableListOf<String>()
        var startPos = 0

        while (true) {
            val pos = text.indexOf(prefix, startPos)
            if (pos == -1) break

            val urlStart = pos + prefix.length
            val urlEnd = text.indexOf(suffix, urlStart)
            if (urlEnd == -1) break

            val url = text.substring(urlStart, urlEnd)

            if (url.startsWith("http") && (url.contains(".mp4") || url.contains(".m3u8"))) {
                urls.add(url)
            }

            startPos = urlEnd + 1
        }

        return urls
    }

    private fun extractPackedComponents(packedJs: String): PackedComponents? {
        // Find all occurrences of "}('"
        val patternPositions = mutableListOf<Int>()
        var searchStart = 0

        while (true) {
            val pos = packedJs.indexOf("}('", searchStart)
            if (pos == -1) break
            patternPositions.add(pos)
            searchStart = pos + 1
        }

        if (patternPositions.isEmpty()) {
            return null
        }

        // Try the last occurrence
        val patternStart = patternPositions.last()
        val startPos = patternStart + 3

        // Find the first string (p parameter)
        var quoteCount = 0
        var pEnd = -1
        var i = startPos

        while (i < packedJs.length) {
            when {
                packedJs[i] == '\\' -> i += 2 // Skip escaped character
                packedJs[i] == '\'' -> {
                    quoteCount++
                    if (quoteCount == 1 && i + 1 < packedJs.length && packedJs[i + 1] == ',') {
                        pEnd = i
                        break
                    }
                    i++
                }
                else -> i++
            }
        }

        if (pEnd == -1) return null

        val p = packedJs.substring(startPos, pEnd)

        // Find the first number (a parameter)
        var numStart = pEnd + 2 // Skip ','
        while (numStart < packedJs.length && packedJs[numStart].isWhitespace()) {
            numStart++
        }

        var numEnd = numStart
        while (numEnd < packedJs.length && packedJs[numEnd].isDigit()) {
            numEnd++
        }

        if (numEnd == numStart) return null

        val a = try {
            packedJs.substring(numStart, numEnd).toInt()
        } catch (e: NumberFormatException) {
            return null
        }

        // Find the second number (c parameter)
        val commaPos = packedJs.indexOf(',', numEnd)
        if (commaPos == -1) return null

        var num2Start = commaPos + 1
        while (num2Start < packedJs.length && packedJs[num2Start].isWhitespace()) {
            num2Start++
        }

        var num2End = num2Start
        while (num2End < packedJs.length && packedJs[num2End].isDigit()) {
            num2End++
        }

        if (num2End == num2Start) return null

        val c = try {
            packedJs.substring(num2Start, num2End).toInt()
        } catch (e: NumberFormatException) {
            return null
        }

        // Find the keyword string (k parameter)
        val kQuoteStart = packedJs.indexOf('\'', num2End)
        if (kQuoteStart == -1) return null

        val kStart = kQuoteStart + 1

        // Find the end of the quoted string
        var kEnd = -1
        i = kStart
        while (i < packedJs.length) {
            when {
                packedJs[i] == '\\' -> i += 2 // Skip escaped character
                packedJs[i] == '\'' -> {
                    if (packedJs.substring(i).startsWith("'.split")) {
                        kEnd = i
                        break
                    }
                    i++
                }
                else -> i++
            }
        }

        if (kEnd == -1) return null

        val kString = packedJs.substring(kStart, kEnd)
        return PackedComponents(p, a, c, kString)
    }

    private fun getBaseAString(num: Int, base: Int): String {
        return if (num < base) {
            if (num < 10) num.toString() else (('a'.code + num - 10).toChar()).toString()
        } else {
            getBaseAString(num / base, base) + getBaseAString(num % base, base)
        }
    }

    private fun replaceWords(text: String, lookup: Map<String, String>): String {
        val result = StringBuilder()
        var i = 0

        while (i < text.length) {
            val char = text[i]

            if (char.isLetterOrDigit() || char == '_') {
                val wordStart = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) {
                    i++
                }

                val word = text.substring(wordStart, i)
                result.append(lookup[word] ?: word)
            } else {
                result.append(char)
                i++
            }
        }

        return result.toString()
    }

    private fun deobfuscatePackedJs(packedJs: String): String {
        return try {
            val components = extractPackedComponents(packedJs)
            if (components == null) {
                return ""
            }

            val (p, a, c, kString) = components

            // Handle escaped quotes and split by |
            val k = kString.replace("\\'", "'").split("|")

            // Build the dictionary for replacements
            val lookup = mutableMapOf<String, String>()
            for (i in c - 1 downTo 0) {
                val key = getBaseAString(i, a)
                val value = if (i < k.size && k[i].isNotEmpty()) k[i] else key
                lookup[key] = value
            }

            // Replace keywords manually by tokenizing
            replaceWords(p, lookup)

        } catch (e: Exception) {
            ""
        }
    }

    private fun determineQuality(videoUrl: String,index: Int): String {
        return when {
            videoUrl.contains("1080") || index == 0-> "1080p"
            videoUrl.contains("720") || index == 1-> "720p"
            videoUrl.contains("480") || index == 2-> "480p"
            videoUrl.contains("360") || index == 3-> "360p"
            videoUrl.contains(".m3u8") -> "HLS"
            else -> "Unknown"
        }
    }
}

//import com.faselhd.app.models.Video // Your project's Video model
//import okhttp3.OkHttpClient
//import okhttp3.Request
//import java.io.IOException
//
//class VidTubeExtractor(private val client: OkHttpClient) {
//
//    fun videosFromUrl(url: String): List<Video> {
//
//    }
//
//    private fun findPackedScript(html: String): String? {
//        val regex =  """eval\(function\(p,a,c,k,e,d\)\{.*?\}\(.*?\}\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
//        return regex.find(html)?.value
//    }
//
//    /**
//     * Deobfuscates the packed JavaScript code to make it human-readable.
//     * This version uses a character class [|] to robustly match the pipe
//     * character, avoiding the PatternSyntaxException.
//     */
//    private fun deobfuscate(packedJs: String): String {
//        println("packedJs: $packedJs")
//        try {
//            // We replace the problematic '\\|' with '[|]'. A character class
//            // matches the literal character without needing complex escapes.
//            val paramsRegex = """}\((.*),(\d+),(\d+),(.*?)\.split\('\\\|'\)""".toRegex()
//
//            val matchResult = paramsRegex.find(packedJs)
//            if (matchResult == null) {
//                println("Could not find deobfuscation parameters in the script. The regex did not match.")
//                return ""
//            }
//
//            var (p, aStr, cStr, kStr) = matchResult.destructured
//            val base = aStr.toInt()
//            val count = cStr.toInt()
//            val keywords = kStr.replace("\\'", "'").split('|')
//
//            val lookup = mutableMapOf<String, String>()
//            for (i in (count - 1) downTo 0) {
//                val key = i.toBase(base)
//                val value = keywords.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
//                lookup[key] = value
//            }
//
//            val wordRegex = """\b(\w+)\b""".toRegex()
//            return wordRegex.replace(p) { result ->
//                lookup[result.groupValues[1]] ?: result.value
//            }
//        } catch (e: Exception) {
//            println("Error during deobfuscation logic: ${e.message}")
//            e.printStackTrace()
//            return ""
//        }
//    }
//
//    private fun parseVideosFromScript(script: String, originalUrl: String): List<Video> {
//        val videoList = mutableListOf<Video>()
//        val videoInfoRegex = """file:"(https?://[^"]+?)",label:"([^"]+?)"""".toRegex()
//
//        videoInfoRegex.findAll(script).forEach { matchResult ->
//            val (videoUrl, qualityLabel) = matchResult.destructured
//            val resolution = qualityLabel.split(" ").lastOrNull { it.contains("p") } ?: ""
//
//            videoList.add(
//                Video(
//                    url = originalUrl,
//                    quality = qualityLabel,
//                    videoUrl = videoUrl,
//                    resolution = resolution
//                )
//            )
//        }
//        return videoList
//    }
//
//    private fun Int.toBase(base: Int): String {
//        if (this < 0) return ""
//        if (this < base) {
//            return if (this < 10) this.toString() else ('a' + (this - 10)).toString()
//        }
//        return (this / base).toBase(base) + (this % base).toBase(base)
//    }
//}
//
//
//
////package com.faselhd.app.network.extractors
////
////import android.util.Log
////import com.faselhd.app.models.Video
////import okhttp3.OkHttpClient
////import okhttp3.Request
////import org.jsoup.Jsoup
////import java.util.regex.Pattern
////
////
////class VidTubeExtractor(private val client: OkHttpClient) {
////
////    fun videosFromUrl(url: String): List<Video> {
////        val videos = mutableListOf<Video>()
////        try {
////            val request = Request.Builder()
////                .url(url)
////                .header("User-Agent", "Mozilla/5.0")
////                .build()
////
////            client.newCall(request).execute().use { response ->
////                if (!response.isSuccessful) return emptyList()
////
////                val htmlCode = response.body?.string() ?: return emptyList()
////
////                // Find the packed JS
////                // Find the packed JS (eval(function(p,a,c,k,e,d){...}))
////                val scriptRegex = Regex(
////                    """eval\(function\(p,a,c,k,e,d\)\{.*?\}\((.*?)\)\)""",
////                    setOf(RegexOption.DOT_MATCHES_ALL)
////                )
////                val match = scriptRegex.find(htmlCode) ?: return emptyList()
////
////                val packedScript = match.groupValues[0]
////                val deobfuscated = deobfuscatePackedJs(packedScript)
////
////                if (deobfuscated.isNotEmpty()) {
////                    // Match file:"..." or file:'...'
////                    val videoRegex = Regex("""file\s*:\s*["'](https?://[^\s"']+\.(?:mp4|m3u8)[^\s"']*)["']""")
////                    val urls = videoRegex.findAll(deobfuscated).map { it.groupValues[1] }.toList()
////
////                    urls.forEach { videoUrl ->
////                        videos.add(
////                            Video(
////                                url = videoUrl,
////                                quality = "Auto",
////                                videoUrl = videoUrl,
////                                headers = mapOf("Referer" to url)
////                            )
////                        )
////                    }
////                }
////            }
////        } catch (e: Exception) {
////            Log.e("VidTubeExtractor", "❌ Error extracting: ${e.message}")
////        }
////        return videos
////    }
////
////    // === Same unpacker logic as before ===
////    private fun deobfuscatePackedJs(packedJs: String): String {
////        println("packedJs ee:(${packedJs.length}) ${packedJs.take(100)}...")
////
////        return try {
////            // Use the exact same regex as the working Python version
////            val regex = Regex("""\}\('(.*)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""")
////            val match = regex.find(packedJs)
////
////            if (match == null) {
////                println("Could not find the core components for deobfuscation.")
////                return ""
////            }
////
////            println("✅ Match found!")
////
////            val (p, aStr, cStr, k) = match.destructured
////            val a = aStr.toInt()
////            val c = cStr.toInt()
////
////            println("p length: ${p.length}")
////            println("a (radix): $a")
////            println("c (count): $c")
////            println("k length: ${k.length}")
////
////            // Handle escaped quotes exactly like Python
////            val keywords = k.replace("\\'", "'").split('|')
////
////            // Convert number to base-a string exactly like Python
////            fun getBaseAString(num: Int, base: Int): String {
////                return if (num < base) {
////                    if (num < 10) num.toString() else ('a' + (num - 10)).toString()
////                } else {
////                    getBaseAString(num / base, base) + getBaseAString(num % base, base)
////                }
////            }
////
////            // Build lookup dictionary exactly like Python (reverse order)
////            val lookup = mutableMapOf<String, String>()
////            for (i in (c - 1) downTo 0) {
////                val key = getBaseAString(i, a)
////                val value = if (i < keywords.size && keywords[i].isNotEmpty()) keywords[i] else key
////                lookup[key] = value
////            }
////
////            println("Built lookup with ${lookup.size} entries")
////
////            // Replace using word boundaries exactly like Python
////            val wordRegex = Regex("""\b(\w+)\b""")
////            val deobfuscated = wordRegex.replace(p) { matchResult ->
////                val word = matchResult.groupValues[1]
////                lookup[word] ?: word
////            }
////
////            println("✅ Deobfuscation completed successfully")
////            return deobfuscated
////
////        } catch (e: Exception) {
////            println("An error occurred during deobfuscation: ${e.message}")
////            e.printStackTrace()
////            return ""
////        }
////    }
////
////
////
////}
////
