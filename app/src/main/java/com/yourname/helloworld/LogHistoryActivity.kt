package com.yourname.helloworld

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogHistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_history)

        val logText = findViewById<TextView>(R.id.logText)

        fun refresh() {
            lifecycleScope.launch {
                val content = withContext(Dispatchers.IO) { AppLog.readAll(this@LogHistoryActivity) }
                logText.text = content.ifBlank { "(no logs yet)" }
            }
        }

        findViewById<Button>(R.id.copyAllButton).setOnClickListener {
            val text = logText.text?.toString().orEmpty()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Irvan Trae logs", text))
        }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { AppLog.clear(this@LogHistoryActivity) }
                refresh()
            }
        }

        refresh()
    }
}

