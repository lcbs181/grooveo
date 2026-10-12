package dev.schlubbe.musicagent.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Space and the arrow keys only control the player while no text field is being typed in. */
@OptIn(ExperimentalTestApi::class)
class TypingFocusTest {
    @Test fun `a focused text field counts as typing, until the focus moves away`() = runComposeUiTest {
        val other = FocusRequester()
        setContent {
            TypingFocus.Track {
                var a by mutableStateOf("")
                var b by mutableStateOf("")
                Column {
                    BasicTextField(a, { a = it }, Modifier.testTag("basic"))
                    OutlinedTextField(b, { b = it }, Modifier.testTag("outlined"))
                    Text("x", Modifier.focusRequester(other).focusable())
                }
            }
        }
        waitForIdle()
        assertFalse(TypingFocus.active)
        onNodeWithTag("basic").performClick()
        waitForIdle()
        assertTrue(TypingFocus.active)
        onNodeWithTag("outlined").performClick()
        waitForIdle()
        assertTrue(TypingFocus.active)
        runOnIdle { other.requestFocus() }
        waitForIdle()
        assertFalse(TypingFocus.active)
    }
}
