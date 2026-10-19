package com.partstock.scanner

import android.Manifest
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
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
        compose.onAllNodesWithText("Mouser Search API key").onFirst().assertExists()
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
    }
}
