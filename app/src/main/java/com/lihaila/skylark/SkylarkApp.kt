package com.lihaila.skylark

import android.app.Application
import com.lihaila.skylark.data.AppStore

class SkylarkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStore.init(this)
    }
}
