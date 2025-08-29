import java.util.regex.Pattern

class JavaScriptUrlExtractor {

    /**
     * Extracts URLs from obfuscated JavaScript code using eval with packed functions
     * @param obfuscatedScript The obfuscated JavaScript code starting with 'eval(function...'
     * @return List of extracted URLs
     */
    fun extractUrlsFromObfuscatedScript(obfuscatedScript: String): List<String> {
        try {
            // First, decode the packed JavaScript
            val decodedScript = decodePacker(obfuscatedScript)

            // Extract URLs from the decoded script
            return extractUrlsFromDecodedScript(decodedScript)
        } catch (e: Exception) {
            println("Error extracting URLs: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Decodes JavaScript packed with Dean Edwards' packer
     */
    private fun decodePacker(packedScript: String): String {
        // Extract the packed data using regex
        val packerPattern = """eval\(function\(p,a,c,k,e,d\)\{.*?return p\}\('([^']+)',(\d+),(\d+),'([^']+)'\.split\('\|'\)""".toRegex()
        val matchResult = packerPattern.find(packedScript) ?: return packedScript

        val (payload, radix, count, keywords) = matchResult.destructured

        val keywordArray = keywords.split('|').toTypedArray()
        val radixInt = radix.toInt()

        // Decode the payload
        var result = payload
        for (i in count.toInt() - 1 downTo 0) {
            val keyword = keywordArray.getOrNull(i) ?: ""
            if (keyword.isNotEmpty()) {
                val pattern = "\\b${i.toString(radixInt)}\\b".toRegex()
                result = result.replace(pattern, keyword)
            }
        }

        return result
    }

    /**
     * Extracts URLs from decoded JavaScript
     */
    private fun extractUrlsFromDecodedScript(script: String): List<String> {
        val urls = mutableListOf<String>()

        // Pattern to match HTTP/HTTPS URLs
        val urlPattern = """https?://[^\s"',;\[\]{}()]+""".toRegex()

        // Find all URLs in the script
        urlPattern.findAll(script).forEach { matchResult ->
            val url = matchResult.value
            // Clean up any trailing characters that might not be part of the URL
            val cleanUrl = cleanUrl(url)
            if (isValidVideoUrl(cleanUrl)) {
                urls.add(cleanUrl)
            }
        }

        return urls.distinct()
    }

    /**
     * Cleans up extracted URL by removing trailing punctuation
     */
    private fun cleanUrl(url: String): String {
        return url.trimEnd(',', '"', '\'', ';', ')', '}', ']')
    }

    /**
     * Checks if URL is likely a video URL based on common patterns
     */
    private fun isValidVideoUrl(url: String): Boolean {
        return url.contains(Regex("\\.(mp4|m3u8|mpd|avi|mkv|webm|flv)")) ||
                url.contains("stream") ||
                url.contains("video") ||
                url.contains("cdn") ||
                url.contains(".jpg") ||
                url.contains(".png") // for thumbnails
    }

    /**
     * Alternative method using regex patterns specific to JWPlayer configurations
     */
    fun extractUrlsFromJWPlayerScript(script: String): List<ExtractedUrl> {
        val urls = mutableListOf<ExtractedUrl>()

        try {
            // First decode if it's packed
            val decodedScript = if (script.startsWith("eval(function")) {
                decodePacker(script)
            } else {
                script
            }

            // Pattern to match file and label pairs in JWPlayer sources
            val sourcePattern = """file:\s*["']([^"']+)["'].*?label:\s*["']([^"']+)["']""".toRegex()

            sourcePattern.findAll(decodedScript).forEach { match ->
                val (fileUrl, label) = match.destructured
                if (isValidVideoUrl(fileUrl)) {
                    urls.add(ExtractedUrl(fileUrl, label))
                }
            }

            // Also look for image/thumbnail URLs
            val imagePattern = """image:\s*["']([^"']+)["']""".toRegex()
            imagePattern.find(decodedScript)?.let { match ->
                val imageUrl = match.groupValues[1]
                urls.add(ExtractedUrl(imageUrl, "thumbnail"))
            }

        } catch (e: Exception) {
            println("Error in JWPlayer extraction: ${e.message}")
        }

        return urls
    }

    data class ExtractedUrl(
        val url: String,
        val label: String = "",
        val quality: String = extractQuality(label)
    ) {
        companion object {
            private fun extractQuality(label: String): String {
                val qualityPattern = """(\d+p)""".toRegex()
                return qualityPattern.find(label)?.value ?: "unknown"
            }
        }
    }
}

// Usage example
fun main() {

}

// Extension functions for convenience
fun String.extractUrls(): List<String> {
    return JavaScriptUrlExtractor().extractUrlsFromObfuscatedScript(this)
}

fun String.extractUrlsWithLabels(): List<JavaScriptUrlExtractor.ExtractedUrl> {
    return JavaScriptUrlExtractor().extractUrlsFromJWPlayerScript(this)
}