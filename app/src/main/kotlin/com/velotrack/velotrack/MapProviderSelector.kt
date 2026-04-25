package com.velotrack.velotrack

import java.util.Locale

enum class MapProvider(val displayName: String) {
    AMAP("Amap (CN)"),
    GOOGLE_MAPS("Google Maps (Global)")
}

object MapProviderSelector {
    fun select(locale: Locale = Locale.getDefault()): MapProvider {
        val region = locale.country.uppercase(Locale.ROOT)
        return if (region == "CN") MapProvider.AMAP else MapProvider.GOOGLE_MAPS
    }
}
