package com.example.csideandroid.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.example.csideandroid.R
import com.example.csideandroid.storage.StorageAccess

// First-run entry. Single 'Choose Project Location' button. Creates 'Choicescript Projects' under the picked folder.
class MainMenuActivity : AppCompatActivity() {

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try { contentResolver.takePersistableUriPermission(uri, flags) } catch (_: Exception) {}

            val picked = DocumentFile.fromTreeUri(this, uri)
            val target = picked?.findFile("Choicescript Projects") ?: picked?.createDirectory("Choicescript Projects")
            if (target != null) {
                StorageAccess.setProjectsRoot(this, target.uri)
                startActivity(Intent(this, ProjectsBrowserActivity::class.java))
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)

        // If already configured, go straight to Projects
        if (StorageAccess.hasBase(this)) {
            startActivity(Intent(this, ProjectsBrowserActivity::class.java))
            finish()
            return
        }

        findViewById<Button>(R.id.btnChooseLocation).setOnClickListener {
            pickFolder.launch(null)
        }
    }
}
