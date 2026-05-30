package com.yourname.helloworld

import android.os.Bundle
import android.widget.Button
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.pushButton).setOnClickListener {
            startActivity(Intent(this, GitHubPushActivity::class.java))
        }
    }
}
