package org.stypox.dicio.skills.search

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object SkillSettingsSearchSerializer : Serializer<SkillSettingsSearch> {
    override val defaultValue: SkillSettingsSearch = SkillSettingsSearch.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): SkillSettingsSearch {
        try {
            return SkillSettingsSearch.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto", exception)
        }
    }

    override suspend fun writeTo(t: SkillSettingsSearch, output: OutputStream) {
        t.writeTo(output)
    }
}
