package com.velotrack.velotrack

import java.util.Locale

enum class MapProvider(val displayName: String) {
    AMAP("Amap (CN)"),
    GOOGLE_MAPS("Google Maps (Global)")
}

object MapProviderSelector {
    fun select(
        locale: Locale = Locale.getDefault(),
        override: String = BuildConfig.MAP_PROVIDER_OVERRIDE,
    ): MapProvider {
        parseOverride(override)?.let { return it }
        val region = locale.country.uppercase(Locale.ROOT)
        return if (region == "CN") MapProvider.AMAP else MapProvider.GOOGLE_MAPS
    }

    private fun parseOverride(raw: String): MapProvider? =
        when (raw.trim().uppercase(Locale.ROOT)) {
            "AMAP", "A_MAP", "GAODE", "CN" -> MapProvider.AMAP
            "GOOGLE", "GOOGLE_MAPS", "GLOBAL" -> MapProvider.GOOGLE_MAPS
            else -> null
        }
}
