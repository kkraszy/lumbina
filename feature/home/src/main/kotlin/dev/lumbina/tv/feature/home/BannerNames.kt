package dev.lumbina.tv.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource

/** The name of the tile [of], as on screen: what the banner picker's way to it begins with. */
@Composable
@ReadOnlyComposable
internal fun bannerName(of: BannerOf): String = when (of) {
    is BannerOf.OfApp -> of.app.label
    BannerOf.AllApps -> stringResource(R.string.home_all_apps)
    is BannerOf.OfInput -> of.input.name
    is BannerOf.OfStick -> of.tile.name
}
