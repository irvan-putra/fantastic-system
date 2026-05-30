package com.yourname.helloworld

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.TransportConfigCallback
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.util.FS
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.KeyPair
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import java.util.concurrent.TimeUnit

class GitHubPushActivity : AppCompatActivity() {

    private val sshDir: File by lazy { File(filesDir, "ssh").apply { mkdirs() } }
    private val privateKeyFile: File by lazy { File(sshDir, "id_rsa") }
    private val publicKeyFile: File by lazy { File(sshDir, "id_rsa.pub") }

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

        val publicKeyText = findViewById<TextView>(R.id.publicKeyText)
        val copyKeyBtn = findViewById<Button>(R.id.copyKeyButton)
        val owner = findViewById<TextInputEditText>(R.id.ownerInput)
        val repo = findViewById<TextInputEditText>(R.id.repoInput)
        val branch = findViewById<TextInputEditText>(R.id.branchInput)
        val message = findViewById<TextInputEditText>(R.id.messageInput)
        val status = findViewById<TextView>(R.id.statusText)

        // If a key already exists, show it.
        if (publicKeyFile.exists()) {
            publicKeyText.text = publicKeyFile.readText()
            copyKeyBtn.isEnabled = true
        }

        findViewById<Button>(R.id.generateKeyButton).setOnClickListener {
            lifecycleScope.launch {
                try {
                    val pub = withContext(Dispatchers.IO) { ensureSshKeypair() }
                    publicKeyText.text = pub
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

        findViewById<Button>(R.id.pickZipButton).setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        findViewById<Button>(R.id.pushButton).setOnClickListener {
            val o = owner.text?.toString()?.trim().orEmpty()
            val r = repo.text?.toString()?.trim().orEmpty()
            val b = branch.text?.toString()?.trim().ifEmpty { "main" }
            val m = message.text?.toString()?.trim().ifEmpty { "Upload project zip from Android" }
            val uri = selectedZipUri

            if (o.isEmpty() || r.isEmpty()) {
                status.text = "Missing owner/repo."
                return@setOnClickListener
            }
            if (uri == null) {
                status.text = "Pick a ZIP file first."
                return@setOnClickListener
            }
            if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
                status.text = "Generate SSH key first."
                return@setOnClickListener
            }

            status.text = "Unzipping + committing + pushing…"
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
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

    private fun ensureSshKeypair(): String {
        if (privateKeyFile.exists() && publicKeyFile.exists()) {
            return publicKeyFile.readText()
        }

        // Generate an RSA keypair using JSch and save it in app-private storage.
        val jsch = JSch()
        val kp = KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072)
        FileOutputStream(privateKeyFile).use { out -> kp.writePrivateKey(out) }
        FileOutputStream(publicKeyFile).use { out -> kp.writePublicKey(out, "hello-trae-android") }
        kp.dispose()

        return publicKeyFile.readText()
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

        val sshFactory = object : JschConfigSessionFactory() {
            override fun configure(hc: org.eclipse.jgit.transport.OpenSshConfig.Host?, session: Session) {
                // Hackathon convenience: do not fail on unknown host keys.
                session.setConfig("StrictHostKeyChecking", "no")
            }

            override fun createDefaultJSch(fs: FS?): JSch {
                val jsch = super.createDefaultJSch(fs)
                jsch.addIdentity(privateKeyFile.absolutePath)
                return jsch
            }
        }

        val transportConfigCallback = TransportConfigCallback { transport: Transport ->
            if (transport is SshTransport) {
                transport.sshSessionFactory = sshFactory
            }
        }

        val git = Git.init().setDirectory(repoDir).call()
        git.checkout().setCreateBranch(true).setName(branch).call()

        // Copy unzipped contents into the repo working tree
        copyDirIntoRepo(sourceDir, repoDir)

        git.add().addFilepattern(".").call()
        git.commit().setMessage(commitMessage).call()

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
}
