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

class FirstRunActivity : AppCompatActivity() {

    private val pickBase =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {

                // *** FIXED PERSISTABLE PERMISSION FOR XIAOMI & OTHER OEMs ***
                val flags =
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

                try {
                    contentResolver.takePersistableUriPermission(uri, flags)
                } catch (_: Exception) {
                    // Xiaomi/HyperOS sometimes throws even when the permission is OK — ignore
                }

                // Find or create the "Choicescript Projects" directory
                val picked = DocumentFile.fromTreeUri(this, uri)
                val projects = if (picked?.name == "Choicescript Projects") {
                    picked
                } else {
                    picked?.findFile("Choicescript Projects") ?: picked?.createDirectory("Choicescript Projects")
                }

                if (projects != null) {
                    StorageAccess.setProjectsRoot(this, projects.uri)
                    startActivity(Intent(this, ProjectsBrowserActivity::class.java))
                    finish()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)

        if (StorageAccess.hasBase(this)) {
            startActivity(Intent(this, ProjectsBrowserActivity::class.java))
            finish()
            return
        }

        findViewById<Button>(R.id.btnChooseLocation).setOnClickListener {
            pickBase.launch(null)
        }
    }
}
