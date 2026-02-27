package io.github.grantbor.gpx2ms

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter

object MsBuilder {

    fun build(data: ConversionData, style: String, outputStream: OutputStream) {
        // ВАЖНО: используем OutputStreamWriter с UTF-8
        val writer = PrintWriter(OutputStreamWriter(outputStream, Charsets.UTF_8), true)
        writer.use { w ->
            w.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            w.println("<customMapSource overlay=\"true\">")
            w.println("  <name>${escapeXml(data.title)}</name>")
            w.println("  <style><![CDATA[")
            w.println(style)
            w.println("  ]]></style>")
            w.println("  <geojson><![CDATA[")
            w.println(buildGeoJson(data))
            w.println("  ]]></geojson>")
            w.println("</customMapSource>")
        }
    }

    private fun buildGeoJson(data: ConversionData): String {
        val features = JSONArray()

        for (point in data.waypoints) {
            features.put(createPointFeature(point))
        }

        for (track in data.tracks) {
            features.put(createTrackFeature(track))
        }

        val geojson = JSONObject()
        geojson.put("type", "FeatureCollection")
        geojson.put("features", features)

        return geojson.toString(2)
    }

    private fun createPointFeature(point: NamedPoint): JSONObject {
        val coordinates = JSONArray().apply {
            put(point.lon)
            put(point.lat)
            if (point.ele != 0.0) {
                put(point.ele)
            }
        }

        val geometry = JSONObject().apply {
            put("type", "Point")
            put("coordinates", coordinates)
        }

        val properties = JSONObject().apply {
            put("name", point.name)
            point.timeIso?.let { put("time", it) }
            point.desc?.let { put("desc", it) }
            point.sym?.let { put("sym", it) }
        }

        return JSONObject().apply {
            put("type", "Feature")
            put("geometry", geometry)
            put("properties", properties)
        }
    }

    private fun createTrackFeature(track: Track): JSONObject {
        val coordinates = JSONArray()
        val timesArray = JSONArray()

        for (segment in track.segments) {
            val segmentCoords = JSONArray()
            val segmentTimes = JSONArray()

            for (point in segment) {
                val pointCoords = JSONArray().apply {
                    put(point.lon)
                    put(point.lat)
                    if (point.ele != 0.0) {
                        put(point.ele)
                    }
                }
                segmentCoords.put(pointCoords)

                point.timeIso?.let {
                    segmentTimes.put(it)
                }
            }

            coordinates.put(segmentCoords)
            if (segmentTimes.length() > 0) {
                timesArray.put(segmentTimes)
            }
        }

        val geometry = JSONObject().apply {
            put("type", "MultiLineString")
            put("coordinates", coordinates)
        }

        val properties = JSONObject().apply {
            put("name", track.name)
            if (timesArray.length() > 0) {
                put("times", timesArray)
            }
        }

        return JSONObject().apply {
            put("type", "Feature")
            put("geometry", geometry)
            put("properties", properties)
        }
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}