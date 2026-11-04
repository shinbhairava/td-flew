package com.shinbhairava.tdflew

import android.app.Application
import android.content.Context
import com.shinbhairava.tdflew.data.SettingsStore
import com.shinbhairava.tdflew.data.TransferStore
import com.shinbhairava.tdflew.engine.TelegramManager

class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val transfers = TransferStore(context)
    val telegram = TelegramManager(context, settings, transfers)
}

class TdFlewApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.telegram.start()
    }
}
