package org.stypox.dicio.skills.search

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import kotlinx.coroutines.launch
import org.dicio.skill.skill.Skill
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.ui.ListSetting
import org.stypox.dicio.settings.ui.StringSetting

object SearchInfo : SkillInfo("search") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_search)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_search)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Search)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Search[ctx.sentencesLanguage] ?: return null
        return SearchSkill(SearchInfo, data)
    }

    // no need to use Hilt injection here, let DataStore take care of handling the singleton itself
    internal val Context.searchDataStore by dataStore(
        fileName = "skill_settings_search.pb",
        serializer = SkillSettingsSearchSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler {
            SkillSettingsSearchSerializer.defaultValue
        },
    )

    override val renderSettings: @Composable () -> Unit get() = @Composable {
        val dataStore = LocalContext.current.searchDataStore
        val data by dataStore.data.collectAsState(SkillSettingsSearchSerializer.defaultValue)
        val scope = rememberCoroutineScope()

        Column {
            ListSetting(
                title = stringResource(R.string.pref_search_engine),
                possibleValues = listOf(
                    ListSetting.Value(
                        value = SearchEngine.SEARCH_ENGINE_DUCKDUCKGO,
                        name = stringResource(R.string.pref_search_engine_duckduckgo),
                        description = stringResource(R.string.pref_search_engine_duckduckgo_description),
                    ),
                    ListSetting.Value(
                        value = SearchEngine.SEARCH_ENGINE_BROWSER,
                        name = stringResource(R.string.pref_search_engine_browser),
                        description = stringResource(R.string.pref_search_engine_browser_description),
                    ),
                ),
            ).Render(
                value = when (val engine = data.engine) {
                    SearchEngine.UNRECOGNIZED -> SearchEngine.SEARCH_ENGINE_DUCKDUCKGO
                    else -> engine
                },
                onValueChange = { engine ->
                    scope.launch {
                        dataStore.updateData { settings ->
                            settings.toBuilder().setEngine(engine).build()
                        }
                    }
                },
            )

            if (data.engine == SearchEngine.SEARCH_ENGINE_BROWSER) {
                StringSetting(
                    title = stringResource(R.string.pref_search_browser_url),
                    descriptionWhenEmpty = stringResource(
                        R.string.pref_search_browser_url_default, DEFAULT_BROWSER_SEARCH_URL
                    ),
                ).Render(
                    value = data.browserUrl,
                    onValueChange = { browserUrl ->
                        scope.launch {
                            dataStore.updateData { settings ->
                                settings.toBuilder().setBrowserUrl(browserUrl.trim()).build()
                            }
                        }
                    },
                )
            }
        }
    }
}
