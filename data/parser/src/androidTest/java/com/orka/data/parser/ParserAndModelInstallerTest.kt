package com.orka.data.parser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.orka.core.model.ClarificationReason
import com.orka.core.model.ModelAvailability
import com.orka.core.model.ParseMode
import com.orka.core.model.ParserContext
import com.orka.core.model.PrimitiveType
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParserAndModelInstallerTest {
    private fun modelFile(context: android.content.Context): File =
        File(context.filesDir, "model/gemma-4-E4B-it.litertlm")

    @Test
    fun parseFallsBackWhenModelIsMissing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        modelFile(context).delete()
        val parser = DefaultTaskParser(context, CompanionKitModelInstaller(context))

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
    fun parseFallsBackWhenModelFileIsBelowMinimumValidSize() = runBlocking {
        // A placeholder/corrupt file below the 10MB validity threshold must never be treated as a
        // usable model — this used to report ParseMode.GEMMA without ever loading an engine.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = modelFile(context).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9, 8, 7, 6))
        }
        val parser = DefaultTaskParser(context, CompanionKitModelInstaller(context))

        val result = parser.parse(
            rawInput = "Prepare presentation by tomorrow evening",
            context = ParserContext(
                now = ZonedDateTime.parse("2026-03-30T09:00:00Z[UTC]"),
                zoneId = ZoneId.of("UTC"),
            ),
        )

        assertThat(result.draft.parseMode).isEqualTo(ParseMode.FALLBACK)

        model.delete()
    }

    @Test
    fun modelInstallerCopiesLocalCompanionKit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "gemma-test.litertlm").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
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

        val result = installer.installFromCompanionKit(File(context.cacheDir, "missing.litertlm").absolutePath)

        assertThat(result.availability).isEqualTo(ModelAvailability.FAILED)
    }

    @Test
    fun detectReportsNotInstalledWhenNeitherImportedNorPushedModelExists() = runBlocking {
        // No adb-pushed model at /data/local/tmp and no prior companion-kit import: the model
        // is no longer bundled into the APK, so this is the expected out-of-the-box state.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        modelFile(context).delete()
        val installer = CompanionKitModelInstaller(context)

        val result = installer.installBundledModelIfAvailable()

        assertThat(result.availability).isEqualTo(ModelAvailability.NOT_INSTALLED)
    }

    @Test
    fun meetingAtNinePmResolvesToTodayWhenFutureInIst() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parser = DefaultTaskParser(context, CompanionKitModelInstaller(context))
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.of(2026, 3, 30, 14, 35, 0, 0, zone)

        val result = parser.parse(
            rawInput = "meeting at 9pm",
            context = ParserContext(now = now, zoneId = zone),
        )

        val expected = ZonedDateTime.of(2026, 3, 30, 21, 0, 0, 0, zone).toInstant()
        assertThat(result.draft.primitiveType).isEqualTo(PrimitiveType.EVENT)
        assertThat(result.draft.eventStartTime).isEqualTo(expected)
        assertThat(result.draft.deadline).isEqualTo(expected)
        assertThat(result.draft.clarificationNeeded).isFalse()
    }

    @Test
    fun meetingAtNinePmResolvesToTomorrowWhenPassedInIst() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parser = DefaultTaskParser(context, CompanionKitModelInstaller(context))
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.of(2026, 3, 30, 22, 0, 0, 0, zone)

        val result = parser.parse(
            rawInput = "meeting at 9pm",
            context = ParserContext(now = now, zoneId = zone),
        )

        val expected = ZonedDateTime.of(2026, 3, 31, 21, 0, 0, 0, zone).toInstant()
        assertThat(result.draft.primitiveType).isEqualTo(PrimitiveType.EVENT)
        assertThat(result.draft.deadline).isEqualTo(expected)
        assertThat(result.draft.clarificationNeeded).isFalse()
    }

    @Test
    fun thisFridayOnSaturdayTriggersAmbiguousDayClarification() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parser = DefaultTaskParser(context, CompanionKitModelInstaller(context))
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.of(2026, 4, 4, 9, 0, 0, 0, zone) // Saturday

        val result = parser.parse(
            rawInput = "meeting this friday at 9pm",
            context = ParserContext(now = now, zoneId = zone),
        )

        assertThat(result.draft.primitiveType).isEqualTo(PrimitiveType.EVENT)
        assertThat(result.draft.clarificationNeeded).isTrue()
        assertThat(result.draft.clarificationReason).isEqualTo(ClarificationReason.AMBIGUOUS_DAY_REFERENCE)
    }

    @Test
    fun pastTimeTodayTriggersDateInPastClarification() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parser = DefaultTaskParser(context, CompanionKitModelInstaller(context))
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.of(2026, 4, 2, 14, 35, 0, 0, zone)

        val result = parser.parse(
            rawInput = "meeting today at 9am",
            context = ParserContext(now = now, zoneId = zone),
        )

        assertThat(result.draft.clarificationNeeded).isTrue()
        assertThat(result.draft.clarificationReason).isEqualTo(ClarificationReason.DATE_IN_PAST)
    }

    @Test
    fun vagueTemporalExpressionTriggersClarification() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parser = DefaultTaskParser(context, CompanionKitModelInstaller(context))
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.of(2026, 3, 30, 14, 35, 0, 0, zone)

        val result = parser.parse(
            rawInput = "pay bill soon",
            context = ParserContext(now = now, zoneId = zone),
        )

        assertThat(result.draft.clarificationNeeded).isTrue()
        assertThat(result.draft.clarificationReason).isEqualTo(ClarificationReason.VAGUE_TEMPORAL_EXPRESSION)
    }
}
