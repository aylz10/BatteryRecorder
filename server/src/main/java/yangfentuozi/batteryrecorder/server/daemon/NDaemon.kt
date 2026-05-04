package yangfentuozi.batteryrecorder.server.daemon

import android.os.Looper
import android.system.Os
import yangfentuozi.batteryrecorder.server.notification.server.NotificationServer
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import yangfentuozi.hiddenapi.compat.ServiceManagerCompat

private const val TAG = "NDaemon"
private const val SERVER_PROCESS_NAME = "batteryrecorder_notification_server"

// notification server daemon
class NDaemon : Daemon(SERVER_PROCESS_NAME, "-notification-server") {
    init {
        killOtherServersExceptSelf()

        if (Os.getuid() != 2000) {
            LoggerX.i(TAG, "init: uid 不为 2000, 执行降权")
            @Suppress("DEPRECATION")
            Os.setuid(2000)
        }

        LoggerX.i(TAG, "init: 初始化 NotificationServer")

        LoggerX.i(TAG, "init: 等待 notification, activity 服务")
        ServiceManagerCompat.waitService("notification")
        ServiceManagerCompat.waitService("activity")

        val server = NotificationServer()
        Runtime.getRuntime().addShutdownHook(Thread(server::onStop))
        server.onStart()
        Looper.loop()
    }
}