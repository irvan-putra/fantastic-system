package com.yourname.helloworld

import android.net.Uri
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Button
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
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import org.apache.sshd.common.config.keys.PublicKeyEntry
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipInputStream

class GitHubPushActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("github_push", MODE_PRIVATE) }

    private val sshDir: File by lazy { File(filesDir, "ssh").apply { mkdirs() } }
    private val privateKeyFile: File by lazy { File(sshDir, "private_key.pk8.b64") }
    private val publicKeyFile: File by lazy { File(sshDir, "public_key.x509.b64") }
    private val keyAlgFile: File by lazy { File(sshDir, "key_alg.txt") }

    private var selectedZipUri: Uri? = null

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
                    val json = buildBackupJson(
                        owner = findViewById<TextInputEditText>(R.id.ownerInput).text?.toString().orEmpty(),
                        repo = findViewById<TextInputEditText>(R.id.repoInput).text?.toString().orEmpty(),
                        branch = findViewById<TextInputEditText>(R.id.branchInput).text?.toString().orEmpty(),
                        message = findViewById<TextInputEditText>(R.id.messageInput).text?.toString().orEmpty()
                    )
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

        // Load saved repo settings (and restore after reinstall via Android backup or Import backup).
        loadPrefsIntoUi()

        // If a key already exists, show it.
        updateKeyUi()

        findViewById<Button>(R.id.generateKeyButton).setOnClickListener {
            lifecycleScope.launch {
                try {
                    val pub = withContext(Dispatchers.IO) { ensureSshKeypair() }
                    publicKeyText.text = "Public key:\n$pub"
                    copyKeyBtn.isEnabled = true
                    status.text = "SSH key ready. Add it to GitHub → Settings → SSH keys."
                } catch (e: Exception) {
                    status.text = "Key generation failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }

        copyKeyBtn.setOnClickListener {
            val pub = publicKeyText.text?.toString().orEmpty()
            if (pub.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("GitHub SSH key", pub))
                status.text = "Public key copied."
            }
        }

        findViewById<Button>(R.id.exportBackupButton).setOnClickListener {
            exportBackup.launch("hello-trae-backup.json")
        }
        findViewById<Button>(R.id.importBackupButton).setOnClickListener {
            importBackup.launch(arrayOf("application/json", "text/plain"))
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
            if (!privateKeyFile.exists() || !publicKeyFile.exists() || !keyAlgFile.exists()) {
                status.text = "Generate SSH key first."
                return@setOnClickListener
            }

            status.text = "Unzipping + committing + pushing…"
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
                } catch (e: Exception) {
                    status.text = "Failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
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
        if (privateKeyFile.exists() && publicKeyFile.exists() && keyAlgFile.exists()) {
            publicKeyText.text = "Public key:\n${getOpenSshPublicKey(loadKeyPair().public)}"
            copyKeyBtn.isEnabled = true
        } else {
            publicKeyText.text = "(public key will appear here)"
            copyKeyBtn.isEnabled = false
        }
    }

    private fun buildBackupJson(owner: String, repo: String, branch: String, message: String): String {
        val obj = JSONObject()

        // SSH key (if present)
        if (privateKeyFile.exists() && publicKeyFile.exists() && keyAlgFile.exists()) {
            obj.put("key_alg", keyAlgFile.readText())
            obj.put("private_key_pk8_b64", privateKeyFile.readText())
            obj.put("public_key_x509_b64", publicKeyFile.readText())
        }

        // Repo settings
        obj.put("owner", owner.trim())
        obj.put("repo", repo.trim())
        obj.put("branch", branch.trim().ifEmpty { "main" })
        obj.put("message", message.trim().ifEmpty { "Upload project zip from Android" })

        return obj.toString(2)
    }

    private fun restoreFromBackupJson(json: String) {
        val obj = JSONObject(json)

        // Restore key files if present
        if (obj.has("key_alg") && obj.has("private_key_pk8_b64") && obj.has("public_key_x509_b64")) {
            sshDir.mkdirs()
            keyAlgFile.writeText(obj.getString("key_alg"))
            privateKeyFile.writeText(obj.getString("private_key_pk8_b64"))
            publicKeyFile.writeText(obj.getString("public_key_x509_b64"))
        }

        // Restore repo prefs
        prefs.edit()
            .putString("owner", obj.optString("owner", ""))
            .putString("repo", obj.optString("repo", ""))
            .putString("branch", obj.optString("branch", "main"))
            .putString("message", obj.optString("message", "Upload project zip from Android"))
            .apply()
    }

    private fun ensureSshKeypair(): String {
        if (privateKeyFile.exists() && publicKeyFile.exists() && keyAlgFile.exists()) {
            return getOpenSshPublicKey(loadKeyPair().public)
        }

        // Prefer a modern key type:
        // 1) Ed25519 (if available on this Android device)
        // 2) ECDSA P-256
        // 3) RSA 3072 (still OK with modern SSH clients that sign with rsa-sha2-256/512)
        val (alg, keyPair) = generateModernKeyPair()

        keyAlgFile.writeText(alg)
        privateKeyFile.writeText(android.util.Base64.encodeToString(keyPair.private.encoded, android.util.Base64.NO_WRAP))
        publicKeyFile.writeText(android.util.Base64.encodeToString(keyPair.public.encoded, android.util.Base64.NO_WRAP))

        return getOpenSshPublicKey(keyPair.public)
    }

    private fun generateModernKeyPair(): Pair<String, KeyPair> {
        // Try Ed25519
        try {
            val kpg = KeyPairGenerator.getInstance("Ed25519")
            return "Ed25519" to kpg.generateKeyPair()
        } catch (_: Throwable) {
            // ignore
        }

        // ECDSA P-256
        try {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            return "EC" to kpg.generateKeyPair()
        } catch (_: Throwable) {
            // ignore
        }

        // RSA fallback
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(3072)
        return "RSA" to kpg.generateKeyPair()
    }

    private fun loadKeyPair(): KeyPair {
        val alg = keyAlgFile.readText().trim()
        val privBytes = android.util.Base64.decode(privateKeyFile.readText(), android.util.Base64.NO_WRAP)
        val pubBytes = android.util.Base64.decode(publicKeyFile.readText(), android.util.Base64.NO_WRAP)

        val kf = KeyFactory.getInstance(alg)
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
        val pub = kf.generatePublic(X509EncodedKeySpec(pubBytes))
        return KeyPair(pub, priv)
    }

    private fun getOpenSshPublicKey(publicKey: PublicKey): String {
        // Apache sshd can render OpenSSH format: "<type> <base64>"
        return PublicKeyEntry.toString(publicKey) + " hello-trae-android"
    }

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
        val remoteUrl = "git@github.com:$owner/$repo.git"

        val keyPair = loadKeyPair()

        // Use JGit's Apache MINA sshd implementation (modern algorithms; avoids legacy ssh-rsa/SHA-1).
        val sshFactory: SshdSessionFactory = SshdSessionFactoryBuilder()
            .setHomeDirectory(filesDir)
            .setSshDirectory(sshDir)
            .setDefaultKeysProvider { _ -> listOf(keyPair) }
            // Hackathon convenience: accept all host keys (no known_hosts management).
            .setServerKeyDatabase { _, _ -> AcceptAllServerKeyDatabase() }
            .build(null)

        val transportConfigCallback = TransportConfigCallback { transport: Transport ->
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
        // Allow empty commits in case the ZIP had no files or only ignored paths.
        git.commit().setAllowEmpty(true).setMessage(commitMessage).call()

        val cfg = git.repository.config
        cfg.setString("remote", "origin", "url", remoteUrl)
        cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
        cfg.save()

        git.push()
            .setRemote("origin")
            .setTransportConfigCallback(transportConfigCallback)
            .setRefSpecs(org.eclipse.jgit.transport.RefSpec(Constants.R_HEADS + branch + ":" + Constants.R_HEADS + branch))
            .call()

        git.close()
    }

    private class AcceptAllServerKeyDatabase : ServerKeyDatabase {
        override fun lookup(
            connectAddress: String,
            remoteAddress: InetSocketAddress,
            config: ServerKeyDatabase.Configuration
        ): List<PublicKey> = emptyList()

        override fun accept(
            connectAddress: String,
            remoteAddress: InetSocketAddress,
            serverKey: PublicKey,
            config: ServerKeyDatabase.Configuration,
            provider: org.eclipse.jgit.transport.CredentialsProvider?
        ): Boolean = true
    }
}
