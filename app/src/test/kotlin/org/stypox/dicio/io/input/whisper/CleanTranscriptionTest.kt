package org.stypox.dicio.io.input.whisper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.input.whisper.WhisperInputDevice.Companion.cleanTranscription

class CleanTranscriptionTest : StringSpec({
    "punctuation is removed and text is lowercased" {
        cleanTranscription(" Который час?") shouldBe "который час"
        cleanTranscription("Поставь таймер на 7 минут.") shouldBe "поставь таймер на 7 минут"
    }

    "non-speech annotations are removed" {
        cleanTranscription("[музыка] Включи фонарик (смех)") shouldBe "включи фонарик"
    }

    "known hallucinations become empty" {
        cleanTranscription("Субтитры сделал DimaTorzok") shouldBe ""
        cleanTranscription("Продолжение следует...") shouldBe ""
    }
})
