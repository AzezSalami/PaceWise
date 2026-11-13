package app.pacewise

import android.app.Application
import app.pacewise.data.Repository
import app.pacewise.reminders.Reminders
import app.pacewise.sync.PhoneSync

class PaceWiseApp : Application() {
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)
        Reminders.createChannels(this)
        Reminders.scheduleAll(this)
        PhoneSync.pushPlan(this)
    }
}
