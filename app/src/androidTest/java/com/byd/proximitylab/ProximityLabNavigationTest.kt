package com.byd.proximitylab

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProximityLabNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun startsOnDevicePage() {
        composeRule.onNodeWithTag("screen_device").assertIsDisplayed()
    }

    @Test
    fun navigatesThroughAllFourPagesAndBack() {
        composeRule.onNodeWithTag("nav_monitor").performClick()
        composeRule.onNodeWithTag("screen_monitor").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_sampling").performClick()
        composeRule.onNodeWithTag("screen_sampling").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_lab").performClick()
        composeRule.onNodeWithTag("screen_lab").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_device").performClick()
        composeRule.onNodeWithTag("screen_device").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_monitor").performClick()
        composeRule.onNodeWithTag("screen_monitor").assertIsDisplayed()

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("screen_device").assertIsDisplayed()
    }
}
