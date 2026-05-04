package yangfentuozi.batteryrecorder.server.notification.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.system.Os
import yangfentuozi.batteryrecorder.server.notification.LocalNotificationUtil
import yangfentuozi.batteryrecorder.server.notification.NotificationUtil
import yangfentuozi.batteryrecorder.server.notification.server.stream.NotificationStreamMessage
import yangfentuozi.batteryrecorder.server.notification.server.stream.StreamReader
import yangfentuozi.batteryrecorder.shared.config.dataclass.ServerSettings
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import java.io.IOException
import kotlin.system.exitProcess

private const val TAG = "NotificationServer"

class NotificationServer(
    stop: () -> Unit = { exitProcess(0) },
){

    @Volatile
    var isStopped = false

    @Volatile
    private var cleanedUp = false

    val notificationUtil: NotificationUtil
    var socket: LocalSocket? = null
    var reader: StreamReader? = null
    val serverSocket: LocalServerSocket
    val serverThread = Thread({
        try {
            LoggerX.i(TAG, "@serverRunnable: 等待客户端")
            socket = serverSocket.accept()
            LoggerX.i(TAG, "@serverRunnable: 接受客户端")
            reader = StreamReader(socket!!.inputStream)
            while (!isStopped) {
                when (val message = reader!!.readNext() ?: break) {
                    is NotificationStreamMessage.Data -> notificationUtil.updateNotification(message.info)
                    NotificationStreamMessage.CancelNotification -> notificationUtil.cancelNotification()
                    is NotificationStreamMessage.Settings ->
                        message.settings?.let { syncSettings(it) }
                    NotificationStreamMessage.Stop -> {
                        isStopped = true
                        stop()
                    }
                }
            }
        } catch (e: IOException) {
            if (!isStopped) LoggerX.e(TAG, "@serverRunnable: 处理客户端请求时出现异常", tr = e)
        } finally {
            reader?.let { runCatching { it.close() } }
            socket?.let { runCatching { it.close() } }
        }
        reader = null
        socket = null
        notificationUtil.cancelNotification()
        stop()
    }, "ServerSocketThread")

    init {
        LoggerX.i(
            TAG,
            "init: NotificationServer 初始化开始, uid=${Os.getuid()}"
        )
        notificationUtil = LocalNotificationUtil()

        LoggerX.i(TAG, "init: 创建 LocalServerSocket 通信服务")
        serverSocket = LocalServerSocket(SOCKET_NAME)
    }

    fun syncSettings(settings: ServerSettings) {
        LoggerX.d(TAG, "syncSettings: 应用配置: $settings")
        LoggerX.maxHistoryDays = settings.maxHistoryDays
        LoggerX.logLevel = settings.logLevel
        notificationUtil.syncSettings(settings)
    }

    fun onStart() {
        serverThread.start()
    }

    fun onStop() {
        if (cleanedUp) return
        LoggerX.i(TAG, "停止服务")
        cleanedUp = true
        isStopped = true
        runCatching {
            serverThread.interrupt()
            reader?.let { runCatching { it.close() } }
            socket?.let { runCatching { it.close() } }
            notificationUtil.cancelNotification()
            serverSocket.close()
        }
        try {
            LoggerX.flushBlocking()
            LoggerX.writer?.close()
        } catch (e: Exception) {
            LoggerX.e(TAG, "onStop: 刷新日志缓冲失败", tr = e, notWrite = true)
        }
    }

    companion object {
        const val SOCKET_NAME = "BatteryRecorder_NotificationServer"
    }
}
