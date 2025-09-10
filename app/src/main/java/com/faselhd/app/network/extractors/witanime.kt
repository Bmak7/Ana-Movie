package com.faselhd.app.network.extractors
import io.github.bonigarcia.wdm.WebDriverManager
import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.TimeoutException
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.time.Duration

fun main() {
    val targetUrl = "https://witanime.red/episode/sakamoto-days-part-2-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1/"
    val finalUrls = extractServerUrls(targetUrl)

    if (finalUrls.isNotEmpty()) {
        println("\n--- ✅ All Extracted URLs ---")
        finalUrls.forEach { (name, url) ->
            println("$name: $url")
        }
    } else {
        println("\n--- ❌ No URLs were extracted. ---")
    }
}

fun extractServerUrls(episodeUrl: String): Map<String, String> {
    println("Setting up the browser...")

    // Automatically download and set up the driver for Chrome
    WebDriverManager.chromedriver().setup()

    // Configure Chrome to run in headless mode (no UI)
    val chromeOptions = ChromeOptions().apply {
        addArguments("--headless")
        addArguments("--no-sandbox")
        addArguments("--disable-dev-shm-usage")
        addArguments("--window-size=1920,1080")
        addArguments("user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
    }

    var driver: ChromeDriver? = null
    val extractedUrls = mutableMapOf<String, String>()

    try {
        driver = ChromeDriver(chromeOptions)
        val jsExecutor = driver as JavascriptExecutor

        // Set a timeout for waiting for elements
        val wait = WebDriverWait(driver, Duration.ofSeconds(15))

        println("Navigating to: $episodeUrl")
        driver.get(episodeUrl)

        val serverLinksXPath = "//ul[@id='episode-servers']/li/a"

        // Wait for the server list to be loaded onto the page
        wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(By.xpath(serverLinksXPath)))

        val numServers = driver.findElements(By.xpath(serverLinksXPath)).size
        println("Found $numServers server links. Starting extraction...")

        for (i in 0 until numServers) {
            try {
                // Re-find elements in each iteration to avoid StaleElementReferenceException
                val serverLink = driver.findElements(By.xpath(serverLinksXPath))[i]
                val serverName = serverLink.text.trim()

                println("  -> Processing server: '$serverName'")

                // THE KEY FIX: Use JavaScript to click, bypassing any overlays
                jsExecutor.executeScript("arguments[0].click();", serverLink)

                // Wait for the video iframe to appear after the click
                val iframeXPath = "//div[@id='iframe-container']/iframe"
                val iframeElement = wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath(iframeXPath)))

                val videoUrl = iframeElement.getAttribute("src")

                if (!videoUrl.isNullOrBlank() && "about:blank" !in videoUrl) {
                    println("     [SUCCESS] Extracted URL: $videoUrl")
                    extractedUrls[serverName] = videoUrl
                } else {
                    println("     [ERROR] Failed to get a valid URL for '$serverName'")
                }

            } catch (e: TimeoutException) {
                println("     [ERROR] Timed out waiting for iframe to load.")
                continue // Move to the next server
            } catch (e: Exception) {
                println("     [ERROR] An unexpected error occurred: ${e.message}")
                continue // Move to the next server
            }
        }
    } finally {
        println("Closing the browser.")
        driver?.quit() // Safely quit the driver if it was initialized
    }

    return extractedUrls
}
