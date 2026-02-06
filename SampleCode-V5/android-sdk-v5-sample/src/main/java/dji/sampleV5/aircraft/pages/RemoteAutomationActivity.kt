package dji.sampleV5.aircraft.pages

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dji.sampleV5.aircraft.R

class RemoteAutomationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_automation)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.remote_automation_container, RemoteAutomationFragment())
                .commit()
        }
    }
}
