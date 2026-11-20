package org.stypox.dicio.skills.search

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class BrowserSearchUrlTest : StringSpec({
    "empty template uses the default URL" {
        buildBrowserSearchUrl("", "weather today") shouldBe
                "https://duckduckgo.com/?q=weather+today"
    }

    "placeholder is replaced by the encoded query" {
        buildBrowserSearchUrl("https://www.startpage.com/do/search?q=%s&cat=web", "a&b c") shouldBe
                "https://www.startpage.com/do/search?q=a%26b+c&cat=web"
    }

    "query is appended when there is no placeholder" {
        buildBrowserSearchUrl("https://example.org/search?q=", "погода") shouldBe
                "https://example.org/search?q=%D0%BF%D0%BE%D0%B3%D0%BE%D0%B4%D0%B0"
    }
})
