package com.orka.data.parser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.orka.core.model.ModelAvailability
import com.orka.core.model.ParseMode
import com.orka.core.model.ParserContext
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParserAndModelInstallerTest {
    @Test
    fun parseFallsBackWhenModelIsMissing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parser = DefaultTaskParser(context)

        val result = parser.parse(
            rawInput = "Prepare presentation by tomorrow evening",
            context = ParserContext(
                now = ZonedDateTime.parse("2026-03-30T09:00:00Z[UTC]"),
                zoneId = ZoneId.of("UTC"),
            ),
        )

        assertThat(result.draft.parseMode).isEqualTo(ParseMode.FALLBACK)
        assertThat(result.draft.deadline).isNotNull()
    }

    @Test
    fun modelInstallerCopiesLocalCompanionKit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "gemma-test.gguf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val installer = CompanionKitModelInstaller(context)

        val result = installer.installFromCompanionKit(source.absolutePath)

        assertThat(result.availability).isEqualTo(ModelAvailability.READY)
        assertThat(result.modelPath).isNotNull()
        assertThat(File(result.modelPath!!).exists()).isTrue()
        assertThat(installer.observeState().first().availability).isEqualTo(ModelAvailability.READY)

        installer.reset()
        source.delete()
    }

    @Test
    fun modelInstallerReportsFailureForMissingFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = CompanionKitModelInstaller(context)

        val result = installer.installFromCompanionKit(File(context.cacheDir, "missing.gguf").absolutePath)

        assertThat(result.availability).isEqualTo(ModelAvailability.FAILED)
    }
}
