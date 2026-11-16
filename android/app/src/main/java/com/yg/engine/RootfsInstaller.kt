package com.yg.engine

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 首启准备：
 *  1. assets/runtime/rootfs-arm64-v8a.tar.xz → files/runtime/rootfs（原子替换，几分钟，带进度）
 *  2. assets/engine/start-engine.sh          → files/engine/start-engine.sh
 *  3. assets/patches 下的 yml 文件          → files/runtime/rootfs/etc/app/（proot 里就是 /etc/app）
 */
class RootfsInstaller(private val ctx: Context) {

    private val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    fun install(onProgress: (Int) -> Unit) {
        val files = File(ctx.applicationInfo.dataDir, "files")
        val target = File(files, "runtime/rootfs")

        if (!target.isDirectory) {
            val staging = File(files, "runtime/rootfs.tmp")
            staging.deleteRecursively(); staging.mkdirs()
            val asset = "runtime/rootfs-$abi.tar.xz"
            // 万一被 aapt 压缩过，openFd 会抛异常；退化成按已读字节算进度，别让安装失败
            val total = runCatching { ctx.assets.openFd(asset).length }.getOrElse { 153L * 1024 * 1024 }
            var read = 0L
            ctx.assets.open(asset).use { raw ->
                XZCompressorInputStream(raw, true).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        var e = tar.nextEntry
                        while (e != null) {
                            val out = File(staging, e.name)
                            val base = staging.canonicalPath
                            if (!out.canonicalPath.startsWith(base + File.separator)) throw SecurityException("tar escape: ${e.name}")
                            if (e.isDirectory) out.mkdirs() else {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { fo -> tar.copyTo(fo) }
                                if (e.mode and 0b001_000_000 != 0) out.setExecutable(true, false)
                            }
                            read += e.size.coerceAtLeast(1)
                            onProgress(((read * 100) / total).toInt().coerceIn(0, 99))
                            e = tar.nextEntry
                        }
                    }
                }
            }
            target.deleteRecursively()
            staging.renameTo(target)
        }

        // 启动脚本
        val engineDir = File(files, "engine").apply { mkdirs() }
        val script = File(engineDir, "start-engine.sh")
        ctx.assets.open("engine/start-engine.sh").use { it.copyTo(FileOutputStream(script), 64 * 1024) }
        script.setExecutable(true, false)

        // patch 落到 rootfs 的 /etc/app
        val etcApp = File(target, "etc/app").apply { mkdirs() }
        for (name in ctx.assets.list("patches").orEmpty()) {
            ctx.assets.open("patches/$name").use { it.copyTo(FileOutputStream(File(etcApp, name))) }
        }
        // 模型密钥（CI 生成，不在仓库里）
        try {
            ctx.assets.open("engine/opencode.key").use { it.copyTo(FileOutputStream(File(etcApp, "opencode.key"))) }
        } catch (_: java.io.IOException) { /* 没配 key 也能起，只是不能对话 */ }

        onProgress(100)
    }
}
