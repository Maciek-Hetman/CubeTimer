package com.maciekhetman.cubetimer.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.maciekhetman.cubetimer.data.bluetooth.BluetoothTimerStatus
import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerModel
import com.maciekhetman.cubetimer.model.TimingDevice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimingDeviceToggleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setToggle(
        timingDevice: TimingDevice,
        bluetoothStatus: BluetoothTimerStatus = BluetoothTimerStatus.Disconnected,
        enabled: Boolean = true,
        clicks: MutableList<TimingDevice> = mutableListOf()
    ): MutableList<TimingDevice> {
        composeTestRule.setContent {
            MaterialTheme {
                TimingDeviceToggle(
                    timingDevice = timingDevice,
                    bluetoothStatus = bluetoothStatus,
                    onDeviceClick = { clicks += it },
                    enabled = enabled
                )
            }
        }
        return clicks
    }

    @Test
    fun `touch timing selected marks only the touch segment`() {
        setToggle(TimingDevice.KEYBOARD)

        composeTestRule.onNodeWithContentDescription("Touch timing").assertIsDisplayed().assertIsSelected()
        composeTestRule.onNodeWithContentDescription("Bluetooth timer").assertIsDisplayed().assertIsNotSelected()
    }

    @Test
    fun `bluetooth selected describes the connected timer`() {
        setToggle(
            TimingDevice.EXTERNAL_TIMER,
            bluetoothStatus = BluetoothTimerStatus.Connected("GAN-1234", SmartTimerModel.GAN)
        )

        composeTestRule.onNodeWithContentDescription("Bluetooth timer, GAN-1234").assertIsSelected()
        composeTestRule.onNodeWithContentDescription("Touch timing").assertIsNotSelected()
    }

    @Test
    fun `bluetooth selected without a timer says it is not connected`() {
        setToggle(TimingDevice.EXTERNAL_TIMER, bluetoothStatus = BluetoothTimerStatus.Disconnected)

        composeTestRule.onNodeWithContentDescription("Bluetooth timer, Not connected").assertIsSelected()
    }

    @Test
    fun `only the selected segment is labelled`() {
        setToggle(TimingDevice.KEYBOARD)

        composeTestRule.onNodeWithText("Touch").assertIsDisplayed().assertIsSelected()
        composeTestRule.onNodeWithText("Bluetooth").assertDoesNotExist()
    }

    @Test
    fun `bluetooth selected carries the bluetooth label`() {
        setToggle(
            TimingDevice.EXTERNAL_TIMER,
            bluetoothStatus = BluetoothTimerStatus.Connected("GAN-1234", SmartTimerModel.GAN)
        )

        composeTestRule.onNodeWithText("Bluetooth").assertIsDisplayed().assertIsSelected()
        composeTestRule.onNodeWithText("Touch").assertDoesNotExist()
    }

    @Test
    fun `label follows the selected device when it changes`() {
        var device by mutableStateOf(TimingDevice.KEYBOARD)
        composeTestRule.setContent {
            MaterialTheme {
                TimingDeviceToggle(
                    timingDevice = device,
                    bluetoothStatus = BluetoothTimerStatus.Disconnected,
                    onDeviceClick = { device = it }
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Bluetooth timer").performClick()

        composeTestRule.onNodeWithText("Bluetooth").assertIsDisplayed().assertIsSelected()
        composeTestRule.onNodeWithText("Touch").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Bluetooth timer, Not connected").assertIsSelected()
    }

    @Test
    fun `disabled toggle keeps the selection visible`() {
        setToggle(TimingDevice.EXTERNAL_TIMER, enabled = false)

        composeTestRule.onNodeWithText("Bluetooth").assertIsDisplayed().assertIsSelected().assertIsNotEnabled()
    }

    @Test
    fun `clicking a segment reports its device`() {
        val clicks = setToggle(TimingDevice.KEYBOARD)

        composeTestRule.onNodeWithContentDescription("Bluetooth timer").performClick()
        composeTestRule.onNodeWithContentDescription("Touch timing").performClick()

        assertEquals(listOf(TimingDevice.EXTERNAL_TIMER, TimingDevice.KEYBOARD), clicks)
    }

    @Test
    fun `clicking the already selected bluetooth segment is still reported`() {
        // TimerScreen reopens the connect dialog from this click.
        val clicks = setToggle(TimingDevice.EXTERNAL_TIMER)

        composeTestRule.onNodeWithContentDescription("Bluetooth timer", substring = true).performClick()

        assertEquals(listOf(TimingDevice.EXTERNAL_TIMER), clicks)
    }

    @Test
    fun `disabled toggle ignores clicks`() {
        val clicks = setToggle(TimingDevice.KEYBOARD, enabled = false)

        composeTestRule.onNodeWithContentDescription("Touch timing").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Bluetooth timer").assertIsNotEnabled().performClick()

        assertEquals(emptyList<TimingDevice>(), clicks)
    }
}
