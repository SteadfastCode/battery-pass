package com.steadfastcode.batterypass

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Logs.init(this)
        Notifications.ensureChannels(this)
        Logs.light("app process start v=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Updater.onAppStart(this)
    }
}
