package io.github.calendaralarm.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.github.calendaralarm.ui.screens.AddAlarmDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for AddAlarmDialog.
 *
 * Regression test for v2.1.4 fix (AddAlarmDialog crash):
 * - Dialog opens without crash
 * - Time picker works
 * - Day selection works
 * - Label input validation
 *
 * These are instrumented tests (androidTest) — require a device or emulator.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AddAlarmDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ============== Regression: Dialog opens without crash (v2.1.4 fix) ==============

    @Test
    fun addAlarmDialog_opensWithoutCrash() {
        composeTestRule.setContent {
            AddAlarmDialog(
                onDismiss = {},
                onAlarmAdded = {}
            )
        }

        // If we get here without exception, the dialog rendered successfully
        // This is the regression test for the v2.1.4 crash fix
        composeTestRule.onNodeWithText("Nieuw alarm").assertExists()
    }

    @Test
    fun addAlarmDialog_showsTitle() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        composeTestRule.onNodeWithText("Nieuw alarm").assertExists()
    }

    // ============== Time Display ==============

    @Test
    fun addAlarmDialog_showsDefaultTime() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        // Default time is 07:00
        composeTestRule.onNodeWithText("07:00").assertExists()
    }

    @Test
    fun addAlarmDialog_hasTimePickerButton() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        // The time display is also the button to open time picker
        composeTestRule.onNodeWithText("07:00").assertClickable()
    }

    // ============== Time Picker Dialog ==============

    @Test
    fun addAlarmDialog_timePicker_opensOnClick() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        // Click the time button
        composeTestRule.onNodeWithText("07:00").performClick()
        composeTestRule.waitForIdle()

        // Time picker dialog should appear
        composeTestRule.onNodeWithText("Kies een tijd").assertExists()
    }

    @Test
    fun addAlarmDialog_timePicker_hasOkButton() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        composeTestRule.onNodeWithText("07:00").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("OK").assertExists()
    }

    @Test
    fun addAlarmDialog_timePicker_hasCancelButton() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        composeTestRule.onNodeWithText("07:00").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Annuleren").assertExists()
    }

    @Test
    fun addAlarmDialog_timePicker_dismissOnCancel() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        // Open time picker
        composeTestRule.onNodeWithText("07:00").performClick()
        composeTestRule.waitForIdle()

        // Cancel
        composeTestRule.onNodeWithText("Annuleren").performClick()
        composeTestRule.waitForIdle()

        // Time picker dialog should be gone, main dialog still visible
        composeTestRule.onNodeWithText("Kies een tijd").assertDoesNotExist()
        composeTestRule.onNodeWithText("Nieuw alarm").assertExists()
    }

    // ============== Label Input ==============

    @Test
    fun addAlarmDialog_hasLabelInput() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        composeTestRule.onNodeWithText("Label").assertExists()
    }

    @Test
    fun addAlarmDialog_labelInput_acceptsText() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        composeTestRule.onNodeWithText("Label").performTextInput("Test Alarm")
        composeTestRule.onNodeWithText("Test Alarm").assertExists()
    }

    @Test
    fun addAlarmDialog_labelInput_showsCharacterCount() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        // Initially shows 0/100
        composeTestRule.onNodeWithText("0/100").assertExists()

        // Type some text
        composeTestRule.onNodeWithText("Label").performTextInput("Test")
        composeTestRule.onNodeWithText("4/100").assertExists()
    }

    @Test
    fun addAlarmDialog_labelInput_enforcesMaxLength() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        // Type 100 characters
        val longLabel = "a".repeat(100)
        composeTestRule.onNodeWithText("Label").performTextInput(longLabel)
        composeTestRule.onNodeWithText("100/100").assertExists()

        // Try to type more — should be blocked
        composeTestRule.onNodeWithText("Label").performTextInput("b")
        composeTestRule.onNodeWithText("100/100").assertExists()
    }

    // ============== Save Button Validation ==============

    @Test
    fun addAlarmDialog_saveButton_disabledWhenLabelEmpty() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        // Save button should exist but be disabled (no label)
        composeTestRule.onNodeWithText("Opslaan").assertExists()
        composeTestRule.onNodeWithText("Opslaan").assertNotEnabled()
    }

    @Test
    fun addAlarmDialog_saveButton_enabledWhenLabelFilled() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        composeTestRule.onNodeWithText("Label").performTextInput("My Alarm")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Opslaan").assertEnabled()
    }

    // ============== Day Selection ==============

    @Test
    fun addAlarmDialog_showsRepeatLabel() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        composeTestRule.onNodeWithText("Herhaal op").assertExists()
    }

    @Test
    fun addAlarmDialog_showsOneTimeLabelByDefault() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        composeTestRule.onNodeWithText("Eenmalig (vandaag of morgen)").assertExists()
    }

    @Test
    fun addAlarmDialog_showsAllDayChips() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        // All 7 day chips should be visible
        composeTestRule.onNodeWithText("Ma").assertExists()
        composeTestRule.onNodeWithText("Di").assertExists()
        composeTestRule.onNodeWithText("Wo").assertExists()
        composeTestRule.onNodeWithText("Do").assertExists()
        composeTestRule.onNodeWithText("Vr").assertExists()
        composeTestRule.onNodeWithText("Za").assertExists()
        composeTestRule.onNodeWithText("Zo").assertExists()
    }

    @Test
    fun addAlarmDialog_dayChip_clickSelects() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        // Click Monday
        composeTestRule.onNodeWithText("Ma").performClick()
        composeTestRule.waitForIdle()

        // Should now show "Elke: Ma" instead of "Eenmalig"
        composeTestRule.onNodeWithText("Elke: Ma").assertExists()
    }

    @Test
    fun addAlarmDialog_dayChip_clickMultipleSelects() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        // Select Mon, Wed, Fri
        composeTestRule.onNodeWithText("Ma").performClick()
        composeTestRule.onNodeWithText("Wo").performClick()
        composeTestRule.onNodeWithText("Vr").performClick()
        composeTestRule.waitForIdle()

        // Should show all selected days
        composeTestRule.onNodeWithText("Elke: Ma, Wo, Vr").assertExists()
    }

    @Test
    fun addAlarmDialog_dayChip_clickAgainDeselects() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        // Select Monday
        composeTestRule.onNodeWithText("Ma").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Elke: Ma").assertExists()

        // Deselect Monday
        composeTestRule.onNodeWithText("Ma").performClick()
        composeTestRule.waitForIdle()

        // Should be back to one-time
        composeTestRule.onNodeWithText("Eenmalig (vandaag of morgen)").assertExists()
    }

    // ============== Cancel Button ==============

    @Test
    fun addAlarmDialog_hasCancelButton() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        composeTestRule.onNodeWithText("Annuleren").assertExists()
    }

    @Test
    fun addAlarmDialog_cancelButton_callsOnDismiss() {
        var dismissed = false
        composeTestRule.setContent {
            AddAlarmDialog(
                onDismiss = { dismissed = true },
                onAlarmAdded = {}
            )
        }

        composeTestRule.onNodeWithText("Annuleren").performClick()
        composeTestRule.waitForIdle()

        assert(dismissed) { "onDismiss should have been called" }
    }

    // ============== Full Flow ==============

    @Test
    fun addAlarmDialog_fullFlow_labelAndDays() {
        composeTestRule.setContent {
            AddAlarmDialog(onDismiss = {}, onAlarmAdded = {})
        }

        // Enter label
        composeTestRule.onNodeWithText("Label").performTextInput("Work Alarm")

        // Select weekdays
        composeTestRule.onNodeWithText("Ma").performClick()
        composeTestRule.onNodeWithText("Di").performClick()
        composeTestRule.onNodeWithText("Wo").performClick()
        composeTestRule.onNodeWithText("Do").performClick()
        composeTestRule.onNodeWithText("Vr").performClick()
        composeTestRule.waitForIdle()

        // Verify state
        composeTestRule.onNodeWithText("Work Alarm").assertExists()
        composeTestRule.onNodeWithText("Elke: Ma, Di, Wo, Do, Vr").assertExists()
        composeTestRule.onNodeWithText("Opslaan").assertEnabled()
    }
}
