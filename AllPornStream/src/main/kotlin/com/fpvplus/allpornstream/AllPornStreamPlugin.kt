package com.fpvplus.allpornstream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class AllPornStreamPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AllPornStream())
    }
}
