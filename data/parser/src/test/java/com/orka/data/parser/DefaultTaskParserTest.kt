package com.orka.data.parser

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.orka.core.model.ModelAvailability
import com.orka.core.model.ModelInstallState
import com.orka.core.model.ParserContext
import com.orka.core.testing.FakeModelInstaller
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class DefaultTaskParserTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private lateinit var context: Context
    private val modelInstaller = FakeModelInstaller(ModelInstallState(ModelAvailability.NOT_INSTALLED))
    private lateinit var parser: DefaultTaskParser

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        parser = DefaultTaskParser(context, modelInstaller)
    }

    @Test
    fun parses24HourRailwayTimeCorrectly() = runBlocking {
        // Monday 10:00 AM IST
        val nowIst = ZonedDateTime.of(2026, 3, 30, 10, 0, 0, 0, ist)
        val result = parser.parse(
            rawInput = "submit tax audit report at 14:00",
            context = ParserContext(now = nowIst),
        )

        val draft = result.draft
        assertThat(draft.deadline).isNotNull()
        val deadlineIst = draft.deadline!!.atZone(ist)
        assertThat(deadlineIst.hour).isEqualTo(14)
        assertThat(deadlineIst.minute).isEqualTo(0)
    }

    @Test
    fun parsesEvening24HourTimeCorrectly() = runBlocking {
        val nowIst = ZonedDateTime.of(2026, 3, 30, 10, 0, 0, 0, ist)
        val result = parser.parse(
            rawInput = "client status meeting at 20:30",
            context = ParserContext(now = nowIst),
        )

        val draft = result.draft
        assertThat(draft.deadline).isNotNull()
        val deadlineIst = draft.deadline!!.atZone(ist)
        assertThat(deadlineIst.hour).isEqualTo(20)
        assertThat(deadlineIst.minute).isEqualTo(30)
    }

    @Test
    fun parsesCompoundDurationWithExplicitTime() = runBlocking {
        // Now: Monday 30 March 2026 at 10:00 AM IST
        val nowIst = ZonedDateTime.of(2026, 3, 30, 10, 0, 0, 0, ist)
        val result = parser.parse(
            rawInput = "finalize deck in 3 days 5pm",
            context = ParserContext(now = nowIst),
        )

        val draft = result.draft
        assertThat(draft.deadline).isNotNull()
        val deadlineIst = draft.deadline!!.atZone(ist)
        // 3 days after Monday 30 March is Thursday 2 April
        assertThat(deadlineIst.dayOfMonth).isEqualTo(2)
        assertThat(deadlineIst.monthValue).isEqualTo(4)
        // Time should be 5:00 PM (17:00), not the current 10:00 AM!
        assertThat(deadlineIst.hour).isEqualTo(17)
        assertThat(deadlineIst.minute).isEqualTo(0)
    }

    @Test
    fun differentiatesThisDayFromNextDayIndianEnglish() = runBlocking {
        // Now: Monday 30 March 2026 at 10:00 AM IST
        val nowIst = ZonedDateTime.of(2026, 3, 30, 10, 0, 0, 0, ist)

        val thisFridayResult = parser.parse(
            rawInput = "team review this friday at 4pm",
            context = ParserContext(now = nowIst),
        )
        val thisFriday = thisFridayResult.draft.deadline!!.atZone(ist)
        // This Friday is April 3 (+4 days)
        assertThat(thisFriday.dayOfMonth).isEqualTo(3)
        assertThat(thisFriday.monthValue).isEqualTo(4)

        val nextFridayResult = parser.parse(
            rawInput = "team review next friday at 4pm",
            context = ParserContext(now = nowIst),
        )
        val nextFriday = nextFridayResult.draft.deadline!!.atZone(ist)
        // Next Friday in Indian English is the following week's Friday: April 10 (+11 days)
        assertThat(nextFriday.dayOfMonth).isEqualTo(10)
        assertThat(nextFriday.monthValue).isEqualTo(4)
    }
}
