package it.eldavo.ylih

/**
 * Build-variant capabilities. The `classic` flavor is the sideloaded build and uses everything
 * the platform allows; see the `play` flavor for the store-safe subset.
 */
object Distribution {
    const val ID = "classic"

    /**
     * The manifest declares `specialUse` alongside `connectedDevice`, so detailed tracking also
     * works for someone who wants wired headphones tracked but denied Bluetooth access.
     */
    const val HAS_SPECIAL_USE_FGS = true

    /** Offers a shortcut into the battery-optimisation settings screen. */
    const val HAS_BATTERY_SHORTCUT = true
}
