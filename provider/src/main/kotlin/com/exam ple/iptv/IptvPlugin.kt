package com.example.iptv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IptvPlugin : Plugin() {
    override fun load(context: Context) {
        val store = CustomChannelStore(context)
        registerMainAPI(IptvProvider(store))
        // Nút "Cài đặt" của plugin trong màn hình Tiện ích mở rộng.
        openSettings = { ctx -> SettingsUi.showMenu(ctx, store) }
    }
}
