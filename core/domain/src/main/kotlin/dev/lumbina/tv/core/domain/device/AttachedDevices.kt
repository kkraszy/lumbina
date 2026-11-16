package dev.lumbina.tv.core.domain.device

/**
 * An Android device on one of this device's USB ports, its debugging on there: a stick powered by
 * this device, its picture coming in on HDMI. [id] tells it from the others while it stays plugged
 * in; [name] is what it calls itself on USB.
 */
data class AttachedDevice(val id: String, val name: String)

/** How far a connection to an [AttachedDevice] gets. */
enum class AttachedState {
    /** It is not on the port any more, or no longer offers debugging there. */
    Gone,

    /** This device's own dialog didn't let Lumbina use the USB device. */
    NotPermitted,

    /** Its debugging didn't let Lumbina's key in: refused, or not answered on its screen in time. */
    NotAllowed,

    Connected,

    /** The connection failed; the log says why. */
    Failed,
}

/** An app the attached device's home screen shows: its TV launcher activity, as `am start -n` takes it. */
data class AttachedApp(val packageName: String, val activity: String) {
    val component: String get() = "$packageName/$activity"
}

/** What an app on an attached device looks like: its [name], and its picture ([banner], a file's uri) where read. */
data class AppLook(val name: String, val banner: String?)

/** The Android devices on USB, reached through their debugging (adb over the cable). */
interface AttachedDevices {
    /** The devices on USB that offer debugging, in no set order. */
    suspend fun list(): List<AttachedDevice>

    /**
     * Connects to [device], asking for what is missing: the USB device here (Android's dialog), and
     * Lumbina's key on [device]'s own screen, which the user sees on its HDMI input.
     */
    suspend fun connect(device: AttachedDevice): AttachedState

    /** Runs [command] in [device]'s shell and returns its output; null when not connected. Asks nothing. */
    suspend fun shell(device: AttachedDevice, command: String): String?

    /**
     * [app]'s name and picture (its TV banner, else its icon) as its package has them; null when
     * not connected or the package can't be read. Slow: the package comes whole over the cable.
     */
    suspend fun look(device: AttachedDevice, app: AttachedApp): AppLook? = null
}

/** What lists the TV apps: the activities that answer MAIN in the TV launcher's category. */
const val TV_APPS_COMMAND =
    "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER"

/** A line of [TV_APPS_COMMAND]'s output that names an activity: "  com.example/.MainActivity". */
private val COMPONENT = Regex("""^\s*([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\s*$""")

/** The apps in what [TV_APPS_COMMAND] printed, each once (its first activity), in the order printed. */
fun parseTvApps(output: String): List<AttachedApp> = output.lineSequence()
    .mapNotNull { COMPONENT.matchEntire(it) }
    .map { AttachedApp(packageName = it.groupValues[1], activity = it.groupValues[2]) }
    .distinctBy { it.packageName }
    .toList()

/** [device]'s TV apps; null when not connected. */
suspend fun AttachedDevices.tvApps(device: AttachedDevice): List<AttachedApp>? =
    shell(device, TV_APPS_COMMAND)?.let(::parseTvApps)

/**
 * Wakes [device] and opens [app] on it; false when not connected or the activity didn't start.
 * The component is quoted: an activity's name may hold a `$`.
 */
suspend fun AttachedDevices.launch(device: AttachedDevice, app: AttachedApp): Boolean {
    val output = shell(device, "input keyevent KEYCODE_WAKEUP; am start -n '${app.component}'") ?: return false
    return !output.contains("Error")
}

/**
 * Opens [app] on the device on USB, the first one found where there are more; false when there is
 * none, or it isn't connected, or the app didn't start.
 */
suspend fun AttachedDevices.launchOnAny(app: AttachedApp): Boolean {
    val device = list().firstOrNull() ?: return false
    return launch(device, app)
}
