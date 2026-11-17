package dev.lumbina.tv.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.lumbina.tv.core.domain.device.AttachedApp
import dev.lumbina.tv.core.domain.device.AttachedDevice
import dev.lumbina.tv.core.domain.device.AttachedState
import dev.lumbina.tv.core.ui.Choice

/**
 * The Android devices on USB (a stick powered by this device, its picture on HDMI): connecting to
 * one through its debugging, then its TV apps, each opened on it by OK.
 */
@Composable
fun AttachedPanel(state: AttachedUiState, commands: AttachedCommands, modifier: Modifier = Modifier) {
    SettingsColumn(modifier) {
        Note(stringResource(R.string.attached_about))
        Header(stringResource(R.string.attached_devices))
        val devices = state.devices
        when {
            devices == null -> Note(stringResource(R.string.attached_searching))
            devices.isEmpty() -> Note(stringResource(R.string.attached_none))
            else -> devices.forEach { device -> DeviceRow(device, state, onClick = { commands.connect(device) }) }
        }
        SettingRow(
            onClick = commands::search,
            headlineContent = { Text(stringResource(R.string.attached_search)) },
        )
        if (state.chosen != null && state.state == null) Note(stringResource(R.string.attached_prompt))
        if (state.state == AttachedState.Connected) Apps(state, commands)
    }
}

@Composable
private fun DeviceRow(device: AttachedDevice, state: AttachedUiState, onClick: () -> Unit) {
    val status = if (device == state.chosen) stateText(state) else stringResource(R.string.attached_tap_to_connect)
    SettingRow(
        onClick = onClick,
        headlineContent = { Text(device.name) },
        supportingContent = { Text(status) },
    )
}

@Composable
@ReadOnlyComposable
private fun stateText(state: AttachedUiState): String = when (state.state) {
    null -> stringResource(R.string.attached_connecting)
    AttachedState.Connected -> state.about ?: stringResource(R.string.attached_connected)
    AttachedState.Gone -> stringResource(R.string.attached_gone)
    AttachedState.NotPermitted -> stringResource(R.string.attached_not_permitted)
    AttachedState.NotAllowed -> stringResource(R.string.attached_not_allowed)
    AttachedState.Failed -> stringResource(R.string.attached_failed)
}

/**
 * The input the device's picture comes in on, then its TV apps: OK puts an app's tile in Favorites
 * (its name and picture read from the device first) or takes it out; holding OK opens it there.
 */
@Composable
private fun Apps(state: AttachedUiState, commands: AttachedCommands) {
    if (state.inputs.isNotEmpty()) {
        ChoiceRow(
            title = stringResource(R.string.attached_input),
            hint = stringResource(R.string.attached_input_hint),
            options = state.inputs.map { input ->
                Choice(title = input.caption, chosen = input == state.input) { commands.chooseInput(input) }
            },
        )
    }
    Header(stringResource(R.string.attached_apps))
    val apps = state.apps
    when {
        apps == null -> Note(stringResource(R.string.attached_apps_unknown))

        apps.isEmpty() -> Note(stringResource(R.string.attached_apps_none))

        else -> {
            Note(stringResource(R.string.attached_apps_hint))
            apps.forEach { app -> AppRow(app, state, commands) }
        }
    }
}

@Composable
private fun AppRow(app: AttachedApp, state: AttachedUiState, commands: AttachedCommands) {
    val launched = state.launched?.takeIf { it.first == app }?.second
    val onHome = app.component in state.onHome
    val name = state.looks[app.component]?.name
    val status = when {
        app.component in state.adding -> stringResource(R.string.attached_adding)
        launched == true -> stringResource(R.string.attached_launched)
        launched == false -> stringResource(R.string.attached_launch_failed)
        onHome -> stringResource(R.string.attached_on_home)
        name == null -> stringResource(R.string.attached_reading_name)
        else -> app.packageName
    }
    SettingRow(
        onClick = { commands.toggleOnHome(app) },
        onLongClick = { commands.launch(app) },
        headlineContent = { Text(name ?: app.packageName) },
        supportingContent = { Text(status) },
        trailingContent = { Switch(checked = onHome, onCheckedChange = null) },
    )
}
