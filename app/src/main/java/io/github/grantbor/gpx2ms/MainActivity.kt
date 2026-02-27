package io.github.grantbor.gpx2ms

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private enum class InputKind { NONE, GPX, MS }
    private var inputKind: InputKind = InputKind.NONE

    private var pickedUri: Uri? = null

    private var currentGpxFile: File? = null

    // Результат конвертации
    private var outputBytes: ByteArray? = null
    private var outputExtension: String = "ms"
    private var hasResult: Boolean = false
    private var suggestedOutName: String = "converted.ms"

    // Для Save/Share/Open
    private var lastExportUri: Uri? = null
    private var lastExportMime: String = "application/octet-stream"
    private var lastExportName: String = "output"

    // UI элементы
    private lateinit var btnPick: Button
    private lateinit var btnConvert: Button
    private lateinit var btnAppend: Button
    private lateinit var btnSave: Button
    private lateinit var btnShare: Button
    private lateinit var btnOpenGuru: Button
    private lateinit var btnPickColor: MaterialCardView
    private lateinit var colorSwatch: View
    private lateinit var txtPicked: TextView
    private lateinit var txtResult: TextView

    // Цвет
    private val PREFS_NAME = "gpx2ms_prefs"
    private val PREF_COLOR_HEX = "ms_color_hex"
    private var msColorHex: String = "#00FFFF"

    // Выбор входного файла
    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (uri == null) {
            txtPicked.setText(R.string.selection_cancelled)
            pickedUri = null
            inputKind = InputKind.NONE
            resetResult()
            return@registerForActivityResult
        }

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { }

        handlePickedUri(uri)
    }

    // Выбор target .ms для Append
    private val pickAppendTarget = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            txtResult.text = "Append отменён"
            return@registerForActivityResult
        }

        val targetName = getDisplayName(uri)
        if (!targetName.lowercase().endsWith(".ms")) {
            txtResult.text = "Нужно выбрать .ms файл"
            return@registerForActivityResult
        }

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { }

        doAppend(uri, targetName)
    }

    // Сохранение файла (Create document)
    private val saveCreateLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
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
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }

            contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                out.write(bytes)
            }

            lastExportUri = uri
            lastExportName = "converted.${outputExtension}"
            lastExportMime = guessMime(outputExtension)

            updateButtons()
            txtResult.setText(R.string.save_done)
        } catch (e: Exception) {
            txtResult.text = "Ошибка сохранения:\n${e.message}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Загружаем сохраненный цвет
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        msColorHex = prefs.getString(PREF_COLOR_HEX, "#00FFFF") ?: "#00FFFF"

        // Инициализация UI
        btnPick = findViewById(R.id.btnPick)
        btnConvert = findViewById(R.id.btnConvert)
        btnAppend = findViewById(R.id.btnAppend)
        btnSave = findViewById(R.id.btnSave)
        btnShare = findViewById(R.id.btnShare)
        btnOpenGuru = findViewById(R.id.btnOpenGuru)
        btnPickColor = findViewById(R.id.btnPickColor)
        colorSwatch = findViewById(R.id.viewColorSwatch)
        txtPicked = findViewById(R.id.txtPicked)
        txtResult = findViewById(R.id.txtResult)

        applyColorToSwatch(msColorHex)
        txtPicked.setText(R.string.file_not_selected)
        txtResult.setText(R.string.result_placeholder)

        resetResult()
        updateButtons()

        // Обработчики кнопок
        btnPick.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            pickFile.launch(intent)
        }

        btnPickColor.setOnClickListener {
            showColorPresetDialog()
        }

        btnConvert.setOnClickListener {
            doConvert()
        }

        btnAppend.setOnClickListener {
            if (!hasResult || outputBytes == null) {
                txtResult.text = "Сначала выполните конвертацию"
                return@setOnClickListener
            }
            if (inputKind != InputKind.GPX) {
                txtResult.text = "Добавление работает только с GPX файлами"
                return@setOnClickListener
            }
            txtResult.text = "Выберите целевой .ms файл..."
            pickAppendTarget.launch(arrayOf("*/*"))
        }

        btnSave.setOnClickListener {
            if (!hasResult || outputBytes == null) {
                txtResult.setText(R.string.nothing_to_save)
                return@setOnClickListener
            }

            val mime = guessMime(outputExtension)
            val createIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                // ИСПОЛЬЗУЕМ suggestedOutName ВМЕСТО "converted.$outputExtension"
                putExtra(Intent.EXTRA_TITLE, suggestedOutName)
                putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            }
            saveCreateLauncher.launch(createIntent)
        }

        btnShare.setOnClickListener {
            val uri = lastExportUri
            if (!hasResult || uri == null) {
                txtResult.text = "Сначала сохраните файл (Сохранить)"
                return@setOnClickListener
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = lastExportMime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            shareIntent.clipData = ClipData.newUri(contentResolver, lastExportName, uri)

            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_title)))
        }

        btnOpenGuru.setOnClickListener {
            val uri = lastExportUri
            if (!hasResult || uri == null) {
                txtResult.text = "Сначала сохраните файл (Save)"
                return@setOnClickListener
            }

            // Список возможных package name для Guru Maps
            val possibleGuruPackages = listOf(
                "com.bodunov.galileo",      // бесплатная версия
                "com.bodunov.GalileoPro",    // платная версия
                "com.gurumaps.android",      // альтернативное название
                "ru.gurumaps.android",       // возможно для RuStore
                "com.gurumaps.galileo"       // еще вариант
            )

            // Ищем установленное приложение
            val targetPkg = possibleGuruPackages.firstOrNull { pkg ->
                isPackageInstalled(pkg)
            }

            if (targetPkg == null) {
                // Если не нашли - показываем диалог
                AlertDialog.Builder(this)
                    .setTitle("Guru Maps не найден")
                    .setMessage("Приложение Guru Maps не установлено. Хотите перейти в магазин для установки?")
                    .setPositiveButton("Перейти в Play Market") { _, _ ->
                        openPlayStore("com.bodunov.galileo")  // открываем бесплатную версию
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
                return@setOnClickListener
            }

            // Пробуем открыть файл в Guru Maps
            try {
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, lastExportMime)
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(viewIntent)
            } catch (e: Exception) {
                txtResult.text = "Не удалось открыть в Guru Maps: ${e.message}"
            }
        }
    }

    /**
     * Обработка выбранного URI
     */
    private fun handlePickedUri(uri: Uri) {
        val displayName = getDisplayName(uri)
        inputKind = when {
            displayName.lowercase().endsWith(".gpx") -> InputKind.GPX
            displayName.lowercase().endsWith(".ms") -> InputKind.MS
            else -> InputKind.NONE
        }

        if (inputKind == InputKind.NONE) {
            txtPicked.text = "Выбран неподдерживаемый файл:\n$displayName"
            pickedUri = null
            resetResult()
            return
        }

        pickedUri = uri
        txtPicked.text = "Выбран: $displayName"
        resetResult()
    }

    /**
     * Основная функция конвертации
     */
    private fun doConvert() {
        val uri = pickedUri ?: run {
            txtResult.setText(R.string.no_file_selected)
            return
        }

        try {
            txtResult.text = "Конвертация...\nКопируем файл"

            val inName = getDisplayName(uri)
            val inExt = when {
                inName.lowercase().endsWith(".gpx") -> "gpx"
                inName.lowercase().endsWith(".ms") -> "ms"
                else -> {
                    txtResult.setText(R.string.unsupported_file)
                    return
                }
            }

            // Копируем входной файл в кэш приложения
            val safeStem = inName.substringBeforeLast('.')
                .ifEmpty { "input" }

            val inFile = File(cacheDir, "$safeStem.$inExt")
            contentResolver.openInputStream(uri)!!.use { input ->
                FileOutputStream(inFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Если это GPX файл - сохраняем его для Append
            if (inExt == "gpx") {
                currentGpxFile = inFile
            }

            // Определяем выходное расширение
            val outExt = if (inExt == "gpx") "ms" else "gpx"
            val outFile = File(cacheDir, "output.$outExt")

            // Сохраняем исходное имя файла без расширения для предложения при сохранении
            val sourceNameWithoutExt = inName.substringBeforeLast('.', inName)
            val suggestedFileName = "$sourceNameWithoutExt.$outExt"

            txtResult.text = "Конвертация...\nОбрабатываем"

            val result = GpxMsConverter.convert(
                inputFile = inFile,
                outputFile = outFile,
                to = null,
                lineMode = "none",
                style = if (outExt == "ms") msColorHex else "",
                append = false
            )

            if (result.outputBytes == null) {
                txtResult.text = result.message
                return
            }

            // Сохраняем результат
            outputBytes = result.outputBytes
            outputExtension = result.outputExtension
            hasResult = true

            // СОХРАНЯЕМ ПРЕДЛОЖЕННОЕ ИМЯ ДЛЯ ИСПОЛЬЗОВАНИЯ В SAVE
            suggestedOutName = suggestedFileName

            // Обнуляем lastExportUri - нужно будет Save
            lastExportUri = null

            updateButtons()
            txtResult.text = "${result.message}\n\nГотово. Используйте Save для сохранения."

        } catch (e: Exception) {
            txtResult.text = "Ошибка:\n${e.message}"
        }
    }

    /**
     * Append: добавление к существующему MS
     */

    private fun doAppend(appendUri: Uri, targetName: String) {
        // Объявляем переменные в начале функции
        val targetFile: File

        try {
            txtResult.text = "Append...\nКопируем target файл"

            // Копируем выбранный MS во временный файл
            targetFile = File(cacheDir, "target_append.ms")
            contentResolver.openInputStream(appendUri)!!.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            txtResult.text = "Append...\nTarget файл скопирован: ${targetFile.exists()}, путь: ${targetFile.absolutePath}"

            // используем сохраненный GPX файл
            val gpxFile = currentGpxFile ?: run {
                txtResult.text = "Сначала выполните Convert с GPX файлом"
                return
            }

            txtResult.text = "Append...\nДобавляем данные"

            // ВЫЗЫВАЕМ КОНВЕРТЕР В РЕЖИМЕ APPEND
            val result = GpxMsConverter.convert(
                inputFile = gpxFile,
                outputFile = targetFile,
                to = "ms",
                lineMode = "none",
                style = "",
                append = true
            )

            // Добавляем логи
            android.util.Log.d("GPX2MS", "Append result success: ${result.outputBytes != null}")
            if (result.outputBytes != null) {
                android.util.Log.d("GPX2MS", "Append result size: ${result.outputBytes!!.size}")
                android.util.Log.d("GPX2MS", "Writing back to URI: $appendUri")
            } else {
                android.util.Log.d("GPX2MS", "Append failed: ${result.message}")
            }

            if (result.outputBytes == null) {
                txtResult.text = result.message
                return
            }

            // Сохраняем результат
            outputBytes = result.outputBytes
            outputExtension = "ms"
            hasResult = true

            // Записываем обратно в выбранный URI
            contentResolver.openOutputStream(appendUri, "wt")!!.use { out ->
                out.write(result.outputBytes)
            }

            // Сообщаем системе, что файл изменился
            contentResolver.notifyChange(appendUri, null)

            lastExportUri = appendUri
            lastExportName = targetName
            lastExportMime = "application/octet-stream"

            updateButtons()
            txtResult.text = "Append успешно выполнен"

        } catch (e: Exception) {
            txtResult.text = "Ошибка Append:\n${e.message}"
        }
    }

    /**
     * Сброс результата при выборе нового файла
     */
    private fun resetResult() {
        outputBytes = null
        hasResult = false
        lastExportUri = null
        currentGpxFile = null
        updateButtons()
    }

    /**
     * Обновление состояния кнопок
     */
    private fun updateButtons() {
        btnConvert.isEnabled = pickedUri != null
        btnAppend.isEnabled = hasResult && inputKind == InputKind.GPX
        btnSave.isEnabled = hasResult
        btnShare.isEnabled = hasResult && lastExportUri != null
        btnOpenGuru.isEnabled = hasResult && lastExportUri != null
    }

    /**
     * Получение имени файла из URI
     */
    private fun getDisplayName(uri: Uri): String {
        var name = "file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex) ?: "file"
            }
        }
        return name
    }

    /**
     * Определение MIME по расширению
     */
    private fun guessMime(ext: String): String {
        return when (ext.lowercase()) {
            "gpx" -> "application/gpx+xml"
            "ms" -> "application/octet-stream"  // Меняем на octet-stream
            else -> "application/octet-stream"
        }
    }

    /**
     * Применить цвет к кружку
     */
    private fun applyColorToSwatch(color: String) {
        try {
            val androidColor = android.graphics.Color.parseColor(color)
            colorSwatch.setBackgroundColor(androidColor)
        } catch (_: Exception) {
            colorSwatch.setBackgroundColor(android.graphics.Color.CYAN)
        }
    }

    /**
     * Диалог выбора цвета
     */
    private fun showColorPresetDialog() {
        val colors = arrayOf("#00FFFF", "#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#F57C00")
        val names = arrayOf("Голубой", "Красный", "Зеленый", "Синий", "Желтенький", "Рыжий")

        AlertDialog.Builder(this)
            .setTitle("Выберите цвет")
            .setItems(names) { _, which ->
                msColorHex = colors[which]
                applyColorToSwatch(msColorHex)
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_COLOR_HEX, msColorHex)
                    .apply()
            }
            .show()
    }

    /**
     * Проверка, установлено ли приложение
     */
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Открыть Play Market
     */
    private fun openPlayStore(packageName: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
        }
    }

    /**
     * Сброс UI результата
     */
    private fun resetResultUi() {
        outputBytes = null
        hasResult = false
        lastExportUri = null
    }
}