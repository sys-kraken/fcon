package app.fcon

import android.app.Application

class FconApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConversionService.createChannel(this)
        Engine.start(this)
        Cookies.refreshAsync(this)
    }
}
