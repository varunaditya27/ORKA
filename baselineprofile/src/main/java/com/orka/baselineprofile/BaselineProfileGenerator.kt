package com.orka.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    private val packageName = "com.orka.app"

    @get:Rule
    val baselineRule = BaselineProfileRule()

    @Test
    fun generate() {
        baselineRule.collect(
            packageName = packageName,
        ) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.text("Finish Setup")), 1_500)
            device.findObject(By.text("Finish Setup"))?.click()
            device.wait(Until.hasObject(By.text("Analyse")), 3_000)
            device.findObject(By.clazz("android.widget.EditText"))?.text = "Review internship report by tomorrow"
            device.findObject(By.text("Analyse"))?.click()
            device.wait(Until.hasObject(By.text("Confirm")), 3_000)
            device.findObject(By.text("Confirm"))?.click()
            device.wait(Until.hasObject(By.text("Mark Done")), 3_000)
            // TaskDetail's back control is an icon-only IconButton (contentDescription "Back"),
            // not a text node — By.text("Back") can never match it and would silently no-op here.
            device.findObject(By.desc("Back"))?.click()
            device.findObject(By.text("Settings"))?.click()
            device.wait(Until.hasObject(By.text("Adaptive scheduling")), 3_000)
        }
    }
}
