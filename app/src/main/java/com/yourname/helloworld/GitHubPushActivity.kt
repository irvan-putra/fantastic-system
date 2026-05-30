package com.yourname.helloworld

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitHubPushActivity : AppCompatActivity() {

    private val http = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var selectedZipUri: Uri? = null

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedZipUri = uri
        if (uri != null) {
            // Persist permission so we can read it later.
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            findViewById<TextView>(R.id.selectedFile).text = uri.toString()
        } else {
            findViewById<TextView>(R.id.selectedFile).text = "(no file selected)"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_github_push)

        val token = findViewById<TextInputEditText>(R.id.tokenInput)
        val owner = findViewById<TextInputEditText>(R.id.ownerInput)
        val repo = findViewById<TextInputEditText>(R.id.repoInput)
        val branch = findViewById<TextInputEditText>(R.id.branchInput)
        val path = findViewById<TextInputEditText>(R.id.pathInput)
        val message = findViewById<TextInputEditText>(R.id.messageInput)
        val status = findViewById<TextView>(R.id.statusText)

        findViewById<Button>(R.id.pickZipButton).setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        findViewById<Button>(R.id.pushButton).setOnClickListener {
            val t = token.text?.toString()?.trim().orEmpty()
            val o = owner.text?.toString()?.trim().orEmpty()
            val r = repo.text?.toString()?.trim().orEmpty()
            val b = branch.text?.toString()?.trim().ifEmpty { "main" }
            val p = path.text?.toString()?.trim().ifEmpty { "hello-trae-android.zip" }
            val m = message.text?.toString()?.trim().ifEmpty { "Upload project zip from Android" }
            val uri = selectedZipUri

            if (t.isEmpty() || o.isEmpty() || r.isEmpty()) {
                status.text = "Missing token/owner/repo."
                return@setOnClickListener
            }
            if (uri == null) {
                status.text = "Pick a ZIP file first."
                return@setOnClickListener
            }

            status.text = "Uploading…"
            lifecycleScope.launch {
                try {
                    val zipBytes = withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IOException("Unable to read selected file.")
                    }

                    val sha = getExistingSha(t, o, r, b, p)
                    putFile(t, o, r, b, p, m, zipBytes, sha)

                    status.text = "Done! Uploaded to $o/$r ($b) as $p"
                } catch (e: Exception) {
                    status.text = "Failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    private suspend fun getExistingSha(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        path: String
    ): String? = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$owner/$repo/contents/$path?ref=$branch"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "HelloTraeAndroid")
            .get()
            .build()

        http.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) {
                throw IOException("GET contents failed: HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            JSONObject(body).getString("sha")
        }
    }

    private suspend fun putFile(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        path: String,
        message: String,
        contentBytes: ByteArray,
        existingSha: String?
    ) = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$owner/$repo/contents/$path"
        val contentB64 = Base64.encodeToString(contentBytes, Base64.NO_WRAP)

        val json = JSONObject().apply {
            put("message", message)
            put("content", contentB64)
            put("branch", branch)
            if (existingSha != null) put("sha", existingSha)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "HelloTraeAndroid")
            .put(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                throw IOException("PUT contents failed: HTTP ${resp.code} $body")
            }
        }
    }
}

