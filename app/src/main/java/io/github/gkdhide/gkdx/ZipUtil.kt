package io.github.gkdhide.gkdx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 订阅数据的压缩工具，界面进程与 Hook 进程共用（不依赖任何 Xposed 类） */
object ZipUtil {
    fun gzip(s: String): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(s.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()

    fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
}
