package io.github.grantbor.gpx2ms

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Парсер для GPX-файлов.
 * Читает GPX и возвращает ConversionData (название, точки, треки).
 */
object GpxParser {

    private val NS_GPX = "http://www.topografix.com/GPX/1/1"

    /**
     * Основная функция для парсинга GPX из InputStream.
     * @param inputStream Поток с данными GPX-файла
     * @param fileName Имя файла (нужно для названия, если в самом файле нет имени)
     * @return ConversionData с распарсенными данными
     * @throws Exception если парсинг не удался
     */
    /**
     * Основная функция для парсинга GPX из InputStream.
     * @param inputStream Поток с данными GPX-файла
     * @param fileName Имя файла (нужно для названия, если в самом файле нет имени)
     * @return ConversionData с распарсенными данными
     * @throws Exception если парсинг не удался
     */
    @Throws(Exception::class)
    fun parse(inputStream: InputStream, fileName: String): ConversionData {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)

        // ВАЖНО: читаем как UTF-8
        parser.setInput(inputStream, "UTF-8")

        try {
            // Пропускаем до первого тега
            var eventType = parser.eventType
            while (eventType != XmlPullParser.START_TAG && eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next()
            }

            if (eventType != XmlPullParser.START_TAG || parser.name != "gpx") {
                throw Exception("Файл не является GPX (корневой тег не <gpx>)")
            }

            return parseGpx(parser, fileName)
        } catch (e: Exception) {
            throw Exception("Ошибка парсинга GPX: ${e.message}", e)
        }
    }

    /**
     * Парсинг корневого элемента <gpx>
     */
    private fun parseGpx(parser: XmlPullParser, fileName: String): ConversionData {
        val waypoints = mutableListOf<NamedPoint>()
        val tracks = mutableListOf<Track>()

        // Пытаемся найти имя в metadata/name
        var title = fileName // По умолчанию имя файла

        var eventType = parser.next()
        var depth = 1 // Добавляем счетчик глубины для безопасности

        while (eventType != XmlPullParser.END_DOCUMENT && depth > 0) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "metadata" -> {
                            val metaName = parseMetadata(parser)
                            if (metaName != null) title = metaName
                        }
                        "wpt" -> {
                            val point = parseWpt(parser)
                            waypoints.add(point)
                        }
                        "trk" -> {
                            val track = parseTrk(parser)
                            tracks.add(track)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    depth--
                }
            }
            eventType = parser.next()
        }

        return ConversionData(title, waypoints, tracks, null)
    }

    /**
     * Парсинг <metadata> и поиск <name>
     */
    private fun parseMetadata(parser: XmlPullParser): String? {
        parser.require(XmlPullParser.START_TAG, null, "metadata")
        var name: String? = null

        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.name == "metadata")) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "name") {
                name = parser.nextText().trim()
                // ВРЕМЕННО: выведем в логи
                android.util.Log.d("GPX2MS", "Найдено имя: $name")

                // Если имя в Unicode escape последовательностях, преобразуем
                if (name?.contains("\\u") == true) {
                    name = unescapeUnicode(name)
                }
            }
            eventType = parser.next()
        }
        return name
    }

    // Добавьте эту функцию для преобразования Unicode escape последовательностей
    private fun unescapeUnicode(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 5 < text.length && text[i + 1] == 'u') {
                val hex = text.substring(i + 2, i + 6)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 6
                    continue
                }
            }
            sb.append(text[i])
            i++
        }
        return sb.toString()
    }

    /**
     * Парсинг <wpt> (waypoint)
     */
    private fun parseWpt(parser: XmlPullParser): NamedPoint {
        parser.require(XmlPullParser.START_TAG, null, "wpt")

        // Атрибуты lat/lon
        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0

        var name = "Без имени" // Значение по умолчанию
        var ele = 0.0
        var time: String? = null
        var desc: String? = null
        var sym: String? = null

        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.name == "wpt")) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "name" -> name = parser.nextText().trim().ifEmpty { "Без имени" }
                    "ele" -> ele = parser.nextText().trim().toDoubleOrNull() ?: 0.0
                    "time" -> time = parser.nextText().trim()
                    "desc" -> desc = parser.nextText().trim()
                    "cmt" -> if (desc.isNullOrEmpty()) desc = parser.nextText().trim()
                    "sym" -> sym = parser.nextText().trim()
                    else -> skipTag(parser) // Пропускаем неизвестные теги
                }
            }
            eventType = parser.next()
        }

        return NamedPoint(name, lat, lon, ele, time, desc, sym)
    }

    /**
     * Парсинг <trk> (track)
     */
    private fun parseTrk(parser: XmlPullParser): Track {
        parser.require(XmlPullParser.START_TAG, null, "trk")

        var name = "track"
        val segments = mutableListOf<List<NamedPoint>>()

        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.name == "trk")) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "name" -> name = parser.nextText().trim().ifEmpty { "track" }
                    "trkseg" -> {
                        val segment = parseTrkseg(parser)
                        if (segment.isNotEmpty()) {
                            segments.add(segment)
                        }
                    }
                    else -> skipTag(parser)
                }
            }
            eventType = parser.next()
        }

        return Track(name, segments)
    }

    /**
     * Парсинг <trkseg> (track segment)
     */
    private fun parseTrkseg(parser: XmlPullParser): List<NamedPoint> {
        parser.require(XmlPullParser.START_TAG, null, "trkseg")

        val points = mutableListOf<NamedPoint>()

        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.name == "trkseg")) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "trkpt") {
                val point = parseTrkpt(parser)
                points.add(point)
            }
            eventType = parser.next()
        }

        return points
    }

    /**
     * Парсинг <trkpt> (track point)
     */
    private fun parseTrkpt(parser: XmlPullParser): NamedPoint {
        parser.require(XmlPullParser.START_TAG, null, "trkpt")

        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0

        var ele = 0.0
        var time: String? = null

        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.name == "trkpt")) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "ele" -> ele = parser.nextText().trim().toDoubleOrNull() ?: 0.0
                    "time" -> time = parser.nextText().trim()
                    else -> skipTag(parser)
                }
            }
            eventType = parser.next()
        }

        // У точек трека нет имени, оставляем пустую строку
        return NamedPoint(name = "", lat = lat, lon = lon, ele = ele, timeIso = time)
    }

    /**
     * Вспомогательная функция для пропуска неизвестных тегов
     */
    private fun skipTag(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) {
            return
        }
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }
}