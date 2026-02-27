package io.github.grantbor.gpx2ms

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Ядро конвертера GPX ↔ MS.
 * Объединяет парсеры и билдеры, реализует основную логику конвертации.
 */
object GpxMsConverter {

    // Регулярное выражение для проверки HEX-цвета
    private val HEX_COLOR_REGEX = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    // Стандартный стиль (как в Python: cyan)
    private val DEFAULT_STYLE = makeStyle("#00FFFF")

    /**
     * Главная функция конвертации (соответствует convert_file из Python)
     * @param inputFile Входной файл
     * @param outputFile Выходной файл (будет создан или перезаписан)
     * @param to Направление: "gpx", "ms" или null (автоопределение)
     * @param lineMode Режим линий: "none", "orth", "snake", "both"
     * @param style Стиль: HEX-цвет, пустая строка или MapCSS
     * @param append Режим добавления (только GPX → MS)
     * @return ConversionResult с сообщением и байтами результата
     */
    fun convert(
        inputFile: File,
        outputFile: File,
        to: String? = null,
        lineMode: String = "none",
        style: String = "",
        append: Boolean = false
    ): ConversionResult {
        return try {
            // 1. Проверяем входной файл
            if (!inputFile.exists()) {
                return errorResult("Входной файл не найден: ${inputFile.path}")
            }

            // 2. Определяем исходный формат
            val srcFormat = detectFormat(inputFile)

            // 3. Определяем целевой формат
            val dstFormat = when {
                to != null -> to.lowercase()
                srcFormat == "gpx" -> "ms"
                else -> "gpx"
            }

            // 4. Валидация: append работает только GPX→MS
            if (append && (srcFormat != "gpx" || dstFormat != "ms")) {
                return errorResult("Append режим работает только для GPX → MS")
            }

            // 5. Если append и файл не существует - ошибка
            if (append && !outputFile.exists()) {
                // Добавим больше информации для отладки
                return errorResult("Для append выходной MS файл должен существовать. Путь: ${outputFile.absolutePath}")
            }

            // 6. Конвертация в зависимости от направления
            val resultBytes = when (dstFormat) {
                "gpx" -> convertToGpx(inputFile, srcFormat)
                "ms" -> convertToMs(inputFile, srcFormat, lineMode, style, append, outputFile)
                else -> return errorResult("Неизвестный целевой формат: $dstFormat")
            }

            // 7. Если есть результат - сохраняем в файл
            if (resultBytes != null) {
                outputFile.parentFile?.mkdirs()
                FileOutputStream(outputFile).use { it.write(resultBytes) }

                val message = if (append) "Append успешно выполнен" else "Конвертация успешна"
                ConversionResult(message, resultBytes, dstFormat)
            } else {
                errorResult("Ошибка при конвертации: результат пустой")
            }

        } catch (e: Exception) {
            errorResult("Ошибка: ${e.message}\n${e.stackTraceToString()}")
        }
    }

    /**
     * Конвертация в GPX (из MS)
     */
    private fun convertToGpx(
        inputFile: File,
        srcFormat: String
    ): ByteArray? {
        if (srcFormat != "ms") {
            throw Exception("В GPX можно конвертировать только из MS")
        }

        // Парсим MS файл
        FileInputStream(inputFile).use { inputStream ->
            val data = MsParser.parse(inputStream, inputFile.nameWithoutExtension)

            // Создаем GPX в байтовый массив
            val outputStream = ByteArrayOutputStream()
            GpxBuilder.build(data, outputStream)

            return outputStream.toByteArray()
        }
    }

