package com.astrbot.mobile.runtime

import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 纯 Kotlin tar.gz 解压器（支持 ustar、GNU longname、符号链接、硬链接）。
 */
object TarGz {

    fun extract(archive: File, dest: File) {
        dest.mkdirs()
        GZIPInputStream(archive.inputStream().buffered()).use { gz ->
            val header = ByteArray(512)
            var pendingName: String? = null
            while (true) {
                if (!readFully(gz, header)) break
                if (header.all { it == 0.toByte() }) break

                var name = parseString(header, 0, 100)
                val mode = parseOctal(header, 100, 8).toInt()
                val size = parseOctal(header, 124, 12)
                val type = header[156].toInt().toChar()
                val linkName = parseString(header, 157, 100)
                val prefix = parseString(header, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
                pendingName?.let { name = it; pendingName = null }

                val pad = (size + 511) / 512 * 512 - size

                when (type) {
                    'L' -> pendingName = readData(gz, size)
                    '5' -> File(dest, name).mkdirs()
                    '2' -> {
                        val out = File(dest, name)
                        out.parentFile?.mkdirs()
                        out.delete()
                        try {
                            Os.symlink(linkName, out.absolutePath)
                        } catch (_: Exception) {
                        }
                    }

                    '1' -> {
                        val out = File(dest, name)
                        out.parentFile?.mkdirs()
                        try {
                            Os.link(File(dest, linkName).absolutePath, out.absolutePath)
                        } catch (_: Exception) {
                        }
                    }

                    '0', Char(0) -> {
                        val out = File(dest, name)
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos -> copyBytes(gz, fos, size) }
                        try {
                            Os.chmod(out.absolutePath, mode and 0xFFF)
                        } catch (_: Exception) {
                        }
                    }

                    else -> skipFully(gz, size)
                }
                if (pad > 0) skipFully(gz, pad)
            }
        }
    }

    /** 读取 tar.gz 中指定文本条目内容 */
    fun extractEntryText(archive: File, entryName: String): String? {
        GZIPInputStream(archive.inputStream().buffered()).use { gz ->
            val header = ByteArray(512)
            var pendingName: String? = null
            while (true) {
                if (!readFully(gz, header)) break
                if (header.all { it == 0.toByte() }) break

                var name = parseString(header, 0, 100)
                val size = parseOctal(header, 124, 12)
                val type = header[156].toInt().toChar()
                val prefix = parseString(header, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
                pendingName?.let { name = it; pendingName = null }

                val pad = (size + 511) / 512 * 512 - size

                if (type == 'L') {
                    pendingName = readData(gz, size)
                } else if ((type == '0' || type == Char(0)) && (name == entryName || name == "./$entryName")) {
                    return readData(gz, size)
                } else {
                    skipFully(gz, size)
                }
                if (pad > 0) skipFully(gz, pad)
            }
        }
        return null
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun copyBytes(input: InputStream, out: FileOutputStream, size: Long) {
        val buf = ByteArray(8192)
        var remain = size
        while (remain > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remain -= n
        }
    }

    private fun readData(input: InputStream, size: Long): String {
        val data = ByteArray(size.toInt())
        var off = 0
        while (off < data.size) {
            val n = input.read(data, off, data.size - off)
            if (n < 0) break
            off += n
        }
        return String(data).trimEnd(Char(0), '\n')
    }

    private fun skipFully(input: InputStream, size: Long) {
        var remain = size
        val buf = ByteArray(8192)
        while (remain > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
            if (n < 0) break
            remain -= n
        }
    }

    private fun parseString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        val s = parseString(header, offset, length).trim()
        return if (s.isEmpty()) 0L else s.toLong(8)
    }
}