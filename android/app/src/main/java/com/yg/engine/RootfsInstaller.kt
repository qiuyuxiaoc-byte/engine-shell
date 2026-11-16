package com.yg.engine

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 首启准备。**用系统自带 toybox tar 解压**（原生 xz，几秒级），不用 Java 逐条写文件。
 *
 * 流程：
 *   1. assets 里的 rootfs.tar.xz → files/runtime/rootfs.tar.xz（流式拷贝，报字节进度）
 *   2. toybox tar -xJf 解到 files/runtime/rootfs.tmp（原生，约 10~30 秒）
 *   3. 校验哨兵文件 → 原子改名成 rootfs → 删掉 tarball
 *   4. 失败才退回 Java 流式解压（兜底，慢但一定能成）
 */
class RootfsInstaller(private val ctx: Context) {

    private val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    fun install(onProgress: (String) -> Unit) {
        val files = File(ctx.applicationInfo.dataDir, "files")
        val runtime = File(files, "runtime").apply { mkdirs() }
        val target = File(runtime, "rootfs")
        val staging = File(runtime, "rootfs.tmp")

        if (!target.isDirectory) {
            staging.deleteRecursively(); staging.mkdirs()
            val asset = "runtime/rootfs-$abi.tar.xz"
            val tarFile = File(runtime, "rootfs.tar.xz")

            // 1) 拷贝
            val total = runCatching { ctx.assets.openFd(asset).length }
                .getOrElse { 153L * 1024 * 1024 }
            var copied = 0L
            ctx.assets.open(asset).use { input ->
                FileOutputStream(tarFile).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n <= 0) break
                        out.write(buf, 0, n); copied += n
                        if (copied % (4L * 1024 * 1024) < n)
                            onProgress("准备运行时 ${copied * 100 / total}%")
                    }
                }
            }
            onProgress("解压运行时…")

            // 2) 原生解压
            val ok = runTar(tarFile, staging)
            if (!ok || !File(staging, "usr/local/bin/dsh").exists()) {
                staging.deleteRecursively(); staging.mkdirs()
                onProgress("解压运行时（兼容模式）…")
                javaExtract(tarFile, staging, onProgress)
            }

            // 3) 校验 + 落地
            if (!File(staging, "usr/local/bin/dsh").exists()) throw IllegalStateException("rootfs 解压不完整")
            target.deleteRecursively()
            staging.renameTo(target)
            tarFile.delete()
        }

        // 启动脚本
        val engineDir = File(files, "engine").apply { mkdirs() }
        val script = File(engineDir, "start-engine.sh")
        ctx.assets.open("engine/start-engine.sh").use { it.copyTo(FileOutputStream(script), 64 * 1024) }
        script.setExecutable(true, false)

        // 密钥（CI 注入，可能没有）
        val etcApp = File(target, "etc/app").apply { mkdirs() }
        runCatching {
            ctx.assets.open("engine/opencode.key").use {
                it.copyTo(FileOutputStream(File(etcApp, "opencode.key")))
            }
        }

        // patch
        ctx.assets.list("patches").orEmpty().forEach { name ->
            ctx.assets.open("patches/$name").use { it.copyTo(FileOutputStream(File(etcApp, name))) }
        }
        onProgress("准备完成")
    }

    /** 系统 toybox tar -xJf，原生解压 */
    private fun runTar(tar: File, dest: File): Boolean = runCatching {
        val p = ProcessBuilder("/system/bin/toybox", "tar", "-xJf", tar.absolutePath, "-C", dest.absolutePath)
            .redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()   // 读干净，避免阻塞
        p.waitFor() == 0
    }.getOrDefault(false)

    /** 兜底：Java 流式解压（慢，但一定能成） */
    private fun javaExtract(tar: File, dest: File, onProgress: (String) -> Unit) {
        var n = 0L
        tar.inputStream().use { raw ->
            XZCompressorInputStream(raw, true).use { xz ->
                TarArchiveInputStream(xz).use { t ->
                    var e = t.nextEntry
                    while (e != null) {
                        val out = File(dest, e.name)
                        if (!out.canonicalPath.startsWith(dest.canonicalPath + File.separator))
                            throw SecurityException("tar escape: ${e.name}")
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { fo -> t.copyTo(fo, 256 * 1024) }
                            if (e.mode and 0b001_000_000 != 0) out.setExecutable(true, false)
                        }
                        n++
                        if (n % 2000 == 0L) onProgress("兼容解压 $n 个文件")
                        e = t.nextEntry
                    }
                }
            }
        }
    }
}
