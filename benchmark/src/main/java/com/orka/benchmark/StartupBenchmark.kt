package com.orka.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    private val packageName = "com.orka.app"

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStart() {
        benchmarkRule.measureRepeated(
            packageName = packageName,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            iterations = 3,
            startupMode = StartupMode.COLD,
        ) {
            pressHome()
            startActivityAndWait()
        }
    }

    @Test
    fun captureFlowSmoke() {
        benchmarkRule.measureRepeated(
            packageName = packageName,
            metrics = listOf(FrameTimingMetric()),
            iterations = 3,
            startupMode = StartupMode.WARM,
        ) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.text("Finish Setup")), 1_500)
            device.findObject(By.text("Finish Setup"))?.click()
            device.wait(Until.hasObject(By.text("Analyse")), 3_000)
            device.findObject(By.clazz("android.widget.EditText"))?.text = "Prepare slides by tomorrow evening"
            device.findObject(By.text("Analyse"))?.click()
            device.wait(Until.hasObject(By.text("Confirm")), 3_000)
            device.findObject(By.text("Confirm"))?.click()
            device.wait(Until.hasObject(By.text("Mark Done")), 3_000)
        }
    }
}
