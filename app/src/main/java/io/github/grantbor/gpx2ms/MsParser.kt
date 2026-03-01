package io.github.grantbor.gpx2ms

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Парсер для MS-файлов (customMapSource).
 * Читает MS и возвращает ConversionData (название, точки, треки, стиль).
 */
object MsParser {

    /**
     * Основная функция для парсинга MS из InputStream.
     * @param inputStream Поток с данными MS-файла
     * @param fileName Имя файла (для названия по умолчанию)
     * @return ConversionData с распарсенными данными и стилем
     * @throws Exception если парсинг не удался
     */
    @Throws(Exception::class)
    fun parse(inputStream: InputStream, fileName: String): ConversionData {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)

        // Пропускаем до первого тега
        var eventType = parser.eventType
        while (eventType != XmlPullParser.START_TAG && eventType != XmlPullParser.END_DOCUMENT) {
            eventType = parser.next()
        }

        // Должен быть корневой тег <customMapSource>
        if (eventType != XmlPullParser.START_TAG || parser.name != "customMapSource") {
            throw Exception("Файл не является MS (корневой тег не <customMapSource>)")
        }

        return parseCustomMapSource(parser, fileName)
    }

    /**
     * Парсинг корневого элемента <customMapSource>
     */
    private fun parseCustomMapSource(parser: XmlPullParser, fileName: String): ConversionData {
        var title = fileName
        var styleText: String? = null
        var geojsonText: String? = null

        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.name == "customMapSource")) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "name" -> {
                        title = parser.nextText().trim().ifEmpty { fileName }
                    }
                    "style" -> {
                        styleText = parser.nextText().trim()
                    }
                    "geojson" -> {
                        geojsonText = parser.nextText().trim()
                    }
                    else -> {
                        // Пропускаем неизвестные теги
                        skipTag(parser)
                    }
                }
            }
            eventType = parser.next()
        }

        // Если нет geojson - ошибка
        if (geojsonText.isNullOrEmpty()) {
            throw Exception("MS файл не содержит <geojson>")
        }

        // Парсим GeoJSON
        val (waypoints, tracks) = parseGeoJson(geojsonText)

        return ConversionData(title, waypoints, tracks, styleText)
    }

    /**
     * Парсинг GeoJSON строки в точки и треки
     * @return Pair(список точек, список треков)
     */
    private fun parseGeoJson(geojsonText: String): Pair<List<NamedPoint>, List<Track>> {
        val waypoints = mutableListOf<NamedPoint>()
        val tracks = mutableListOf<Track>()

        val json = JSONObject(geojsonText)
        val features = json.optJSONArray("features") ?: JSONArray()

        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            val type = geometry.optString("type")

            when (type) {
                "Point" -> {
                    val point = parseGeoJsonPoint(geometry, properties)
                    if (point != null) waypoints.add(point)
                }
                "MultiLineString" -> {
                    val track = parseGeoJsonMultiLineString(geometry, properties)
                    if (track != null) tracks.add(track)
                }
                // Можно добавить другие типы, если нужно
            }
        }

        return Pair(waypoints, tracks)
    }

    /**
     * Парсинг GeoJSON Point
     */
    private fun parseGeoJsonPoint(geometry: JSONObject, properties: JSONObject): NamedPoint? {
        val coords = geometry.optJSONArray("coordinates") ?: return null
        if (coords.length() < 2) return null

        val lon = coords.optDouble(0, 0.0)
        val lat = coords.optDouble(1, 0.0)
        val ele = if (coords.length() >= 3) coords.optDouble(2, 0.0) else 0.0

        val name = properties.optString("name", "Без имени")
        val time = properties.optString("time", null)
        val desc = properties.optString("desc", null)
        val sym = properties.optString("sym", null)

        return NamedPoint(name, lat, lon, ele, time, desc, sym)
    }

    /**
     * Парсинг GeoJSON MultiLineString в трек
     */
    private fun parseGeoJsonMultiLineString(geometry: JSONObject, properties: JSONObject): Track? {
        val coords = geometry.optJSONArray("coordinates") ?: return null
        val times = properties.optJSONArray("times")

        val name = properties.optString("name", "track")
        val segments = mutableListOf<List<NamedPoint>>()

        for (i in 0 until coords.length()) {
            val lineCoords = coords.optJSONArray(i) ?: continue
            val segmentPoints = mutableListOf<NamedPoint>()
            val segmentTimes = if (times != null && i < times.length()) {
                times.optJSONArray(i)
            } else null

            for (j in 0 until lineCoords.length()) {
                val pointCoords = lineCoords.optJSONArray(j) ?: continue
                if (pointCoords.length() < 2) continue

                val lon = pointCoords.optDouble(0, 0.0)
                val lat = pointCoords.optDouble(1, 0.0)
                val ele = if (pointCoords.length() >= 3) pointCoords.optDouble(2, 0.0) else 0.0

                val time = if (segmentTimes != null && j < segmentTimes.length()) {
                    segmentTimes.optString(j, null)
                } else null

                segmentPoints.add(NamedPoint(name = "", lat = lat, lon = lon, ele = ele, timeIso = time))
            }

            if (segmentPoints.isNotEmpty()) {
                segments.add(segmentPoints)
            }
        }

        return if (segments.isNotEmpty()) Track(name, segments) else null
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