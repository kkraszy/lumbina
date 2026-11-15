package dev.lumbina.tv.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.lumbina.tv.core.domain.home.TileContent
import dev.lumbina.tv.core.ui.MenuEntry
import dev.lumbina.tv.core.ui.TileMenu
import dev.lumbina.tv.core.ui.TileName

/** [tile] in the row [sectionId]: OK opens it (or puts it down while [moving]), holding OK its menu. */
@Suppress("LongParameterList") // what a row's tile is drawn with, as the others in SectionTile
@Composable
internal fun StickSectionTile(
    tile: TileContent.StickTile,
    sectionId: String,
    moving: Boolean,
    marked: Boolean,
    name: TileName,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) = StickTile(
    banner = actions.bannerOf(tile.key),
    onClick = { actions.click(moving) { actions.openStick(tile) } },
    onLongClick = { hold(moving) { actions.stickMenu(sectionId, tile) } },
    label = tile.name,
    name = name,
    highlighted = marked,
    modifier = modifier,
)

/** The tile of an app on the device on USB: its menu, and the rows that have it. */
@Composable
internal fun StickDialogs(
    dialog: HomeDialog.StickDialog,
    state: HomeUiState,
    commands: HomeCommands,
    menu: MenuState,
    startMoving: (Moving) -> Unit,
) {
    when (dialog) {
        is HomeDialog.StickActions -> StickMenu(dialog, state, commands, menu, startMoving)

        is HomeDialog.StickWhere -> OnceTileWhere(
            tile = dialog.tile.name,
            layout = state.layout,
            has = { state.layout.hasStickApp(it, dialog.tile.app.component) },
            set = { sectionId, shown -> commands.setStickApp(sectionId, dialog.tile.app, shown) },
            menu = menu,
            dialog = dialog,
        )
    }
}

/**
 * Holding OK on the tile of an app on the device on USB: as an input's tile has (move, out of this
 * row, the rows that have it), then its banner, then opening it.
 */
@Composable
private fun StickMenu(
    dialog: HomeDialog.StickActions,
    state: HomeUiState,
    commands: HomeCommands,
    menu: MenuState,
    startMoving: (Moving) -> Unit,
) {
    val section = state.layout.section(dialog.sectionId) ?: return
    val tile = dialog.tile
    val group = stringResource(R.string.menu_group_stick)
    TileMenu(
        title = tile.name,
        entries = onceTileEntries(
            section = section,
            layout = state.layout,
            has = { state.layout.hasStickApp(it, tile.app.component) },
            onMove = { startMoving(Moving(section.id, tile.key)) },
            onOut = { commands.removeStickApp(section.id, tile) },
            onWhere = { menu.show(HomeDialog.StickWhere(tile)) },
        ) + listOf(
            MenuEntry(stringResource(R.string.menu_banner)) { menu.show(HomeDialog.Banner(BannerOf.OfStick(tile))) },
            MenuEntry(stringResource(R.string.menu_open), group = group) { commands.openStickApp(tile) },
        ),
        onDismiss = { menu.done(dialog) },
        onBack = menu::back,
        subtitle = stringResource(R.string.stick_hint),
        focused = menu.cameBackTo,
        onChoose = menu::chose,
    )
}
