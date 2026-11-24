package nl.vitaal.app

import android.app.Application

class VitaalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(this)
    }
}
