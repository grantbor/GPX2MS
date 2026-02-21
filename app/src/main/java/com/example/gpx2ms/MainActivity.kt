package com.example.gpx2ms  // оставь свой package

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class MainActivity : AppCompatActivity() {

    private var pickedUri: Uri? = null

    private var outputBytes: ByteArray? = null
    private var suggestedOutName: String = "converted.ms"
    private var shareFile: File? = null

    // Result state: enabled only after Convert (or after successful Append)
    private var hasResult: Boolean = false

    private lateinit var btnConvert: Button
    private lateinit var btnAppend: Button
    private lateinit var btnSave: Button
    private lateinit var btnShare: Button
    private lateinit var btnOpenGuru: Button

    private lateinit var txtPicked: TextView
    private lateinit var txtResult: TextView

    // --- Pick input GPX/MS ---
    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            txtPicked.setText(R.string.selection_cancelled)
            pickedUri = null
            resetResultUi()
            txtResult.setText(R.string.result_placeholder)
            updateButtons()
            return@registerForActivityResult
        }

        val name = getDisplayName(uri).lowercase()
        if (!name.endsWith(".gpx") && !name.endsWith(".ms")) {
            txtPicked.setText(R.string.unsupported_file)
            pickedUri = null
            resetResultUi()
            txtResult.setText(R.string.result_placeholder)
            updateButtons()
            return@registerForActivityResult
        }

        pickedUri = uri

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { /* ok */ }

        txtPicked.text = getString(R.string.file_selected, name)

        // New input invalidates previous result actions
        resetResultUi()
        txtResult.text = "Input selected.\nPress Convert to generate result."

        updateButtons()
    }

    // --- Append: user picks target .ms here ---
    private val pickAppendTarget = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            txtResult.text = "Append cancelled."
            return@registerForActivityResult
        }

        val targetName = getDisplayName(uri)
        if (!targetName.lowercase().endsWith(".ms")) {
            txtResult.text = "Unsupported target file.\nPlease select a .ms file."
            return@registerForActivityResult
        }

        // Try to persist read/write permissions if provider supports it
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { /* ok */ }

        doAppendIntoTarget(uri, targetName)
    }

    // --- Save: one dialog (Create new OR pick existing to overwrite) ---
    private val saveChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = res.data?.data
        if (uri == null) {
            txtResult.setText(R.string.save_cancelled)
            return@registerForActivityResult
        }

        val bytes = outputBytes
        if (!hasResult || bytes == null) {
            txtResult.setText(R.string.nothing_to_save)
            return@registerForActivityResult
        }

        // If user picked existing doc via OpenDocument, persistable permissions might be needed
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { /* ok */ }

        try {
            // "wt" helps overwrite when provider supports it
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(bytes)
            } ?: throw IllegalStateException("Could not open output stream.")

            txtResult.setText(R.string.save_done)
        } catch (e: Exception) {
            txtResult.text = "ERROR saving:\n${e.message}\n\n$e"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        val btnPick = findViewById<Button>(R.id.btnPick)

        btnConvert = findViewById(R.id.btnConvert)
        btnAppend = findViewById(R.id.btnAppend)
        btnSave = findViewById(R.id.btnSave)
        btnShare = findViewById(R.id.btnShare)
        btnOpenGuru = findViewById(R.id.btnOpenGuru)

        txtPicked = findViewById(R.id.txtPicked)
        txtResult = findViewById(R.id.txtResult)

        txtPicked.setText(R.string.file_not_selected)
        txtResult.setText(R.string.result_placeholder)

        resetResultUi()
        updateButtons()

        btnPick.setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }

        // Convert: generate result to internal output.* (does NOT overwrite any user file)
        btnConvert.setOnClickListener {
            val uri = pickedUri
            if (uri == null) {
                txtResult.setText(R.string.no_file_selected)
                return@setOnClickListener
            }

            try {
                txtResult.text = "CONVERT\n1/2 Preparing…"

                val inName = getDisplayName(uri)
                val inLower = inName.lowercase()
                val inExt = when {
                    inLower.endsWith(".gpx") -> "gpx"
                    inLower.endsWith(".ms") -> "ms"
                    else -> ""
                }
                if (inExt.isEmpty()) {
                    txtResult.setText(R.string.unsupported_file)
                    return@setOnClickListener
                }

                val inFile = File(filesDir, "input.$inExt")
                contentResolver.openInputStream(uri)!!.use { input ->
                    inFile.outputStream().use { output -> input.copyTo(output) }
                }

                val outExt = if (inExt == "gpx") "ms" else "gpx"
                val outFile = File(filesDir, "output.$outExt")

                txtResult.text = "CONVERT\n2/2 Running…"

                val py = Python.getInstance()
                val bridge = py.getModule("bridge")
                val pyResult = bridge.callAttr(
                    "convert",
                    inFile.absolutePath,
                    outFile.absolutePath,
                    null,   // auto
                    "none", // line_mode
                    ""      // style
                ).toString()

                val bytes = outFile.readBytes()
                outputBytes = bytes
                hasResult = true

                suggestedOutName = makeSuggestedName(inName, outExt)

                // Share/Open-in-Guru use the same prepared file
                val sf = File(filesDir, suggestedOutName)
                if (sf.exists()) sf.delete()
                outFile.copyTo(sf, overwrite = true)
                shareFile = sf

                updateButtons()

                txtResult.text =
                    "$pyResult\n\nГотово.\nТеперь можно: Добавить в файл / Сохранить новый / Поделиться / Открыть в Guru Maps."

            } catch (e: Exception) {
                txtResult.text = "CONVERT FAILED ❌\n${e.message}\n\n$e"
            }
        }

        // Append: ask user to pick target MS, then append GPX into it (overwrite that target)
        btnAppend.setOnClickListener {
            if (!hasResult || outputBytes == null) {
                txtResult.text = "No result yet.\nPress Convert first."
                return@setOnClickListener
            }
            val inUri = pickedUri
            if (inUri == null || !getDisplayName(inUri).lowercase().endsWith(".gpx")) {
                txtResult.text = "Append works with GPX input.\nPick a .gpx file first."
                return@setOnClickListener
            }

            txtResult.text = "Pick target .ms to append into…"
            pickAppendTarget.launch(arrayOf("*/*"))
        }

        // Save: one dialog. User can create new OR pick existing and overwrite
        btnSave.setOnClickListener {
            if (!hasResult || outputBytes == null) {
                txtResult.setText(R.string.nothing_to_save)
                return@setOnClickListener
            }

            val createIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, suggestedOutName)
            }

            val overwriteIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            }

            val chooser = Intent.createChooser(createIntent, "Save result")
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(overwriteIntent))

            saveChooserLauncher.launch(chooser)
        }

        // Share: same as before
        btnShare.setOnClickListener {
            val file = shareFile
            if (!hasResult || file == null || !file.exists()) {
                txtResult.setText(R.string.nothing_to_save)
                return@setOnClickListener
            }

            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            val mime = when {
                file.name.lowercase().endsWith(".gpx") -> "application/gpx+xml"
                else -> "application/octet-stream"
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_title)))
        }

        // Open in Guru Maps (smart: if not installed -> open Play Store)
        btnOpenGuru.setOnClickListener {
            val file = shareFile
            if (!hasResult || file == null || !file.exists()) {
                txtResult.setText(R.string.nothing_to_save)
                return@setOnClickListener
            }

            val contentUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            val mime = when {
                file.name.lowercase().endsWith(".gpx") -> "application/gpx+xml"
                else -> "application/octet-stream"
            }

            val guruFree = "com.bodunov.galileo"
            val guruPro = "com.bodunov.GalileoPro"

            val targetPkg: String? = when {
                isPackageInstalled(guruFree) -> guruFree
                isPackageInstalled(guruPro) -> guruPro
                else -> null
            }

            if (targetPkg == null) {
                txtResult.text = "Guru Maps is not installed. Opening Google Play…"
                openPlayStore(guruFree)
                return@setOnClickListener
            }

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage(targetPkg)
            }

            try {
                startActivity(viewIntent)
            } catch (_: Exception) {
                val chooser = Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Open with…"
                )
                startActivity(chooser)
            }
        }
    }

    // --- Append implementation (does real GPX->MS append into selected target) ---

    private fun doAppendIntoTarget(targetUri: Uri, targetName: String) {
        val gpxUri = pickedUri
        if (gpxUri == null) {
            txtResult.text = "No input selected."
            return
        }
        val gpxName = getDisplayName(gpxUri).lowercase()
        if (!gpxName.endsWith(".gpx")) {
            txtResult.text = "Append works with GPX input.\nPick a .gpx file first."
            return
        }

        try {
            txtResult.text = "APPEND\n1/4 Reading target…"

            val beforeSize = try {
                contentResolver.openFileDescriptor(targetUri, "r")?.statSize ?: -1L
            } catch (_: Exception) { -1L }

            // Copy GPX to internal
            val inFile = File(filesDir, "input.gpx")
            contentResolver.openInputStream(gpxUri)!!.use { input ->
                inFile.outputStream().use { output -> input.copyTo(output) }
            }

            // Copy target MS to internal output.ms (Python will append into it)
            val outFile = File(filesDir, "output.ms")
            contentResolver.openInputStream(targetUri)!!.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }

            txtResult.text = "APPEND\n2/4 Appending…"

            val py = Python.getInstance()
            val bridge = py.getModule("bridge")
            val pyResult = bridge.callAttr(
                "convert",
                inFile.absolutePath,
                outFile.absolutePath,
                null,   // auto
                "none", // line_mode
                "",     // style
                true    // append
            ).toString()

            txtResult.text = "APPEND\n3/4 Writing target…"

            val bytes = outFile.readBytes()
            contentResolver.openOutputStream(targetUri, "wt")?.use { out ->
                out.write(bytes)
            } ?: throw IllegalStateException("Could not open output stream for target.")

            val afterSize = try {
                contentResolver.openFileDescriptor(targetUri, "r")?.statSize ?: -1L
            } catch (_: Exception) { -1L }

            // Update "result" to be the appended MS as well (so Save/Share/Open work with it)
            outputBytes = bytes
            hasResult = true
            suggestedOutName = targetName // nice default for Save
            val sf = File(filesDir, "appended_$targetName")
            if (sf.exists()) sf.delete()
            outFile.copyTo(sf, overwrite = true)
            shareFile = sf

            updateButtons()

            txtResult.text =
                "$pyResult\n\nDONE Appended into:\n$targetName\nSize: $beforeSize → $afterSize bytes"

        } catch (e: Exception) {
            txtResult.text = "APPEND FAILED ❌\n${e.message}\n\n$e"
        }
    }

    // --- UI helpers ---

    private fun resetResultUi() {
        outputBytes = null
        shareFile = null
        hasResult = false

        btnAppend.isEnabled = false
        btnSave.isEnabled = false
        btnShare.isEnabled = false
        btnOpenGuru.isEnabled = false
    }

    private fun updateButtons() {
        val hasInput = pickedUri != null
        btnConvert.isEnabled = hasInput

        // All result actions are disabled until Convert (or successful Append) produces bytes.
        btnAppend.isEnabled = hasResult
        btnSave.isEnabled = hasResult
        btnShare.isEnabled = hasResult
        btnOpenGuru.isEnabled = hasResult
    }

    // --- Play Store helpers ---

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(
                    pkg,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun openPlayStore(appId: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appId")))
            return
        } catch (_: ActivityNotFoundException) {
            // fallback below
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$appId")))
    }

    // --- File helpers ---

    private fun getDisplayName(uri: Uri): String {
        val cursor = contentResolver.query(uri, null, null, null, null) ?: return uri.toString()
        cursor.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && it.moveToFirst()) {
                return it.getString(nameIndex) ?: uri.toString()
            }
        }
        return uri.toString()
    }

    private fun makeSuggestedName(inputName: String, outExt: String): String {
        val base = inputName.substringBeforeLast('.', inputName)
        return "$base.$outExt"
    }
}