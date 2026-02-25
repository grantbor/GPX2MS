package io.github.grantbor.gpx2ms

import android.content.ClipData
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.card.MaterialCardView
import java.io.File

class MainActivity : AppCompatActivity() {

    private enum class InputKind { NONE, GPX, MS }
    private var inputKind: InputKind = InputKind.NONE

    private var pickedUri: Uri? = null

    // Result bytes kept in memory (for Save)
    private var outputBytes: ByteArray? = null
    private var suggestedOutName: String = "converted.ms"
    private var hasResult: Boolean = false

    // ✅ The single source of truth for Share/Open:
    // - set after Save (created document URI)
    // - set after Append (target MS URI)
    private var lastExportUri: Uri? = null
    private var lastExportMime: String = "application/octet-stream"
    private var lastExportName: String = "output"

    // ✅ For "revoke after return"
    private var pendingRevokeUri: Uri? = null
    private var pendingRevokePkgs: MutableSet<String> = mutableSetOf()

    private lateinit var btnConvert: Button
    private lateinit var btnAppend: Button
    private lateinit var btnSave: Button
    private lateinit var btnShare: Button
    private lateinit var btnOpenGuru: Button

    // Color picker overlay (circle)
    private lateinit var btnPickColor: MaterialCardView
    private lateinit var colorSwatch: View

    private lateinit var txtPicked: TextView
    private lateinit var txtResult: TextView

    // ---- Color state ----
    private val PREFS_NAME = "gpx2ms_prefs"
    private val PREF_COLOR_HEX = "ms_color_hex"
    private var msColorHex: String = "#00FFFF" // default cyan

