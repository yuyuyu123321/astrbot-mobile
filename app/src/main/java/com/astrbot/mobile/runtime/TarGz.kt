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
        extractTo(dest) { GZIPInputStream(archive.inputStream().buffered()) }
    }

    /**
     * 从 APK assets 中流式解压 tar.gz。
     * 内置 rootfs 约 300MB，不能整体读入内存，必须走流式解压。
     *
     * @param onProgress 0f..1f，按已消费的压缩字节数 / 总压缩字节数估算
     */
    fun extractAsset(
        context: android.content.Context,
        assetPath: String,
        dest: File,
        onProgress: (Float) -> Unit = {}
    ) {
        val totalSize = try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (_: Exception) {
            0L
        }
        dest.mkdirs()
        context.assets.open(assetPath, android.content.res.AssetManager.ACCESS_STREAMING).use { raw ->
            val counting = CountingInputStream(raw, totalSize, onProgress)
            extractTo(dest) { GZIPInputStream(counting, 64 * 1024) }
        }
        onProgress(1f)
    }

    /** 按压缩字节上报进度（避免解压时无反馈） */
    private class CountingInputStream(
        private val inner: InputStream,
        private val total: Long,
        private val onProgress: (Float) -> Unit
    ) : InputStream() {
        private var read = 0L
        private var lastPct = -1

        private fun report() {
            if (total <= 0) return
            val pct = (read * 100 / total).toInt()
            if (pct != lastPct) {
                lastPct = pct
                onProgress((read.toFloat() / total).coerceIn(0f, 1f))
            }
        }

        override fun read(): Int {
            val b = inner.read()
            if (b >= 0) {
                read++
                report()
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) {
                read += n
                report()
            }
            return n
        }

        override fun available(): Int = inner.available()
        override fun close() = inner.close()
    }

    private fun extractTo(dest: File, open: () -> InputStream) {
        dest.mkdirs()
        open().use { gz ->
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