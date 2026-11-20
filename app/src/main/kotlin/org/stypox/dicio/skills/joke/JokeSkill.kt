package org.stypox.dicio.skills.joke

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.json.JSONObject
import org.stypox.dicio.sentences.Sentences.Joke
import org.stypox.dicio.util.ConnectionUtils

class JokeSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Joke>,
    private val resolvedLocale: String,
) : StandardRecognizerSkill<Joke>(correspondingSkillInfo, data) {
    override suspend fun generateOutput(ctx: SkillContext, inputData: Joke): SkillOutput {
        if (resolvedLocale in OFFLINE_JOKE_LOCALES) {
            // no online joke API has jokes in these languages, so a few are bundled with the app
            val (setup, delivery) = ctx.android.assets.open("jokes/$resolvedLocale.txt")
                .bufferedReader().useLines { lines ->
                    lines.filter { it.isNotBlank() && !it.startsWith("#") }
                        .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 } }
                        .toList()
                }
                .random()
            return JokeOutput.Success(setup = setup, delivery = delivery)
        } else if (resolvedLocale == "en") {
            val joke: JSONObject = ConnectionUtils.getPageJson(RANDOM_JOKE_URL_EN)
            return JokeOutput.Success(
                setup = joke.getString("setup"),
                delivery = joke.getString("punchline")
            )
        } else {
            val joke: JSONObject = ConnectionUtils.getPageJson(
                "$RANDOM_JOKE_URL?lang=$resolvedLocale&safe-mode&type=twopart"
            )
            return JokeOutput.Success(
                setup = joke.getString("setup"),
                delivery = joke.getString("delivery")
            )
        }
    }

    companion object {
        private const val RANDOM_JOKE_URL = "https://v2.jokeapi.dev/joke/Any"
        private const val RANDOM_JOKE_URL_EN = "https://official-joke-api.appspot.com/random_joke"
        /** Locales whose jokes are bundled in `assets/jokes/<locale>.txt` */
        private val OFFLINE_JOKE_LOCALES = listOf("ru")
        val JOKE_SUPPORTED_LOCALES = listOf(
            "cs", "de", "en", "es", "fr", "pt"
        ) + OFFLINE_JOKE_LOCALES
    }
}
