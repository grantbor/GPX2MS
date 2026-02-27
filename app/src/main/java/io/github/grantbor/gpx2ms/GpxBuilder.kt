package io.github.grantbor.gpx2ms

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/**
 * Билдер для создания GPX-файлов из моделей данных.
 */
object GpxBuilder {

    private val NS_GPX = "http://www.topografix.com/GPX/1/1"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun build(data: ConversionData, outputStream: OutputStream) {
        // ВАЖНО: используем OutputStreamWriter с UTF-8
        val writer = PrintWriter(OutputStreamWriter(outputStream, Charsets.UTF_8), true)
        writer.use { w ->
            // XML заголовок с UTF-8
            w.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")

            w.println("<gpx version=\"1.1\" creator=\"GPX2MS Kotlin\" xmlns=\"$NS_GPX\">")

            w.println("  <metadata>")
            w.println("    <name>${escapeXml(data.title)}</name>")
            w.println("  </metadata>")

            for (point in data.waypoints) {
                writeWaypoint(w, point)
            }

            for (track in data.tracks) {
                writeTrack(w, track)
            }

            w.println("</gpx>")
        }
    }

    private fun writeWaypoint(writer: PrintWriter, point: NamedPoint) {
        writer.println("  <wpt lat=\"${point.lat}\" lon=\"${point.lon}\">")
        writer.println("    <name>${escapeXml(point.name)}</name>")
        writer.println("    <ele>${point.ele}</ele>")

        point.timeIso?.let {
            writer.println("    <time>${it}</time>")
        }
        point.desc?.let {
            writer.println("    <desc>${escapeXml(it)}</desc>")
        }
        point.sym?.let {
            writer.println("    <sym>${escapeXml(it)}</sym>")
        }

        writer.println("  </wpt>")
    }

    private fun writeTrack(writer: PrintWriter, track: Track) {
        writer.println("  <trk>")
        writer.println("    <name>${escapeXml(track.name)}</name>")

        for (segment in track.segments) {
            writer.println("    <trkseg>")
            for (point in segment) {
                writer.println("      <trkpt lat=\"${point.lat}\" lon=\"${point.lon}\">")
                writer.println("        <ele>${point.ele}</ele>")
                point.timeIso?.let {
                    writer.println("        <time>${it}</time>")
                }
                writer.println("      </trkpt>")
            }
            writer.println("    </trkseg>")
        }

        writer.println("  </trk>")
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
        // Кириллица остается как есть
    }
}