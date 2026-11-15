package dev.lumbina.tv.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lumbina.tv.core.domain.device.AppLook
import dev.lumbina.tv.core.domain.device.AttachedApp
import dev.lumbina.tv.core.domain.device.AttachedDevice
import dev.lumbina.tv.core.domain.device.AttachedDevices
import dev.lumbina.tv.core.domain.device.AttachedState
import dev.lumbina.tv.core.domain.device.InputCatalog
import dev.lumbina.tv.core.domain.device.InputPresence
import dev.lumbina.tv.core.domain.device.InputSource
import dev.lumbina.tv.core.domain.device.launch
import dev.lumbina.tv.core.domain.device.tvApps
import dev.lumbina.tv.core.domain.home.HomeAppearanceRepository
import dev.lumbina.tv.core.domain.home.HomeItem
import dev.lumbina.tv.core.domain.home.HomeLayoutRepository
import dev.lumbina.tv.core.domain.home.HomeSection
import dev.lumbina.tv.core.domain.home.withBanner
import dev.lumbina.tv.core.ui.WhileUiSubscribed
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the page of devices on USB shows. */
data class AttachedUiState(
    /** The devices found; null while looking. */
    val devices: List<AttachedDevice>? = null,
    /** The device being connected to, or connected; null before one is chosen. */
    val chosen: AttachedDevice? = null,
    /** How far connecting to [chosen] got; null while it is under way. */
    val state: AttachedState? = null,
    /** What [chosen] says it is ("Xiaomi MiTV-AFMU1, Android 14"), once connected. */
    val about: String? = null,
    /** [chosen]'s TV apps once connected; null before, or where it didn't list them. */
    val apps: List<AttachedApp>? = null,
    /** The app last opened on [chosen], and whether it started. */
    val launched: Pair<AttachedApp, Boolean>? = null,
    /** This device's inputs; the device's picture comes in on one of them. */
    val inputs: List<InputSource> = emptyList(),
    /** The input the tiles switch to: the user's choice, else the one a device works on. */
    val input: InputSource? = null,
    /** The apps whose tile is in Favorites, by [AttachedApp.component]. */
    val onHome: Set<String> = emptySet(),
    /** The apps whose tile is being made: their package read off the device for its picture. */
    val adding: Set<String> = emptySet(),
    /** The names and pictures of [apps] read so far, by [AttachedApp.component]; read one by one once connected. */
    val looks: Map<String, AppLook> = emptyMap(),
)

/** What the page's rows do. */
interface AttachedCommands {
    fun search()

    fun connect(device: AttachedDevice)

    fun launch(app: AttachedApp)

    fun chooseInput(input: InputSource)

    /** Puts [app]'s tile in Favorites, with its name and picture, or takes it out. */
    fun toggleOnHome(app: AttachedApp)
}

@HiltViewModel
class AttachedViewModel @Inject constructor(
    private val devices: AttachedDevices,
    inputCatalog: InputCatalog,
    private val layouts: HomeLayoutRepository,
    private val appearance: HomeAppearanceRepository,
) : ViewModel(),
    AttachedCommands {

    private val own = MutableStateFlow(AttachedUiState())
    private val chosenInput = MutableStateFlow<String?>(null)

    val state: StateFlow<AttachedUiState> = combine(
        own,
        inputCatalog.live,
        chosenInput,
        layouts.layout,
    ) { state, inputs, chosen, layout ->
        val favorites = layout.favorites.items.filterIsInstance<HomeItem.StickApp>()
        state.copy(
            inputs = inputs,
            input = inputs.firstOrNull { it.id == chosen }
                ?: inputs.firstOrNull { input -> favorites.any { it.inputId == input.id } }
                ?: inputs.firstOrNull { it.presence is InputPresence.Working }
                ?: inputs.firstOrNull(),
            onHome = favorites.map { it.component }.toSet(),
            // The tiles' names stand until the device's are read: an app on home is named at once.
            looks = layout.stickNames.mapValues { (_, name) -> AppLook(name, null) } + state.looks,
        )
    }.stateIn(viewModelScope, WhileUiSubscribed, AttachedUiState())

    private var connecting: Job? = null

    init {
        search()
    }

    override fun search() {
        viewModelScope.launch {
            val found = devices.list()
            own.update { state ->
                // A device unplugged since is no longer the chosen one.
                if (state.chosen != null && state.chosen !in found) {
                    AttachedUiState(devices = found)
                } else {
                    state.copy(devices = found)
                }
            }
        }
    }

    override fun connect(device: AttachedDevice) {
        connecting?.cancel()
        own.update {
            it.copy(chosen = device, state = null, about = null, apps = null, launched = null, looks = emptyMap())
        }
        connecting = viewModelScope.launch {
            val result = devices.connect(device)
            own.update { it.copy(state = result) }
            if (result != AttachedState.Connected) return@launch
            val about = devices.shell(device, ABOUT_COMMAND)?.lines()?.map(String::trim)
            val apps = devices.tvApps(device)?.sortedBy { it.packageName }
            own.update { state -> state.copy(about = about?.let { describe(it) }, apps = apps) }
            // One by one, each named as soon as it is read; an app read before comes at once.
            apps?.forEach { app ->
                val look = devices.look(device, app) ?: return@forEach
                own.update { it.copy(looks = it.looks + (app.component to look)) }
            }
        }
    }

    override fun launch(app: AttachedApp) {
        val device = own.value.chosen ?: return
        viewModelScope.launch {
            val started = devices.launch(device, app)
            own.update { it.copy(launched = app to started) }
        }
    }

    override fun chooseInput(input: InputSource) {
        chosenInput.value = input.id
    }

    override fun toggleOnHome(app: AttachedApp) {
        val device = own.value.chosen ?: return
        val current = state.value
        if (app.component in current.adding) return
        if (app.component in current.onHome) {
            viewModelScope.launch {
                layouts.update { it.withStickApp(HomeSection.FAVORITES, stickItem(app, ""), shown = false) }
            }
            return
        }
        val input = current.input ?: return
        own.update { it.copy(adding = it.adding + app.component) }
        viewModelScope.launch {
            try {
                val look = own.value.looks[app.component] ?: devices.look(device, app)
                val item = stickItem(app, input.id)
                layouts.update { layout ->
                    layout.withStickApp(HomeSection.FAVORITES, item, shown = true)
                        .nameStickApp(app.component, look?.name ?: app.packageName)
                }
                look?.banner?.let { banner -> appearance.update { it.withBanner(item.key, banner) } }
            } finally {
                own.update { it.copy(adding = it.adding - app.component) }
            }
        }
    }

    private companion object {
        const val ABOUT_COMMAND =
            "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release"

        fun stickItem(app: AttachedApp, inputId: String) = HomeItem.StickApp(app.packageName, app.activity, inputId)

        /** "Xiaomi MiTV-AFMU1, Android 14" from the three lines of [ABOUT_COMMAND]. */
        fun describe(lines: List<String>): String? {
            fun line(index: Int) = lines.getOrElse(index) { "" }
            val (maker, model, release) = listOf(line(0), line(1), line(2))
            val name = listOf(maker, model).filter(String::isNotEmpty).joinToString(" ")
            return listOf(name, release.takeIf(String::isNotEmpty)?.let { "Android $it" })
                .filter { !it.isNullOrEmpty() }
                .joinToString(", ")
                .ifEmpty { null }
        }
    }
}
