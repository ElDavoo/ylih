package it.eldavo.ylih

/**
 * Build-variant capabilities for the Google Play build.
 *
 * Two things are given up relative to `classic`:
 *
 * 1. **No `specialUse` foreground-service type.** Play reviews that type case by case and
 *    expects a justification for why no other type fits; `connectedDevice` covers the Bluetooth
 *    case, so `specialUse` is dropped. The cost is real: on Android 14+, `connectedDevice`
 *    requires holding a Bluetooth permission, so in this build detailed tracking (wired
 *    headphones and playback measurement) is unavailable until Bluetooth access is granted.
 * 2. **No battery-optimisation shortcut.** Not strictly forbidden — the app only opens the
 *    system settings screen and never requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — but
 *    steering users away from battery management is the kind of thing store review pushes back
 *    on, so the Play build explains the problem in text instead.
 */
object Distribution {
    const val ID = "play"

    const val HAS_SPECIAL_USE_FGS = false

    const val HAS_BATTERY_SHORTCUT = false
}
