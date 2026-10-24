package id.g6hifiplayer

import android.media.AudioFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Build 1.36: facts needed to write a native (C++) 24-bit packed output path on the LG G6.
 * 1) Does the Java framework treat encoding 21 (24-bit packed) as a valid AudioTrack format?
 * 2) Can this app dlopen the private C++ audio client library, and which AudioTrack symbols does it export?
 * Pure read-only diagnostics; nothing here changes playback.
 */
internal object FrameworkProbe {
    fun run(report: (String) -> Unit) {
        report("PROBE_DEVICE sdk=${android.os.Build.VERSION.SDK_INT} model=${android.os.Build.MODEL} fingerprint=${android.os.Build.FINGERPRINT} abis=${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")
        for (name in listOf("isPublicEncoding", "isValidEncoding", "isEncodingLinearPcm")) {
            val result = try {
                val m = AudioFormat::class.java.getDeclaredMethod(name, Int::class.javaPrimitiveType)
                m.isAccessible = true
                listOf(16, 4, 21, 22, 13).joinToString(" ") { "enc$it=${m.invoke(null, it)}" }
            } catch (e: Throwable) { "unavailable ${e.javaClass.simpleName}" }
            report("PROBE_JAVA_ENCODING_CHECK $name $result")
        }
        val dirs = listOf("/system/lib64", "/vendor/lib64", "/system/lib", "/vendor/lib")
        for (lib in listOf("libaudioclient.so", "libmedia.so", "libaudioflinger.so", "libaudiopolicyservice.so")) {
            for (dir in dirs) {
                val f = File(dir, lib)
                if (!f.exists()) continue
                report("PROBE_LIB path=${f.path} size=${f.length()} readable=${f.canRead()}")
                try { System.load(f.path); report("PROBE_DLOPEN path=${f.path} result=OK") }
                catch (e: Throwable) { report("PROBE_DLOPEN path=${f.path} result=FAILED ${(e.message ?: "").take(160)}") }
                if (lib == "libaudioclient.so" || (lib == "libmedia.so" && !File(dir, "libaudioclient.so").exists())) dumpSymbols(f, report)
            }
        }
        for (dir in listOf("/vendor/lib64/hw", "/vendor/lib/hw")) {
            File(dir).listFiles { x -> x.name.startsWith("audio.primary") }?.forEach {
                report("PROBE_HAL path=${it.path} size=${it.length()}")
            }
        }
    }

    private fun dumpSymbols(file: File, report: (String) -> Unit) {
        try {
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteBuffer.allocate(raf.length().toInt()).order(ByteOrder.LITTLE_ENDIAN)
                raf.channel.read(buf); buf.flip()
                val is64 = buf.get(4).toInt() == 2
                val shoff = if (is64) buf.getLong(0x28).toInt() else buf.getInt(0x20)
                val shentsize = buf.getShort(if (is64) 0x3A else 0x2E).toInt() and 0xFFFF
                val shnum = buf.getShort(if (is64) 0x3C else 0x30).toInt() and 0xFFFF
                val found = mutableListOf<String>()
                for (i in 0 until shnum) {
                    val sh = shoff + i * shentsize
                    if (buf.getInt(sh + 4) != 11) continue // SHT_DYNSYM
                    val off = if (is64) buf.getLong(sh + 0x18).toInt() else buf.getInt(sh + 0x10)
                    val size = if (is64) buf.getLong(sh + 0x20).toInt() else buf.getInt(sh + 0x14)
                    val link = buf.getInt(sh + if (is64) 0x28 else 0x18)
                    val strSh = shoff + link * shentsize
                    val strOff = if (is64) buf.getLong(strSh + 0x18).toInt() else buf.getInt(strSh + 0x10)
                    val entSize = if (is64) 24 else 16
                    for (s in 0 until size / entSize) {
                        val nameIdx = buf.getInt(off + s * entSize)
                        var p = strOff + nameIdx
                        val sb = StringBuilder()
                        while (buf.get(p).toInt() != 0 && sb.length < 300) { sb.append(buf.get(p).toInt().toChar()); p++ }
                        val n = sb.toString()
                        if (n.startsWith("_ZN7android10AudioTrack") &&
                            (n.startsWith("_ZN7android10AudioTrackC") || n.contains("3setE") || n.contains("5writeE") || n.contains("5startE"))) found += n
                    }
                }
                report("PROBE_SYMBOLS file=${file.path} matches=${found.size}")
                found.take(14).forEach { report("PROBE_SYMBOL $it") }
            }
        } catch (e: Throwable) {
            report("PROBE_SYMBOLS file=${file.path} failed ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