    /**
     * Конвертация в MS (из GPX)
     */
    private fun convertToMs(
        inputFile: File,
        srcFormat: String,
        lineMode: String,
        style: String,
        append: Boolean,
        outputFile: File
    ): ByteArray? {
        if (srcFormat != "gpx") {
            throw Exception("В MS можно конвертировать только из GPX")
        }

        // Парсим GPX файл
        val gpxData = FileInputStream(inputFile).use { inputStream ->
            val data = GpxParser.parse(inputStream, inputFile.nameWithoutExtension)
            // ВРЕМЕННО: выведем название
            android.util.Log.d("GPX2MS", "Название после парсинга: ${data.title}")
            data
        }

        // Применяем line-mode (пока только заглушка, как в Python)
        val processedData = processLineMode(gpxData, lineMode)

        // Определяем стиль для выходного MS
        val finalStyle = when {
            // Для append с пустым style - оставляем существующий стиль
            append && style.isEmpty() -> {
                if (outputFile.exists()) {
                    FileInputStream(outputFile).use {
                        val existingData = MsParser.parse(it, outputFile.nameWithoutExtension)
                        existingData.styleText ?: DEFAULT_STYLE
                    }
                } else {
                    DEFAULT_STYLE
                }
            }
            // HEX-цвет -> преобразуем в полный стиль
            HEX_COLOR_REGEX.matches(style) -> makeStyle(style)
            // Пустая строка для обычной конвертации -> DEFAULT_STYLE
            style.isEmpty() -> DEFAULT_STYLE
            // Иначе считаем, что это готовый MapCSS
            else -> style
        }

        // Если append - нужно добавить к существующему файлу
        if (append && outputFile.exists()) {
            return appendToExistingMs(outputFile, processedData, finalStyle)
        }

        // Иначе создаем новый MS
        val outputStream = ByteArrayOutputStream()
        MsBuilder.build(processedData, finalStyle, outputStream)
        return outputStream.toByteArray()
    }

    /**
    * Создает уникальный ключ для точки на основе координат и времени
    */
    private fun getWptKey(point: NamedPoint): String {
        // Округляем координаты до 6 знаков (примерно 10 см точности)
        val latStr = "%.6f".format(point.lat)
        val lonStr = "%.6f".format(point.lon)
        val timeStr = point.timeIso ?: "notime"
        return "wpt:$latStr,$lonStr,$timeStr"
    }

    /**
     * Создает уникальный ключ для трека на основе первой и последней точки
     * (как в Python - по названию и количеству точек)
     */
    private fun getTrackKey(track: Track): String {
        val name = track.name
        val pointCount = track.segments.sumOf { it.size }
        return "trk:$name:$pointCount"
    }

    /**
     * Append: добавление новых данных к существующему MS файлу с дедупликацией
     */
    private fun appendToExistingMs(
        existingFile: File,
        newData: ConversionData,
        newStyle: String
    ): ByteArray {
        android.util.Log.d("GPX2MS", "=== APPEND DEBUG (with dedup) ===")

        // Читаем существующий MS
        val existingData = FileInputStream(existingFile).use { stream ->
            MsParser.parse(stream, existingFile.nameWithoutExtension)
        }

        android.util.Log.d("GPX2MS", "existingData.waypoints: ${existingData.waypoints.size}")
        android.util.Log.d("GPX2MS", "existingData.tracks: ${existingData.tracks.size}")
        android.util.Log.d("GPX2MS", "newData.waypoints: ${newData.waypoints.size}")
        android.util.Log.d("GPX2MS", "newData.tracks: ${newData.tracks.size}")

        // Дедупликация
        val (mergedWaypoints, mergedTracks, added, skipped) = deduplicateData(
            existingData.waypoints,
            existingData.tracks,
            newData.waypoints,
            newData.tracks
        )

        android.util.Log.d("GPX2MS", "Добавлено: $added, Пропущено (дубликаты): $skipped")

        val mergedData = ConversionData(
            title = existingData.title,
            waypoints = mergedWaypoints,
            tracks = mergedTracks,
            styleText = null
        )

        android.util.Log.d("GPX2MS", "mergedData.waypoints: ${mergedData.waypoints.size}")
        android.util.Log.d("GPX2MS", "mergedData.tracks: ${mergedData.tracks.size}")

        val outputStream = ByteArrayOutputStream()
        MsBuilder.build(mergedData, newStyle, outputStream)
        val result = outputStream.toByteArray()

        android.util.Log.d("GPX2MS", "result size: ${result.size}")
        android.util.Log.d("GPX2MS", "=== END APPEND DEBUG ===")

        return result
    }

