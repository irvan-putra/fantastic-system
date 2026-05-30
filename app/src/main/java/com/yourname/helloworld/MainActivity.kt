package com.yourname.helloworld

import android.os.Bundle
import android.widget.Button
import android.content.Intent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.pushButton).setOnClickListener {
            startActivity(Intent(this, GitHubPushActivity::class.java))
        }

        findViewById<TextView>(R.id.commitText).text = "Latest commit: ${BuildConfig.LATEST_COMMIT_MSG}"
    }
}
