package com.orka.core.database

import com.google.common.truth.Truth.assertThat
import com.orka.core.model.TaskCategory
import org.junit.Test

class OrkaTypeConvertersTest {
    private val converters = OrkaTypeConverters()

    @Test
    fun stringSetRoundTripsWithReservedCharacters() {
        val original = setOf("deadline", "title,subtitle", "path\\segment", "key:value")

        val encoded = converters.fromStringSet(original)
        val decoded = converters.toStringSet(encoded)

        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun stringMapRoundTripsDeterministically() {
        val original = mapOf(
            "title" to "prepare, refine, deliver",
            "path" to "docs\\report",
            "ratio" to "10:30",
        )

        val encoded = converters.fromStringMap(original)
        val decoded = converters.toStringMap(encoded)

        assertThat(encoded).isEqualTo(converters.fromStringMap(original))
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun floatMapRoundTripsCategoryValues() {
        val original = mapOf(
            TaskCategory.PROFESSIONAL to 0.75f,
            TaskCategory.ACADEMIC to 0.2f,
        )

        val encoded = converters.fromFloatMap(original)
        val decoded = converters.toFloatMap(encoded)

        assertThat(decoded).isEqualTo(original)
    }
}
