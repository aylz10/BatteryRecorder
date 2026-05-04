package yangfentuozi.batteryrecorder.server.daemon

import android.ddm.DdmHandleAppName
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import yangfentuozi.batteryrecorder.shared.Constants
import yangfentuozi.batteryrecorder.shared.util.Handlers
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

private const val TAG = "Daemon"
private val SERVER_CGROUP_DIRS = listOf(
    "/acct",
    "/dev/cg2_bpf",
    "/sys/fs/cgroup",
    "/dev/memcg/apps"
)

open class Daemon(
    val procName: String,
    logSuffix: String = "",
) {
    init {
        DdmHandleAppName.setAppName(procName, 0)

        // 配置 LoggerX
        LoggerX.logDirPath = "${Constants.SHELL_DATA_DIR_PATH}/${Constants.SHELL_LOG_DIR_PATH}"
        LoggerX.suffix = logSuffix
        LoggerX.d(
            TAG,
            "init: LoggerX 配置完成, dir=${LoggerX.logDirPath}, suffix=${LoggerX.suffix}"
        )

        // 切换 cgroup
        switchCgroupIfNeeded()

        // 设置 OOM 保活
        setSelfOomScoreAdj()

        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }
        Handlers.initMainThread()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LoggerX.a(thread.name, "NotificationServer crashed", tr = throwable)
            Handlers.main.post { exitProcess(255) }
        }
    }

    private fun setSelfOomScoreAdj() {
        val oomScoreAdjFile = File("/proc/self/oom_score_adj")
        val oomScoreAdjValue = -1000
        try {
            oomScoreAdjFile.writeText("$oomScoreAdjValue\n")
            val actualValue: String = oomScoreAdjFile.readText().trim()
            if (oomScoreAdjValue.toString() != actualValue) {
                LoggerX.e(
                    TAG,
                    "setSelfOomScoreAdj: 设置 oom_score_adj 失败, expected=$oomScoreAdjValue actual=$actualValue"
                )
                return
            }
            LoggerX.i(TAG, "setSelfOomScoreAdj: 设置 oom_score_adj 成功, actual=$oomScoreAdjValue")
        } catch (e: IOException) {
            LoggerX.e(TAG, "setSelfOomScoreAdj: 设置 oom_score_adj 失败", tr = e)
        } catch (e: RuntimeException) {
            LoggerX.e(TAG, "setSelfOomScoreAdj: 设置 oom_score_adj 失败", tr = e)
        }
    }

    fun killOtherServersExceptSelf() {
        val selfPid = Os.getpid()
        val procDir = File("/proc")
        val entries = procDir.listFiles() ?: run {
            LoggerX.w(TAG, "killOtherServersExceptSelf: /proc 不可读")
            return
        }

        entries.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == selfPid) return@forEach

            val cmdline = try {
                val raw = File("/proc/$pid/cmdline").readBytes()
                val end = raw.indexOf(0).let { if (it >= 0) it else raw.size }
                if (end <= 0) null
                else String(raw, 0, end)
            } catch (_: Exception) {
                null
            } ?: return@forEach
            if (cmdline != procName) return@forEach

            try {
                Os.kill(pid, OsConstants.SIGKILL)
                LoggerX.i(TAG, "killOtherServersExceptSelf: 杀死旧 Server, pid=$pid")
            } catch (e: ErrnoException) {
                LoggerX.w(TAG, "killOtherServersExceptSelf: 杀死旧 Server 失败, pid=$pid", tr = e)
            }
        }
    }

    private fun switchCgroupIfNeeded() {
        val selfPid = Os.getpid()
        for (dir in SERVER_CGROUP_DIRS) {
            val procsFile = File(dir, "cgroup.procs")
            if (!procsFile.exists()) continue

            try {
                procsFile.appendText("$selfPid\n")
                LoggerX.i(TAG, "switchCgroupIfNeeded: 切换 cgroup 成功, path=${procsFile.path}")
                return
            } catch (e: Exception) {
                LoggerX.w(
                    TAG,
                    "switchCgroupIfNeeded: 切换 cgroup 失败, path=${procsFile.path}",
                    tr = e
                )
            }
        }

        LoggerX.w(TAG, "switchCgroupIfNeeded: 未找到可用 cgroup")
    }
}