    /**
     * Дедупликация точек и треков
     * @return Четверка (объединенные точки, объединенные треки, добавлено, пропущено)
     */
    private fun deduplicateData(
        existingWpts: List<NamedPoint>,
        existingTracks: List<Track>,
        newWpts: List<NamedPoint>,
        newTracks: List<Track>
    ): Quadruple<List<NamedPoint>, List<Track>, Int, Int> {

        // Множества ключей существующих объектов
        val wptSeen = existingWpts.mapTo(HashSet()) { getWptKey(it) }
        val trackSeen = existingTracks.mapTo(HashSet()) { getTrackKey(it) }

        val mergedWpts = existingWpts.toMutableList()
        val mergedTracks = existingTracks.toMutableList()

        var added = 0
        var skipped = 0

        // Добавляем новые точки с проверкой дубликатов
        for (point in newWpts) {
            val key = getWptKey(point)
            if (key in wptSeen) {
                skipped++
                continue
            }
            wptSeen.add(key)
            mergedWpts.add(point)
            added++
        }

        // Добавляем новые треки с проверкой дубликатов
        for (track in newTracks) {
            val key = getTrackKey(track)
            if (key in trackSeen) {
                skipped++
                continue
            }
            trackSeen.add(key)
            mergedTracks.add(track)
            added++
        }

        return Quadruple(mergedWpts, mergedTracks, added, skipped)
    }

    /**
     * Вспомогательный data class для возврата 4 значений
     */
    data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    /**
     * Определение формата файла (по расширению или содержимому)
     */
    private fun detectFormat(file: File): String {
        val name = file.name.lowercase()

        // По расширению
        when {
            name.endsWith(".gpx") -> return "gpx"
            name.endsWith(".ms") -> return "ms"
        }

        // Если расширение не помогло - смотрим содержимое (как в Python)
        FileInputStream(file).use { inputStream ->
            val header = inputStream.bufferedReader().use { it.readText().take(2000).lowercase() }
            when {
                "<gpx" in header -> return "gpx"
                "<custommapsource" in header || "<geojson" in header -> return "ms"
            }
        }

        throw Exception("Не удалось определить формат файла: ${file.name}")
    }

    /**
     * Обработка line-mode (пока заглушка, как в Python)
     * TODO: Реализовать orth, snake, both
     */
    private fun processLineMode(data: ConversionData, mode: String): ConversionData {
        if (mode == "none" || mode.isEmpty()) {
            return data
        }

        // Пока просто возвращаем данные без изменений
        // Здесь будет реализация ортогональных линий и змейки
        return data
    }

    /**
     * Создание MapCSS стиля из HEX-цвета
     */
    private fun makeStyle(color: String): String {
        val c = if (HEX_COLOR_REGEX.matches(color)) color else "#00FFFF"

        // Встраиваем иконку как base64 (как в Python)
        val circleSvg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"8\" height=\"8\" viewBox=\"0 0 8 8\"><circle cx=\"4\" cy=\"4\" r=\"3\" fill=\"#888\"/></svg>"
        val circleB64 = android.util.Base64.encodeToString(circleSvg.toByteArray(), android.util.Base64.NO_WRAP)

        return """
            node {
                text: eval(tag("name"));
                details-text: eval(tag("name"));
                details-description: eval(tag("name"));
                text-color: black;
                font-stroke-width: 5px;
                font-stroke-color: yellow;
                icon-image: eval(data("$circleB64"));
                icon-scale: 1;
                icon-tint: $c;
            }
            line {
                color: $c;
                width: 3px;
            }
        """.trimIndent()
    }

    /**
     * Вспомогательная функция для создания результата с ошибкой
     */
    private fun errorResult(message: String): ConversionResult {
        return ConversionResult("ERROR:\n$message", null, "")
    }
}