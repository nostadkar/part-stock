package com.partstock.scanner

import android.Manifest
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.partstock.scanner.parse.BatchRules
import com.partstock.scanner.parse.BomCsv
import com.partstock.scanner.parse.OcrLine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshots of every main screen for CI (#58): empty and with parts, light and dark, font scale 1.0 and 2.0. PNGs go
 * to [DIR] on the emulator (shell-owned, so they survive the app being uninstalled after the run); build.yml pulls
 * them and uploads them as the "screenshots" artifact.
 *
 * Never fails the build: every walk is wrapped, a failure is logged and the next walk starts from a fresh launch (back
 * presses don't throw when they leave the app). The
 * smoke assertions live in [SmokeTest], unchanged. Names are stable: `NN-screen-theme-data-fontNNN.png`.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Test
    fun screenshots() {
        val started = System.currentTimeMillis()
        try {
            shell("rm -rf $DIR")
            shell("mkdir -p $DIR")
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't prepare $DIR", t)
            return
        }
        try {
            for (font in listOf(1.0f, 2.0f)) {
                setFontScale(font)
                for (theme in listOf("LIGHT", "DARK")) for (withParts in listOf(false, true)) {
                    if (System.currentTimeMillis() - started > TIME_LIMIT_MS) {
                        Log.w(TAG, "Time limit reached, skipping the remaining screenshots")
                        return
                    }
                    val suffix = "${theme.lowercase()}-${if (withParts) "parts" else "empty"}-font${(font * 100).toInt()}"
                    try {
                        launch(theme, withParts)
                        walk(suffix, withParts)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Screenshot walk $suffix stopped", t)
                        try { shot("99-failed", suffix) } catch (_: Throwable) {}
                    } finally {
                        try { scenario?.close() } catch (_: Throwable) {}
                        scenario = null
                    }
                }
            }
        } finally {
            try { setFontScale(1.0f) } catch (_: Throwable) {}
            reset()
        }
    }

    private fun walk(suffix: String, withParts: Boolean) {
        val count = if (withParts) 3 else 0
        shot("01-scan", suffix)

        compose.onNodeWithText("Inventory ($count)").performClick()
        compose.waitForIdle()
        shot("02-inventory", suffix)

        if (withParts) {
            compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("LM358DR"))
            compose.waitForIdle()
            compose.onAllNodesWithText("LM358DR").onFirst().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            shot("03-part", suffix)
            Espresso.pressBackUnconditionally()
            compose.waitForIdle()
        }

        compose.onNodeWithTag("tab-LOCATIONS").performClick()
        compose.waitForIdle()
        shot("04-locations", suffix)
        if (withParts) {
            compose.onNodeWithContentDescription("Grid view").performClick()
            compose.waitForIdle()
            shot("04b-locations-grid", suffix)
            compose.onNodeWithContentDescription("List view").performClick()
            compose.waitForIdle()
        }

        compose.onNodeWithTag("tab-PROJECTS").performClick()
        compose.waitForIdle()
        shot("05-projects", suffix)

        if (withParts) {
            compose.onAllNodesWithText(PROJECT).onFirst().performClick()
            compose.waitForIdle()
            shot("06-project", suffix)
            Espresso.pressBackUnconditionally()
            compose.waitForIdle()
        }

        compose.onNodeWithTag("tab-SCAN").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        shot("07-settings", suffix)
        Espresso.pressBackUnconditionally()
        compose.waitForIdle()

        if (withParts) {
            // The queued bag's card in the batch tray opens the normal review screen (the camera is off on CI).
            compose.onAllNodes(hasClickLabel("Review")).onFirst().performClick()
            compose.waitForIdle()
            shot("08-review", suffix)
            Espresso.pressBackUnconditionally()
            compose.waitForIdle()
        }
    }

    private fun hasClickLabel(label: String) = SemanticsMatcher("click label $label") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private fun launch(theme: String, withParts: Boolean) {
        reset()
        val prefs = Prefs(context)
        prefs.lastUpdateCheck = System.currentTimeMillis() // no update dialog over the screens
        prefs.themeMode = theme
        prefs.batchMode = withParts // with parts: one queued bag, so the Review screen can be opened without a camera
        prefs.inventoryView = "LIST"
        prefs.inventorySort = "CATEGORY"
        prefs.locationsView = "LIST"
        if (withParts) {
            val db = InventoryDb(context)
            listOf(
                part("RC0603FR-0710KL", 500, "A1", "RES 10K OHM 1% 1/10W 0603"),
                part("GRM188R71H104KA93D", 200, "A2", "CAP CER 0.1UF 50V X7R 0603"),
                part("LM358DR", 3, "B1", "IC OPAMP GP 2 CIRCUIT 8SOIC"),
            ).forEach { db.save(it) }
            db.createProject(
                PROJECT, "BOM CSV",
                listOf(bomLine("RC0603FR-0710KL", 4, "R1 R2 R3 R4"), bomLine("LM358DR", 1, "U1"), bomLine("NE555DR", 1, "U2")),
            )
            db.close()
            BatchQueue.store(context, listOf(queuedBag()))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    /** Back to a clean app: no database, no batch queue, default theme and batch mode. */
    private fun reset() {
        try {
            context.deleteDatabase("inventory.db")
            File(context.filesDir, "batch").deleteRecursively()
            val prefs = Prefs(context)
            prefs.themeMode = "SYSTEM"
            prefs.batchMode = false
        } catch (t: Throwable) {
            Log.w(TAG, "Reset failed", t)
        }
    }

    private fun queuedBag(): BatchItem {
        var y = 0
        val lines = listOf(
            "DIGIKEY", "DK Part # 296-1395-5-ND", "MFG Part # LM358DR", "IC OPAMP GP 2 CIRCUIT 8SOIC", "QTY: 10",
        ).map { y += 40; OcrLine(it, 20, y, 600, y + 30) }
        return BatchItem(
            id = "screenshot-bag", createdAt = System.currentTimeMillis(), frames = listOf(lines), codes = emptyList(),
            photoFile = "", lookup = BatchRules.LOOKUP_NONE,
        )
    }

    private fun part(mpn: String, qty: Int, bin: String, description: String) = ScanEntry(
        mpn = mpn, description = description, manufacturer = "", quantity = qty, addMode = true, location = bin,
        distributor = "", distributorPn = "", customerPn = "", source = "Test", notes = "", dateCode = "", refDes = "",
        raw = "", unitCost = 0.05, productUrl = "", category = "",
    )

    private fun bomLine(mpn: String, perBoard: Int, refDes: String) = BomCsv.Line().apply {
        this.mpn = mpn
        this.perBoard = perBoard
        this.refDes = refDes
    }

    /** Whole-screen PNG via the shell's screencap (software-rendered on the CI emulator). Logs instead of throwing. */
    private fun shot(screen: String, suffix: String) {
        compose.waitForIdle()
        Thread.sleep(400) // let ripples and fades settle
        val name = "$screen-$suffix.png"
        try {
            shell("screencap -p $DIR/$name")
            Log.i(TAG, "Saved $name")
        } catch (t: Throwable) {
            Log.w(TAG, "Screenshot $name failed", t)
        }
    }

    private fun setFontScale(scale: Float) {
        shell("settings put system font_scale $scale")
        Thread.sleep(1500) // let the configuration change reach the app before it launches
    }

    /** Runs a shell command and waits for it to finish (reading its output to the end). */
    private fun shell(cmd: String) {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private companion object {
        const val TAG = "PartStockShots"
        const val DIR = "/sdcard/Download/part-stock-screenshots"
        const val PROJECT = "Test board"
        const val TIME_LIMIT_MS = 8 * 60 * 1000L
    }
}
