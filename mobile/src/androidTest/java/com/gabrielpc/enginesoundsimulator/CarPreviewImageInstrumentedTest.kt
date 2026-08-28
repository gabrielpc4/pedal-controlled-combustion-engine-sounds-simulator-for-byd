package com.gabrielpc.enginesoundsimulator

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarPreviewImageInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun missingPrivatePreviewUsesNeutralPlaceholder() {
        compose.setContent {
            CarPreviewImage(
                absolutePath = null,
                assetFallback = null,
                contentDescription = "Uninstalled test car",
                maximumDimensionPx = 256,
                modifier = Modifier.size(width = 160.dp, height = 90.dp),
            )
        }

        compose.onNodeWithText("NO PREVIEW").assertIsDisplayed()
    }
}
