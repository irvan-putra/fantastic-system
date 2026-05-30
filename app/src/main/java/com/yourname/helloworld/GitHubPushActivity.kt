package com.yourname.helloworld

import android.net.Uri
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import android.net.Uri as AndroidUri
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.RemoteRefUpdate
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.KeyPair
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig

class GitHubPushActivity : AppCompatActivity() {

    private val logTag = "GitHubPush"
    private var lastErrorForCopy: String = ""

    private val prefs by lazy { getSharedPreferences("github_push", MODE_PRIVATE) }

    private val sshDir: File by lazy { File(filesDir, "ssh").apply { mkdirs() } }
    private val privateKeyFile: File by lazy { File(sshDir, "id_key") }
    private val publicKeyFile: File by lazy { File(sshDir, "id_key.pub") }
    private val keyTypeFile: File by lazy { File(sshDir, "key_type.txt") }

    private var selectedZipUri: Uri? = null
    private var pushRocketAnim: ObjectAnimator? = null

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedZipUri = uri
        if (uri != null) {
            // Persist permission so we can read it later.
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            findViewById<TextView>(R.id.selectedFile).text = uri.toString()
        } else {
            findViewById<TextView>(R.id.selectedFile).text = "(no file selected)"
        }
    }

    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Make sure the latest UI values are persisted before exporting.
                    saveUiToPrefs()
                    val json = buildBackupJson()
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IOException("Unable to write backup file.")
                }
                findViewById<TextView>(R.id.statusText).text = "Backup exported."
            } catch (e: Exception) {
                findViewById<TextView>(R.id.statusText).text = "Export failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val json = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw IOException("Unable to read backup file.")
                    restoreFromBackupJson(json)
                }
                loadPrefsIntoUi()
                updateKeyUi()
                findViewById<TextView>(R.id.statusText).text = "Backup imported."
            } catch (e: Exception) {
                findViewById<TextView>(R.id.statusText).text = "Import failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_github_push)

        val publicKeyText = findViewById<TextView>(R.id.publicKeyText)
        val copyKeyBtn = findViewById<Button>(R.id.copyKeyButton)
        val owner = findViewById<TextInputEditText>(R.id.ownerInput)
        val repo = findViewById<TextInputEditText>(R.id.repoInput)
        val branch = findViewById<TextInputEditText>(R.id.branchInput)
        val message = findViewById<TextInputEditText>(R.id.messageInput)
        val status = findViewById<TextView>(R.id.statusText)
        val pushRocket = findViewById<ImageView>(R.id.pushRocket)
        lastErrorForCopy = ""

        // Load saved repo settings (and restore after reinstall via Android backup or Import backup).
        loadPrefsIntoUi()

        // If a key already exists, show it.
        updateKeyUi()

        findViewById<Button>(R.id.generateKeyButton).setOnClickListener {
            lifecycleScope.launch {
                try {
                    val pub = withContext(Dispatchers.IO) { ensureSshKeypair() }
                    publicKeyText.text = pub
                    copyKeyBtn.isEnabled = true
                    status.text = "SSH key ready. Add it to GitHub → Settings → SSH keys."
                    AppLog.append(this@GitHubPushActivity, logTag, "Generated SSH key")
                } catch (e: Exception) {
                    status.text = "Key generation failed: ${e.message ?: e.javaClass.simpleName}"
                    AppLog.appendException(this@GitHubPushActivity, logTag, "Key generation failed", e)
                }
            }
        }

        copyKeyBtn.setOnClickListener {
            val pub = try {
                if (privateKeyFile.exists() && publicKeyFile.exists()) getOpenSshPublicKey() else ""
            } catch (_: Exception) {
                ""
            }
            if (pub.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("GitHub SSH key", pub))
                status.text = "Public key copied."
                AppLog.append(this@GitHubPushActivity, logTag, "Public key copied to clipboard")
            } else {
                status.text = "No public key yet. Generate SSH key first."
            }
        }

        findViewById<Button>(R.id.exportBackupButton).setOnClickListener {
            exportBackup.launch("irvan-trae-backup.json")
        }
        findViewById<Button>(R.id.importBackupButton).setOnClickListener {
            importBackup.launch(arrayOf("application/json", "text/plain"))
        }

        findViewById<Button>(R.id.copyLogButton).setOnClickListener {
            val text = if (lastErrorForCopy.isNotBlank()) lastErrorForCopy else AppLog.readAll(this)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Irvan Trae log", text))
            status.text = "Log copied."
        }

        findViewById<Button>(R.id.viewHistoryButton).setOnClickListener {
            startActivity(Intent(this, LogHistoryActivity::class.java))
        }

        findViewById<Button>(R.id.openGithubButton).setOnClickListener {
            val o = owner.text?.toString()?.trim().orEmpty()
            val r = repo.text?.toString()?.trim().orEmpty()
            val b = branch.text?.toString()?.trim().orEmpty().ifEmpty { "main" }
            if (o.isBlank() || r.isBlank()) {
                status.text = "Missing owner/repo."
                return@setOnClickListener
            }
            val url = "https://github.com/$o/$r/tree/$b"
            startActivity(Intent(Intent.ACTION_VIEW, AndroidUri.parse(url)))
        }

        findViewById<Button>(R.id.pickZipButton).setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        findViewById<Button>(R.id.pushButton).setOnClickListener {
            val o = owner.text?.toString()?.trim().orEmpty()
            val r = repo.text?.toString()?.trim().orEmpty()
            val b = branch.text?.toString()?.trim().orEmpty().ifEmpty { "main" }
            val m = message.text?.toString()?.trim().orEmpty().ifEmpty { "Upload project zip from Android" }
            val uri = selectedZipUri

            if (o.isEmpty() || r.isEmpty()) {
                status.text = "Missing owner/repo."
                return@setOnClickListener
            }
            if (uri == null) {
                status.text = "Pick a ZIP file first."
                return@setOnClickListener
            }
            if (!privateKeyFile.exists() || !publicKeyFile.exists() || !keyTypeFile.exists()) {
                status.text = "Generate SSH key first."
                return@setOnClickListener
            }

            status.text = "Unzipping + committing + pushing…"
            AppLog.append(this@GitHubPushActivity, logTag, "Push start: $o/$r branch=$b")
            startPushRocket(pushRocket)
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        saveUiToPrefs()
                        ensureSshKeypair()
                        val unzipDir = File(cacheDir, "zip_extract").apply {
                            deleteRecursively()
                            mkdirs()
                        }
                        unzipFromUri(uri, unzipDir)

                        val repoDir = File(cacheDir, "git_repo").apply {
                            deleteRecursively()
                            mkdirs()
                        }

                        gitPushDirectoryOverSsh(
                            owner = o,
                            repo = r,
                            branch = b,
                            commitMessage = m,
                            sourceDir = unzipDir,
                            repoDir = repoDir
                        )
                    }
                    status.text = "Done! Pushed to $o/$r ($b)"
                    AppLog.append(this@GitHubPushActivity, logTag, "Push success: $o/$r branch=$b")
                    stopPushRocket(pushRocket)
                } catch (e: Exception) {
                    Log.e(logTag, "Push failed", e)
                    val full = formatError(e)
                    lastErrorForCopy = full
                    status.text = "Failed: $full"
                    AppLog.appendException(this@GitHubPushActivity, logTag, "Push failed", e)
                    stopPushRocket(pushRocket)
                }
            }
        }
    }

    private fun startPushRocket(rocket: ImageView) {
        stopPushRocket(rocket)
        rocket.visibility = View.VISIBLE
        rocket.bringToFront()
        rocket.post {
            val parent = rocket.parent as? View ?: return@post
            val startY = -rocket.height.toFloat()
            val endY = (parent.height + rocket.height).toFloat()
            rocket.translationY = startY
            pushRocketAnim = ObjectAnimator.ofFloat(rocket, "translationY", startY, endY).apply {
                duration = 4200
                interpolator = LinearInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        }
    }

    private fun stopPushRocket(rocket: ImageView) {
        pushRocketAnim?.cancel()
        pushRocketAnim = null
        rocket.visibility = View.GONE
        rocket.translationY = 0f
    }

    override fun onPause() {
        super.onPause()
        saveUiToPrefs()
    }

    private fun loadPrefsIntoUi() {
        findViewById<TextInputEditText>(R.id.ownerInput).setText(prefs.getString("owner", "").orEmpty())
        findViewById<TextInputEditText>(R.id.repoInput).setText(prefs.getString("repo", "").orEmpty())
        findViewById<TextInputEditText>(R.id.branchInput).setText(prefs.getString("branch", "main").orEmpty())
        findViewById<TextInputEditText>(R.id.messageInput).setText(
            prefs.getString("message", "Upload project zip from Android").orEmpty()
        )
    }

    private fun saveUiToPrefs() {
        val owner = findViewById<TextInputEditText>(R.id.ownerInput).text?.toString()?.trim().orEmpty()
        val repo = findViewById<TextInputEditText>(R.id.repoInput).text?.toString()?.trim().orEmpty()
        val branch = findViewById<TextInputEditText>(R.id.branchInput).text?.toString()?.trim().orEmpty()
        val message = findViewById<TextInputEditText>(R.id.messageInput).text?.toString()?.trim().orEmpty()

        prefs.edit()
            .putString("owner", owner)
            .putString("repo", repo)
            .putString("branch", branch.ifEmpty { "main" })
            .putString("message", message.ifEmpty { "Upload project zip from Android" })
            .apply()
    }

    private fun updateKeyUi() {
        val publicKeyText = findViewById<TextView>(R.id.publicKeyText)
        val copyKeyBtn = findViewById<Button>(R.id.copyKeyButton)
        if (privateKeyFile.exists() && publicKeyFile.exists() && keyTypeFile.exists()) {
            publicKeyText.text = getOpenSshPublicKey()
            copyKeyBtn.isEnabled = true
        } else {
            publicKeyText.text = "(public key will appear here)"
            copyKeyBtn.isEnabled = false
        }
    }

    private fun buildBackupJson(): String {
        val obj = JSONObject()

        // SSH key (if present)
        if (privateKeyFile.exists() && publicKeyFile.exists() && keyTypeFile.exists()) {
            obj.put("key_type", keyTypeFile.readText())
            obj.put("private_key", privateKeyFile.readText())
            obj.put("public_key", publicKeyFile.readText())
        }

        // Repo settings
        obj.put("owner", prefs.getString("owner", "").orEmpty().trim())
        obj.put("repo", prefs.getString("repo", "").orEmpty().trim())
        obj.put("branch", prefs.getString("branch", "main").orEmpty().trim().ifEmpty { "main" })
        obj.put("message", prefs.getString("message", "Upload project zip from Android").orEmpty().trim())

        return obj.toString(2)
    }

    private fun restoreFromBackupJson(json: String) {
        val obj = JSONObject(json)

        // Restore key files if present
        if (obj.has("key_type") && obj.has("private_key") && obj.has("public_key")) {
            sshDir.mkdirs()
            keyTypeFile.writeText(obj.getString("key_type"))
            privateKeyFile.writeText(obj.getString("private_key"))
            publicKeyFile.writeText(obj.getString("public_key"))
        }

        // Restore repo prefs
        prefs.edit()
            .putString("owner", obj.optString("owner", ""))
            .putString("repo", obj.optString("repo", ""))
            .putString("branch", obj.optString("branch", "main"))
            .putString("message", obj.optString("message", "Upload project zip from Android"))
            .apply()

        AppLog.append(
            this,
            logTag,
            "Imported backup repo details: ${obj.optString("owner", "")}/${obj.optString("repo", "")} branch=${obj.optString("branch", "main")}"
        )
    }

    private fun ensureSshKeypair(): String {
        if (privateKeyFile.exists() && publicKeyFile.exists() && keyTypeFile.exists()) {
            return getOpenSshPublicKey()
        }

        // Generate a key using JSch (mwiede fork supports modern rsa-sha2 signatures on GitHub).
        val jsch = JSch()
        val kp = KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072)
        FileOutputStream(privateKeyFile).use { out -> kp.writePrivateKey(out) }
        FileOutputStream(publicKeyFile).use { out -> kp.writePublicKey(out, "irvan-trae-app") }
        keyTypeFile.writeText("RSA")
        kp.dispose()

        return getOpenSshPublicKey()
    }

    private fun getOpenSshPublicKey(): String = publicKeyFile.readText().trim()

    private fun unzipFromUri(uri: Uri, targetDir: File) {
        contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    if (name.startsWith("__MACOSX/") || name.contains("../")) {
                        zis.closeEntry()
                        continue
                    }
                    val outFile = File(targetDir, name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                }
            }
        } ?: throw IOException("Unable to read ZIP from picker.")
    }

    private fun copyDirIntoRepo(source: File, repoRoot: File) {
        source.walkTopDown().forEach { f ->
            val rel = f.relativeTo(source).path
            if (rel.isEmpty()) return@forEach
            if (rel.startsWith(".git/")) return@forEach
            val dest = File(repoRoot, rel)
            if (f.isDirectory) {
                dest.mkdirs()
            } else {
                dest.parentFile?.mkdirs()
                f.inputStream().use { it.copyTo(dest.outputStream()) }
            }
        }
    }

    private fun gitPushDirectoryOverSsh(
        owner: String,
        repo: String,
        branch: String,
        commitMessage: String,
        sourceDir: File,
        repoDir: File
    ) {
        // JGit expects a "user home" directory. On Android this property may be missing,
        // causing errors like "IllegalArgumentException: No user home".
        ensureJGitUserHome()

        val remoteUrl = "git@github.com:$owner/$repo.git"

        val sshFactory = object : JschConfigSessionFactory() {
            override fun configure(hc: OpenSshConfig.Host?, session: Session) {
                // Hackathon convenience: do not fail on unknown host keys.
                session.setConfig("StrictHostKeyChecking", "no")
            }

            override fun createDefaultJSch(fs: org.eclipse.jgit.util.FS?): JSch {
                val jsch = super.createDefaultJSch(fs)
                // Use the key generated by the app.
                jsch.addIdentity(privateKeyFile.absolutePath)
                return jsch
            }
        }

        val transportConfigCallback = TransportConfigCallback { transport: Transport ->
            // Increase network timeout: the first push can be large and slow on mobile networks.
            transport.timeout = 180
            if (transport is SshTransport) {
                transport.sshSessionFactory = sshFactory
            }
        }

        // IMPORTANT: for a brand-new repo, HEAD is "unborn" until the first commit.
        // Creating/checking out a branch before the first commit can fail with:
        // "Ref HEAD cannot be resolved". So we set the initial branch at init time.
        val git = Git.init()
            .setDirectory(repoDir)
            .setInitialBranch(branch)
            .call()

        // Copy unzipped contents into the repo working tree
        copyDirIntoRepo(sourceDir, repoDir)

        git.add().addFilepattern(".").call()

        val st = git.status().call()
        AppLog.append(
            this,
            logTag,
            "Git status: added=${st.added.size} changed=${st.changed.size} modified=${st.modified.size} removed=${st.removed.size} untracked=${st.untracked.size}"
        )
        // Allow empty commits in case the ZIP had no files or only ignored paths.
        val committed: RevCommit = git.commit().setAllowEmpty(true).setMessage(commitMessage).call()
        AppLog.append(this, logTag, "Created commit ${committed.name.take(10)} on branch $branch")

        val cfg = git.repository.config
        cfg.setString("remote", "origin", "url", remoteUrl)
        cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
        cfg.save()

        val results = git.push()
            .setRemote("origin")
            .setTransportConfigCallback(transportConfigCallback)
            .setTimeout(180)
            .setRefSpecs(org.eclipse.jgit.transport.RefSpec(Constants.R_HEADS + branch + ":" + Constants.R_HEADS + branch))
            .call()

        // Log remote update details (useful when users think push succeeded but nothing changed).
        for (res in results) {
            for (u in res.remoteUpdates) {
                AppLog.append(this, logTag, "Remote update ${u.remoteName}: ${u.status} ${u.message ?: ""}".trim())
            }
        }

        // Treat non-OK statuses as a failure. Otherwise the UI may incorrectly say "success"
        // even if the remote rejected the update (e.g., REJECTED_NONFASTFORWARD).
        val rejected = results
            .flatMap { it.remoteUpdates }
            .firstOrNull { u ->
                u.status != RemoteRefUpdate.Status.OK &&
                    u.status != RemoteRefUpdate.Status.UP_TO_DATE
            }
        if (rejected != null) {
            throw TransportException(
                "Push rejected: ${rejected.remoteName} ${rejected.status}" +
                    (rejected.message?.let { " ($it)" } ?: "") +
                    ". Tip: change Branch to a new branch name and push again, or fast-forward/merge on GitHub first."
            )
        }

        git.close()
    }

    private fun ensureJGitUserHome() {
        val home = System.getProperty("user.home")
        if (home.isNullOrBlank()) {
            // Use app-private storage as a safe home directory substitute.
            System.setProperty("user.home", filesDir.absolutePath)
        }
    }

    private fun formatError(e: Throwable): String {
        // Provide a compact but informative message with cause chain.
        val parts = mutableListOf<String>()
        var cur: Throwable? = e
        var guard = 0
        while (cur != null && guard++ < 6) {
            val msg = cur.message?.trim().orEmpty()
            val name = cur.javaClass.simpleName
            when {
                msg.isNotEmpty() -> parts += "$name: $msg"
                else -> parts += name
            }
            cur = cur.cause
        }

        // Common hint for GitHub over SSH when the server closes abruptly.
        val combined = parts.joinToString(" ")
        val hint = when {
            combined.contains("Auth fail for methods 'publickey'") ->
                " (fix: copy the public key and add it in GitHub → Settings → SSH keys; then verify you have access to this repo/branch)"
            e is TransportException ->
                " (check: SSH key added to GitHub, repo access, stable network)"
            else -> ""
        }
        return parts.distinct().joinToString(" → ") + hint
    }
}
