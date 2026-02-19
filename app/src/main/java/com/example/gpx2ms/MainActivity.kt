package com.example.gpx2ms  // оставь свой package

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import android.content.Intent
import androidx.core.content.FileProvider

class MainActivity : AppCompatActivity() {

    private var pickedUri: Uri? = null

    private var outputBytes: ByteArray? = null
    private var suggestedOutName: String = "converted.ms"

    private var shareFile: File? = null

    private var outputFile: File? = null

    private lateinit var btnShare: Button

    private lateinit var btnSave: Button
    private lateinit var txtResult: TextView
    private lateinit var txtPicked: TextView



    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->


        if (uri != null) {

            val name = getDisplayName(uri).lowercase()

            if (!name.endsWith(".gpx") && !name.endsWith(".ms")) {
                txtPicked.setText(R.string.unsupported_file)
                pickedUri = null
                return@registerForActivityResult
            }

            // сброс предыдущего результата
            outputBytes = null
            outputFile = null
            btnSave.isEnabled = false
            btnShare.isEnabled = false
            txtResult.setText(R.string.result_placeholder)
            shareFile = null
            btnShare.isEnabled = false


            pickedUri = uri

            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            txtPicked.text = getString(R.string.file_selected, name)

        } else {
            txtPicked.setText(R.string.selection_cancelled)
        }
    }

    private val saveFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->

        if (uri == null) {
            txtResult.setText(R.string.save_cancelled)
            return@registerForActivityResult
        }

        val bytes = outputBytes
        if (bytes == null) {
            txtResult.setText(R.string.nothing_to_save)
            return@registerForActivityResult
        }

        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
            }
            txtResult.setText(R.string.save_done)
        } catch (e: Exception) {
            txtResult.text = "ERROR saving:\n${e.message}\n\n$e"
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Python init
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        val btnPick = findViewById<Button>(R.id.btnPick)
        txtPicked = findViewById(R.id.txtPicked)
        txtResult = findViewById(R.id.txtResult)

        btnPick.setOnClickListener {
            // Можно фильтровать типы, но у GPX/MS часто content-type неопределён,
            // поэтому проще: */*
            pickFile.launch(arrayOf("*/*"))
        }


        val btnConvert = findViewById<Button>(R.id.btnConvert)

        btnSave = findViewById(R.id.btnSave)
        btnSave.isEnabled = false

        btnShare = findViewById(R.id.btnShare)
        btnShare.isEnabled = false

        btnConvert.setOnClickListener {
            val uri = pickedUri
            if (uri == null) {
                txtResult.setText(R.string.no_file_selected)
                return@setOnClickListener
            }

            try {
                txtResult.setText(R.string.convert_started)

                // 1) Определяем имя и расширение
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

                // 2) Копируем выбранный файл во внутреннее хранилище
                val inFile = File(filesDir, "input.$inExt")
                contentResolver.openInputStream(uri)!!.use { input ->
                    inFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // 3) Определяем расширение выхода (auto: gpx<->ms)
                val outExt = if (inExt == "gpx") "ms" else "gpx"
                val outFile = File(filesDir, "output.$outExt")

                // 4) Запуск Python-конвертера
                val py = Python.getInstance()
                val bridge = py.getModule("bridge")


                // toArg: null = auto
                val result = bridge.callAttr(
                    "convert",
                    inFile.absolutePath,
                    outFile.absolutePath,
                    null,      // to: auto
                    "none",    // line_mode
                    ""         // style
                ).toString()

                // 5) Показать результат + путь + размер
                val size = outFile.length()
                outputBytes = outFile.readBytes()
                suggestedOutName = makeSuggestedName(getDisplayName(uri), outExt)
                btnSave.isEnabled = true

                // файл для шаринга с "красивым" именем
                val sf = File(filesDir, suggestedOutName)

                // перезаписываем (если был)
                if (sf.exists()) sf.delete()

                // копируем содержимое результата в файл с нужным именем
                outFile.copyTo(sf, overwrite = true)

                shareFile = sf
                btnShare.isEnabled = true


                txtResult.text = "$result\n\nSaved internally as:\n${outFile.absolutePath}\nBytes: $size"

            } catch (e: Exception) {
                txtResult.text = "ERROR:\n${e.message}\n\n$e"
            }
        }

        btnSave.setOnClickListener {
            if (outputBytes == null) {
                txtResult.setText(R.string.nothing_to_save)
            } else {
                saveFile.launch(suggestedOutName)
            }
        }

        btnShare.setOnClickListener {
            val file = shareFile
            if (file == null || !file.exists()) {
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





        // На старте
        txtPicked.setText(R.string.file_not_selected)
    }

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
        // input.gpx -> input.ms, track.ms -> track.gpx
        val base = inputName.substringBeforeLast('.', inputName)
        return "$base.$outExt"
    }


}