    // --- Pick input GPX/MS ---
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            if (uri == null) {
                txtPicked.setText(R.string.selection_cancelled)
                pickedUri = null
                inputKind = InputKind.NONE
                resetResultUi()
                updateButtons()
                return@registerForActivityResult
            }

            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            handlePickedUri(uri)
        }

    // --- Append: user picks target .ms here (existing file) ---
    private val pickAppendTarget =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
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
            } catch (_: Exception) {
                /* ok */
            }

            doAppendIntoTarget(uri, targetName)
        }

    // --- Save: ONLY "Create document" so user can always set file name ---
    private val saveCreateLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
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

            try {
                // Persistable permission helps later Share/Open (some providers support it)
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) { /* ok */ }

                contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                    out.write(bytes)
                }

                // ✅ After Save, export URI becomes the truth for Share/Open
                lastExportUri = uri
                lastExportName = suggestedOutName
                lastExportMime = guessMimeByName(suggestedOutName)

                updateButtons()
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

        // Load saved color
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        msColorHex = prefs.getString(PREF_COLOR_HEX, "#00FFFF") ?: "#00FFFF"

        val btnPick = findViewById<Button>(R.id.btnPick)
        btnConvert = findViewById(R.id.btnConvert)
        btnAppend = findViewById(R.id.btnAppend)
        btnSave = findViewById(R.id.btnSave)
        btnShare = findViewById(R.id.btnShare)
        btnOpenGuru = findViewById(R.id.btnOpenGuru)

        btnPickColor = findViewById(R.id.btnPickColor)
        colorSwatch = findViewById(R.id.viewColorSwatch)
        applyColorToSwatch(msColorHex)

        txtPicked = findViewById(R.id.txtPicked)
        txtResult = findViewById(R.id.txtResult)

        txtPicked.setText(R.string.file_not_selected)
        txtResult.setText(R.string.result_placeholder)

        resetResultUi()
        updateButtons()

        btnPick.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            pickFile.launch(intent)
        }

        // Color picker (simple presets)
        btnPickColor.setOnClickListener {
            showColorPresetDialog()
        }

        // Convert: generate result into internal output file and memory bytes
        // (does NOT export to filesystem by itself)
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

                // Copy input to internal cache for Python
                val originalStem = inName.substringBeforeLast('.', inName)
                val safeStem = originalStem
                    .replace('\u0000', '_')
                    .replace('/', '_')
                    .replace('\\', '_')
                    .trim()
                    .ifEmpty { "input" }

                val inFile = File(cacheDir, "$safeStem.$inExt")
                contentResolver.openInputStream(uri)!!.use { input ->
                    inFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val outExt = if (inExt == "gpx") "ms" else "gpx"
                val outFile = File(cacheDir, "output.$outExt")

                txtResult.text = "CONVERT\n2/2 Running…"

                val py = Python.getInstance()
                val bridge = py.getModule("bridge")

                // ✅ One color for points+track:
                // We pass HEX as "style". converter3.py will expand it to full style.
                val styleOrEmpty = if (outExt == "ms") msColorHex else ""

                val pyResult = bridge.callAttr(
                    "convert",
                    inFile.absolutePath,
                    outFile.absolutePath,
                    null,   // auto
                    "none", // line_mode
                    styleOrEmpty,
                    false   // append
                ).toString()

                outputBytes = outFile.readBytes()
                hasResult = true
                suggestedOutName = makeSuggestedName(inName, outExt)

                // Convert does NOT define lastExportUri (no filesystem export yet)
                lastExportUri = null
                lastExportName = "output"
                lastExportMime = "application/octet-stream"

                updateButtons()

                txtResult.text =
                    "$pyResult\n\nГотово.\nТеперь можно: Append (в существующий .ms) / Save (создать файл) / Share / Open in Guru."
            } catch (e: Exception) {
                txtResult.text = "CONVERT FAILED ❌\n${e.message}\n\n$e"
            }
        }

        // Append: pick existing MS target and append GPX into it (overwrite target)
        btnAppend.setOnClickListener {
            if (!hasResult || outputBytes == null) {
                txtResult.text = "No result yet.\nPress Convert first."
                return@setOnClickListener
            }

            if (inputKind != InputKind.GPX) {
                txtResult.text = "Append works with GPX input.\nPick a .gpx file first."
                return@setOnClickListener
            }

            txtResult.text = "Pick target .ms to append into…"
            pickAppendTarget.launch(arrayOf("*/*"))
        }

        // Save: ALWAYS CreateDocument (so user can set file name)
        btnSave.setOnClickListener {
            if (!hasResult || outputBytes == null) {
                txtResult.setText(R.string.nothing_to_save)
                return@setOnClickListener
            }

            val mime = guessMimeByName(suggestedOutName)
            val createIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, suggestedOutName)
                putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            }

            saveCreateLauncher.launch(createIntent)
        }

        // Share: share the exported filesystem document (Save/Append result), not internal cache
        btnShare.setOnClickListener {
            val uri = lastExportUri
            if (!hasResult || uri == null) {
                txtResult.text = "Nothing exported yet.\nUse Save or Append first."
                return@setOnClickListener
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = lastExportMime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // ✅ Strengthen: ClipData + explicit grants to all potential recipients
            shareIntent.clipData = ClipData.newUri(contentResolver, lastExportName, uri)
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val resInfoList = packageManager.queryIntentActivities(shareIntent, 0)
            for (resolveInfo in resInfoList) {
                val pkg = resolveInfo.activityInfo.packageName
                try {
                    grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    pendingRevokePkgs.add(pkg)
                } catch (_: Exception) {
                    /* ok */
                }
            }
            pendingRevokeUri = uri

            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_title)))
        }

        // Open in Guru: open the exported filesystem document (Save/Append result)
        btnOpenGuru.setOnClickListener {
            val uri = lastExportUri
            if (!hasResult || uri == null) {
                txtResult.text = "Nothing exported yet.\nUse Save or Append first."
                return@setOnClickListener
            }

            val mime = lastExportMime

            val guruFree = "com.bodunov.galileo"
            val guruPro = "com.bodunov.GalileoPro"
            val targetPkg: String? = when {
                isPackageInstalled(guruFree) -> guruFree
                isPackageInstalled(guruPro) -> guruPro
                else -> null
            }

            if (targetPkg == null) {
                txtResult.text = "Guru Maps is not installed.\nOpening Google Play…"
                openPlayStore(guruFree)
                return@setOnClickListener
            }

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                setPackage(targetPkg)
            }

            // ✅ Strengthen: explicit grant + ClipData
            try {
                grantUriPermission(targetPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                pendingRevokeUri = uri
                pendingRevokePkgs.add(targetPkg)
            } catch (_: Exception) {
                /* ok */
            }

            viewIntent.clipData = ClipData.newUri(contentResolver, lastExportName, uri)
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            try {
                startActivity(viewIntent)
            } catch (_: Exception) {
                // Fallback: open with any capable viewer (also strengthened)
                val fallback = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(contentResolver, lastExportName, uri)
                }
                startActivity(Intent.createChooser(fallback, "Open with…"))
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Revoke temporary URI grants after returning to our app
        val uri = pendingRevokeUri ?: return
        if (pendingRevokePkgs.isEmpty()) return

        try {
            revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            /* ok */
        }

        pendingRevokeUri = null
        pendingRevokePkgs.clear()
    }

    // --- Append implementation (GPX->MS append into selected target) ---
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
            } catch (_: Exception) {
                -1L
            }

            // Copy GPX to internal cache
            val inFile = File(cacheDir, "input.gpx")
            contentResolver.openInputStream(gpxUri)!!.use { input ->
                inFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Copy target MS to internal output.ms (Python will append into it)
            val outFile = File(cacheDir, "output.ms")
            contentResolver.openInputStream(targetUri)!!.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            txtResult.text = "APPEND\n2/4 Appending…"

            val py = Python.getInstance()
            val bridge = py.getModule("bridge")

            // ✅ IMPORTANT:
            // Append should NOT change style of existing MS by default.
            // We pass empty style override, so converter keeps existing style.
            val pyResult = bridge.callAttr(
                "convert",
                inFile.absolutePath,
                outFile.absolutePath,
                null,   // auto
                "none", // line_mode
                "",     // style override: keep existing
                true    // append
            ).toString()

            txtResult.text = "APPEND\n3/4 Writing target…"

            val bytes = outFile.readBytes()
            contentResolver.openOutputStream(targetUri, "wt")?.use { out ->
                out.write(bytes)
            } ?: throw IllegalStateException("Could not open output stream for target.")

            val afterSize = try {
                contentResolver.openFileDescriptor(targetUri, "r")?.statSize ?: -1L
            } catch (_: Exception) {
                -1L
            }

            // Update in-memory result (so Save can still work immediately if needed)
            outputBytes = bytes
            hasResult = true
            suggestedOutName = targetName

            // ✅ After Append, export URI becomes the truth for Share/Open
            lastExportUri = targetUri
            lastExportName = targetName
            lastExportMime = "application/octet-stream"

            updateButtons()

            txtResult.text =
                "$pyResult\n\nDONE Appended into:\n$targetName\nSize: $beforeSize → $afterSize bytes"
        } catch (e: Exception) {
            txtResult.text = "APPEND FAILED ❌\n${e.message}\n\n$e"
        }
    }

    // --- Color dialog/prefs ---
    private fun showColorPresetDialog() {
        val presets: List<Pair<String, String>> = listOf(
            "Циан (#00FFFF)" to "#00FFFF",
            "Зеленый (#00FF00)" to "#00FF00",
            "Красный (#FF0000)" to "#FF0000",
            "Желтенький (#FFFF00)" to "#FFFF00",
            "Маджента (#FF00FF)" to "#FF00FF",
            "Белый (#FFFFFF)" to "#FFFFFF",
            "Рыжий (#FF8800)" to "#FF8800",
        )
        val labels = presets.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Objects color")
            .setItems(labels) { _, which ->
                val hex = presets[which].second
                msColorHex = hex
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_COLOR_HEX, msColorHex)
                    .apply()
                applyColorToSwatch(msColorHex)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyColorToSwatch(hex: String) {
        // Parse #RRGGBB / #AARRGGBB
        val c = try {
            android.graphics.Color.parseColor(hex)
        } catch (_: Exception) {
            android.graphics.Color.parseColor("#00FFFF")
        }
        colorSwatch.setBackgroundColor(c)
    }

    // --- UI helpers ---
    private fun resetResultUi() {
        outputBytes = null
        hasResult = false

        lastExportUri = null
        lastExportName = "output"
        lastExportMime = "application/octet-stream"

        pendingRevokeUri = null
        pendingRevokePkgs.clear()
    }

    private fun updateButtons() {
        val hasInput = pickedUri != null
        btnConvert.isEnabled = hasInput

        // Convert/Append/Save depend on having a conversion result (bytes)
        btnSave.isEnabled = hasResult

        // Append only makes sense for GPX input
        btnAppend.isEnabled = hasResult && inputKind == InputKind.GPX

        // Share/Open depend on having an exported filesystem URI (Save or Append done)
        val exported = hasResult && lastExportUri != null
        btnShare.isEnabled = exported
        btnOpenGuru.isEnabled = exported
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
        startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$appId")
            )
        )
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

    private fun handlePickedUri(uri: Uri) {
        val name = getDisplayName(uri)
        val nameLower = name.lowercase()

        if (!nameLower.endsWith(".gpx") && !nameLower.endsWith(".ms")) {
            txtPicked.setText(R.string.unsupported_file)
            pickedUri = null
            inputKind = InputKind.NONE
            resetResultUi()
            updateButtons()
            return
        }

        inputKind = when {
            nameLower.endsWith(".gpx") -> InputKind.GPX
            nameLower.endsWith(".ms") -> InputKind.MS
            else -> InputKind.NONE
        }

        pickedUri = uri
        txtPicked.text = getString(R.string.file_selected, name)

        resetResultUi()
        updateButtons()
    }

    private fun makeSuggestedName(inputName: String, outExt: String): String {
        val base = inputName.substringBeforeLast('.', inputName)
        return "$base.$outExt"
    }

    private fun guessMimeByName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".gpx") -> "application/gpx+xml"
            // MS is a custom XML-ish format; many apps accept octet-stream
            lower.endsWith(".ms") -> "application/octet-stream"
            else -> "application/octet-stream"
        }
    }
}