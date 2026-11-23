package com.partstock.scanner

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the app on an emulator (CI) and walks through the main screens, once with an empty inventory and once with
 * a few parts, so a crash at launch or on opening a screen fails the build before a release is published.
 * Stays off the camera: the permission is granted so no system dialog covers the app, and nothing is captured.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun launch(parts: List<ScanEntry>) {
        context.deleteDatabase("inventory.db")
        val prefs = Prefs(context)
        prefs.lastUpdateCheck = System.currentTimeMillis() // no update check (and no update dialog) during the test
        prefs.batchMode = false
        if (parts.isNotEmpty()) {
            val db = InventoryDb(context)
            parts.forEach { db.save(it) }
            db.close()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun part(mpn: String, qty: Int, bin: String, description: String) = ScanEntry(
        mpn = mpn, description = description, manufacturer = "", quantity = qty, addMode = true, location = bin,
        distributor = "", distributorPn = "", customerPn = "", source = "Test", notes = "", dateCode = "", refDes = "",
        raw = "", unitCost = 0.05, productUrl = "", category = "",
    )

    /** Scan → Inventory → Locations → back → Projects → back → back → Settings → back. */
    private fun walkScreens(partCount: Int) {
        compose.onNodeWithText("Inventory ($partCount)").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Locations").assertIsDisplayed()

        compose.onNodeWithContentDescription("Locations").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Locations").onFirst().assertExists()
        Espresso.pressBack()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Projects / BOMs").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Projects").onFirst().assertExists()
        Espresso.pressBack()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Locations").assertIsDisplayed()
        Espresso.pressBack() // Inventory → Scan
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Mouser").onFirst().assertExists()
        compose.onAllNodesWithText("Backup & restore").onFirst().assertExists()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Inventory ($partCount)").assertIsDisplayed()
    }

    @Test
    fun emptyInventory() {
        launch(emptyList())
        walkScreens(0)
    }

    @Test
    fun withParts() {
        launch(
            listOf(
                part("RC0603FR-0710KL", 500, "A1", "RES 10K OHM 1% 1/10W 0603"),
                part("GRM188R71H104KA93D", 200, "A2", "CAP CER 0.1UF 50V X7R 0603"),
                part("LM358DR", 3, "B1", "IC OPAMP GP 2 CIRCUIT 8SOIC"),
            ),
        )
        walkScreens(3)
        openPartPage("LM358DR")
    }

    /** Landscape: Scan and Inventory open, a part page opens, and rotating back keeps that page open. */
    @Test
    fun landscape() {
        launch(
            listOf(
                part("RC0603FR-0710KL", 500, "A1", "RES 10K OHM 1% 1/10W 0603"),
                part("LM358DR", 3, "B1", "IC OPAMP GP 2 CIRCUIT 8SOIC"),
            ),
        )
        rotate(Configuration.ORIENTATION_LANDSCAPE)
        compose.onNodeWithContentDescription("Read label").assertExists()
        compose.onNodeWithText("Inventory (2)").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("LM358DR"))
        compose.waitForIdle()
        compose.onAllNodesWithText("LM358DR").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("Stock over time").assertExists()
        rotate(Configuration.ORIENTATION_PORTRAIT)
        compose.onNodeWithText("Stock over time").assertExists() // still on the part page after rotating
        Espresso.pressBack()
        compose.waitForIdle()
        Espresso.pressBack()
        compose.waitForIdle()
    }

    private fun rotate(orientation: Int) {
        scenario!!.onActivity {
            it.requestedOrientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        compose.waitUntil(10_000) {
            var now = 0
            scenario!!.onActivity { now = it.resources.configuration.orientation }
            now == orientation
        }
        compose.waitForIdle()
    }

    /** Scan → Inventory → part page (with its Stock over time card) → back → back. */
    private fun openPartPage(mpn: String) {
        compose.onNodeWithText("Inventory (3)").performClick()
        compose.waitForIdle()
        // The row may be below the Overview card, outside the composed part of the list: scroll to it first.
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(mpn))
        compose.waitForIdle()
        // The first match may be in the Overview's "Most valuable parts" (same part), partly off screen: click by action.
        compose.onAllNodesWithText(mpn).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("Stock over time").assertExists()
        Espresso.pressBack() // part page → Inventory
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Locations").assertIsDisplayed()
        Espresso.pressBack() // Inventory → Scan
        compose.waitForIdle()
    }
}
