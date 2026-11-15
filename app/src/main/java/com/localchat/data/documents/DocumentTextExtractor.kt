package com.localchat.data.documents

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream

object DocumentTextExtractor {
    private const val MAX_TEXT_CHARS = 250_000

    fun extract(context: Context, name: String, bytes: ByteArray): String? {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val text = runCatching {
            when (ext) {
                "pdf" -> extractPdf(context, bytes)
                "docx" -> extractDocx(bytes)
                "html", "htm" -> Jsoup.parse(bytes.toString(Charsets.UTF_8)).text()
                "rtf" -> extractRtf(bytes.toString(Charsets.UTF_8))
                "txt", "md", "markdown", "json", "csv", "tsv", "log", "xml", "yaml", "yml" ->
                    bytes.toString(Charsets.UTF_8)
                else -> extractIfPlainText(bytes)
            }
        }.getOrNull() ?: return null

        return text
            .replace("\u0000", "")
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .take(MAX_TEXT_CHARS)
            .takeIf { it.isNotBlank() }
    }

    private fun extractPdf(context: Context, bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(bytes).use { document ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            buildString {
                for (page in 1..document.numberOfPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val pageText = stripper.getText(document).trim()
                    if (pageText.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n")
                        append("[PDF page ").append(page).append("]\n")
                        append(pageText)
                    }
                }
            }
        }
    }

    private fun extractDocx(bytes: ByteArray): String {
        val out = StringBuilder()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!entry.isDirectory && isWordTextPart(name)) {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    val part = wordXmlToText(xml)
                    if (part.isNotBlank()) {
                        if (out.isNotEmpty()) out.append("\n\n")
                        out.append(part.trim())
                    }
                }
                zip.closeEntry()
            }
        }
        return out.toString()
    }

    private fun isWordTextPart(name: String): Boolean {
        if (!name.startsWith("word/") || !name.endsWith(".xml")) return false
        val file = name.substringAfterLast('/')
        return file == "document.xml" ||
            file.startsWith("header") ||
            file.startsWith("footer") ||
            file == "footnotes.xml" ||
            file == "endnotes.xml"
    }

    private fun wordXmlToText(xml: String): String {
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser()
        parser.setInput(StringReader(xml))

        val out = StringBuilder()
        var inText = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "t" -> inText = true
                    "tab" -> out.append('\t')
                    "br", "cr" -> out.append('\n')
                    "p" -> if (out.isNotEmpty() && !out.endsWith('\n')) out.append('\n')
                }
                XmlPullParser.TEXT -> if (inText) out.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "p" -> if (out.isNotEmpty() && !out.endsWith('\n')) out.append('\n')
                }
            }
            event = parser.next()
        }
        return out.toString()
    }

    private fun extractRtf(raw: String): String {
        return raw
            .replace(Regex("\\\\par[d]?"), "\n")
            .replace(Regex("\\\\'[0-9a-fA-F]{2}")) { match ->
                match.value.takeLast(2).toInt(16).toChar().toString()
            }
            .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
            .replace(Regex("[{}]"), "")
    }

    private fun extractIfPlainText(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val sample = bytes.take(4096)
        val printable = sample.count { b ->
            val c = b.toInt() and 0xff
            c == 9 || c == 10 || c == 13 || c in 32..126 || c >= 128
        }
        if (printable < sample.size * 0.85f) return null
        return bytes.toString(Charsets.UTF_8)
    }
}

private fun StringBuilder.endsWith(char: Char): Boolean = isNotEmpty() && this[length - 1] == char

