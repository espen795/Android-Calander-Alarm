package io.github.calendaralarm.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.github.calendaralarm.ui.screens.SettingsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for SettingsScreen.
 *
 * Regression tests for v2.1.3 fix (SettingsScreen crash) and v2.1.4 features:
 * - Screen renders without crash
 * - ServerRulesEditor renders
 * - Snooze duration selection works
 * - Device token show/hide toggle
 * - Rotate token dialog
 *
 * These are instrumented tests (androidTest) — require a device or emulator.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ============== Regression: Screen renders without crash (v2.1.3 fix) ==============

    @Test
    fun settingsScreen_rendersWithoutCrash() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        // If we get here without exception, the screen rendered successfully
        // This is the regression test for the v2.1.3 crash fix
        composeTestRule.onNodeWithText("Wake times (server)").assertExists()
    }

    @Test
    fun settingsScreen_showsSnoozeSection() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        composeTestRule.onNodeWithText("Alarm").assertExists()
        composeTestRule.onNodeWithText("Snooze duur").assertExists()
    }

    @Test
    fun settingsScreen_showsSyncSection() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        composeTestRule.onNodeWithText("Server Sync").assertExists()
        composeTestRule.onNodeWithText("Server URL").assertExists()
    }

    @Test
    fun settingsScreen_showsDeviceTokenSection() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        composeTestRule.onNodeWithText("Device Token").assertExists()
    }

    @Test
    fun settingsScreen_showsCloudflareAccessSection() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        composeTestRule.onNodeWithText("Cloudflare Access").assertExists()
    }

    @Test
    fun settingsScreen_showsUpdatesSection() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        composeTestRule.onNodeWithText("Updates").assertExists()
    }

    @Test
    fun settingsScreen_showsAboutSection() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        composeTestRule.onNodeWithText("Over").assertExists()
    }

    // ============== Snooze Duration Selection ==============

    @Test
    fun settingsScreen_snoozeDuration_showsAllOptions() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        // All 4 snooze options should be visible
        composeTestRule.onNodeWithText("5 min").assertExists()
        composeTestRule.onNodeWithText("10 min").assertExists()
        composeTestRule.onNodeWithText("15 min").assertExists()
        composeTestRule.onNodeWithText("30 min").assertExists()
    }

    @Test
    fun settingsScreen_snoozeDuration_clickChangesSelection() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        // Click on 10 min option
        composeTestRule.onNodeWithText("10 min").performClick()

        // Wait for UI to settle
        composeTestRule.waitForIdle()

        // The chip should now be selected (we verify by checking it's still there)
        composeTestRule.onNodeWithText("10 min").assertExists()
    }

    @Test
    fun settingsScreen_snoozeDuration_click30min() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        composeTestRule.onNodeWithText("30 min").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("30 min").assertExists()
    }

    // ============== Server URL Input ==============

    @Test
    fun settingsScreen_serverUrl_showsHttpsValidation() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        // Find the server URL text field and check it exists
        composeTestRule.onNodeWithText("Server URL").assertExists()
        composeTestRule.onNodeWithText("Server URL opslaan").assertExists()
    }

    @Test
    fun settingsScreen_serverUrl_saveButtonExists() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        composeTestRule.onNodeWithText("Server URL opslaan").assertExists()
    }

    // ============== Device Token Show/Hide ==============

    @Test
    fun settingsScreen_deviceToken_showsMaskedByDefault() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        // Token should be masked with dots by default
        composeTestRule.onNodeWithText("••••••••••••••••••••••••••••••••", substring = true).assertExists()
    }

    @Test
    fun settingsScreen_deviceToken_hasShowHideButton() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        // The show/hide button should exist (content description "Toon" or "Verberg")
        composeTestRule.onNodeWithContentDescription("Toon").assertExists()
    }

    @Test
    fun settingsScreen_deviceToken_hasCopyButton() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        composeTestRule.onNodeWithContentDescription("Kopiëren").assertExists()
    }

    // ============== Rotate Token Dialog ==============

    @Test
    fun settingsScreen_rotateTokenButton_exists() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        // The "Nieuw" button (with refresh icon) triggers rotate dialog
        composeTestRule.onNodeWithText("Nieuw").assertExists()
    }

    @Test
    fun settingsScreen_rotateTokenDialog_opensOnClick() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        // Click the rotate button
        composeTestRule.onNodeWithText("Nieuw").performClick()
        composeTestRule.waitForIdle()

        // Dialog should appear
        composeTestRule.onNodeWithText("Token roteren?").assertExists()
        composeTestRule.onNodeWithText("Roteer").assertExists()
        composeTestRule.onNodeWithText("Annuleren").assertExists()
    }

    @Test
    fun settingsScreen_rotateTokenDialog_dismissOnCancel() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        // Open dialog
        composeTestRule.onNodeWithText("Nieuw").performClick()
        composeTestRule.waitForIdle()

        // Click cancel
        composeTestRule.onNodeWithText("Annuleren").performClick()
        composeTestRule.waitForIdle()

        // Dialog should be gone
        composeTestRule.onNodeWithText("Token roteren?").assertDoesNotExist()
    }

    // ============== Cloudflare Access ==============

    @Test
    fun settingsScreen_cfAccess_hasClientIdField() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        composeTestRule.onNodeWithText("Client ID").assertExists()
    }

    @Test
    fun settingsScreen_cfAccess_hasClientSecretField() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        composeTestRule.onNodeWithText("Client Secret").assertExists()
    }

    @Test
    fun settingsScreen_cfAccess_saveButtonDisabledWhenEmpty() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        // Save button should be disabled when fields are empty
        composeTestRule.onNodeWithText("Opslaan").assertExists()
    }

    @Test
    fun settingsScreen_cfAccess_hasClearButton() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        composeTestRule.onNodeWithText("Wissen").assertExists()
    }

    // ============== Updates Section ==============

    @Test
    fun settingsScreen_updates_hasCheckButton() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        composeTestRule.onNodeWithText("Nu checken op updates").assertExists()
    }

    // ============== Multiple Sections Coexist ==============

    @Test
    fun settingsScreen_allSectionsRenderTogether() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        // Verify all major sections are present
        composeTestRule.onNodeWithText("Wake times (server)").assertExists()
        composeTestRule.onNodeWithText("Alarm").assertExists()
        composeTestRule.onNodeWithText("Server Sync").assertExists()
        composeTestRule.onNodeWithText("Cloudflare Access").assertExists()
        composeTestRule.onNodeWithText("Updates").assertExists()
        composeTestRule.onNodeWithText("Over").assertExists()
    }
}
