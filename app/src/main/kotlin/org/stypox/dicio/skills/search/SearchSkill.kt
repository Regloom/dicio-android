package org.stypox.dicio.skills.search

import androidx.core.net.toUri
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.jsoup.Jsoup
import kotlinx.coroutines.flow.first
import org.jsoup.nodes.Document
import org.stypox.dicio.sentences.Sentences.Search
import org.stypox.dicio.skills.search.SearchInfo.searchDataStore
import org.stypox.dicio.util.ConnectionUtils
import org.stypox.dicio.util.LocaleUtils
import org.stypox.dicio.util.ShareUtils

class SearchSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Search>,
    // the following two params are exposed because they are used in SearchOutput
    specificity: Specificity = data.specificity,
    private val askAgainIfNoResult: Boolean = true,
) : StandardRecognizerSkill<Search>(correspondingSkillInfo, data, specificity) {
    override suspend fun generateOutput(ctx: SkillContext, inputData: Search): SkillOutput {
        val query = when (inputData) { is Search.Query -> inputData.what }
        return search(ctx, query, askAgainIfNoResult)
    }
}

/** Used when the browser search URL setting is empty. */
internal const val DEFAULT_BROWSER_SEARCH_URL = "https://duckduckgo.com/?q=%s"
private const val BROWSER_SEARCH_URL_QUERY_PLACEHOLDER = "%s"

/** Runs the search with the engine chosen in the skill settings. */
internal suspend fun search(
    ctx: SkillContext,
    query: String?,
    askAgainIfNoResult: Boolean,
): SearchOutput {
    val settings = ctx.android.searchDataStore.data.first()
    return when (settings.engine) {
        SearchEngine.SEARCH_ENGINE_BROWSER -> searchInBrowser(ctx, query, settings.browserUrl)
        else -> searchOnDuckDuckGo(ctx, query, askAgainIfNoResult)
    }
}

internal fun buildBrowserSearchUrl(urlTemplate: String, query: String): String {
    val template = urlTemplate.ifBlank { DEFAULT_BROWSER_SEARCH_URL }
    val encodedQuery = ConnectionUtils.urlEncode(query)
    return if (template.contains(BROWSER_SEARCH_URL_QUERY_PLACEHOLDER)) {
        template.replace(BROWSER_SEARCH_URL_QUERY_PLACEHOLDER, encodedQuery)
    } else {
        // no placeholder: assume the query goes at the end, e.g. "https://example.org/search?q="
        template + encodedQuery
    }
}

private fun searchInBrowser(
    ctx: SkillContext,
    query: String?,
    urlTemplate: String,
): SearchOutput {
    if (query.isNullOrBlank()) {
        return SearchOutput.NoSearchTerm
    }
    ShareUtils.openUrlInBrowser(ctx.android, buildBrowserSearchUrl(urlTemplate, query))
    return SearchOutput.OpenedInBrowser(query)
}

private const val DUCK_DUCK_GO_SEARCH_URL = "https://duckduckgo.com/html/?q="

private val DUCK_DUCK_GO_SUPPORTED_LOCALES = listOf(
    "ar-es", "au-en", "at-de", "be-fr", "be-nl", "br-pt", "bg-bg", "ca-en", "ca-fr",
    "ct-ca", "cl-es", "cn-zh", "co-es", "hr-hr", "cz-cs", "dk-da", "ee-et", "fi-fi",
    "fr-fr", "de-de", "gr-el", "hk-tz", "hu-hu", "in-en", "id-en", "ie-en", "il-en",
    "it-it", "jp-jp", "kr-kr", "lv-lv", "lt-lt", "my-en", "mx-es", "nl-nl", "nz-en",
    "no-no", "pk-en", "pe-es", "ph-en", "pl-pl", "pt-pt", "ro-ro", "ru-ru", "xa-ar",
    "sg-en", "sk-sk", "sl-sl", "za-en", "es-ca", "es-es", "se-sv", "ch-de", "ch-fr",
    "tw-tz", "th-en", "tr-tr", "us-en", "us-es", "ua-uk", "uk-en", "vn-en"
)

internal fun searchOnDuckDuckGo(
    ctx: SkillContext,
    query: String?,
    askAgainIfNoResult: Boolean,
): SearchOutput {
    if (query.isNullOrBlank()) {
        return SearchOutput.NoSearchTerm
    }

    // find the locale supported by DuckDuckGo that matches the user locale the most
    val locale = LocaleUtils.resolveValueForSupportedLocale(
        ctx.locale,
        DUCK_DUCK_GO_SUPPORTED_LOCALES.associateBy {
            // DuckDuckGo locale names have first the country and then the language, but the locale
            // selection function assumes the opposite
            it.split("-").reversed().joinToString(separator = "-")
        }
    // default to English when no locale is supported
    ) ?: "us-en"

    // make request using headers
    val html: String = ConnectionUtils.getPage(
        DUCK_DUCK_GO_SEARCH_URL + ConnectionUtils.urlEncode(query),
        mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:143.0) Gecko/20100101 Firefox/143.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Host" to "html.duckduckgo.com",
            "Cookie" to "kl=$locale",
        )
    )

    // Sometimes DuckDuckGo replies with a recaptcha request, and no results are provided then
    if (html.contains("anomaly-modal__title")) {
        return SearchOutput.RecaptchaRequested
    }

    val document: Document = Jsoup.parse(html)
    val elements = document.select("div[class=links_main links_deep result__body]")
    val results: MutableList<SearchOutput.Data> = ArrayList()
    for (element in elements) {
        try {
            // the url is under the "uddg" query parameter
            val ddgUrl = element.select("a[class=result__a]").first()!!.attr("href")
            val url = ddgUrl.toUri().getQueryParameter("uddg")!!

            results.add(
                SearchOutput.Data(
                    title = element.select("a[class=result__a]").first()!!.text(),
                    thumbnailUrl = "https:" + element.select("img[class=result__icon__img]")
                        .first()!!.attr("src"),
                    url = url,
                    description = element.select("a[class=result__snippet]").first()!!.text(),
                )
            )
        } catch (_: NullPointerException) {
        }
    }

    return if (results.isEmpty()) {
        if (askAgainIfNoResult) {
            SearchOutput.NoResultAskAgain
        } else {
            SearchOutput.NoResultStop
        }
    } else {
        SearchOutput.Results(results)
    }
}
