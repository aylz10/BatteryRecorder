package yangfentuozi.batteryrecorder.server.notification

import yangfentuozi.batteryrecorder.server.notification.server.ChildServerBridge
import yangfentuozi.batteryrecorder.shared.config.SettingsConstants

class RemoteNotificationUtil(
    private val bridge: ChildServerBridge,
    private var compatibilityModeEnabled: Boolean = SettingsConstants.notificationCompatModeEnabled.def,
    private var iconCompatibilityModeEnabled: Boolean = SettingsConstants.notificationIconCompatModeEnabled.def
) : NotificationUtil {

    private val lock = Any()

    init {
        bridge.onWriterConnected = { writer ->
            synchronized(lock) {
                writer.writeCompatibilityModeEnabled(compatibilityModeEnabled)
            }
        }
        bridge.writer?.let { writer ->
            synchronized(lock) {
                writer.writeCompatibilityModeEnabled(compatibilityModeEnabled)
            }
        }
    }

    /**
     * 更新通知子进程的兼容模式配置。
     *
     * @param enabled `true` 表示每次更新通知都新建 Builder；`false` 表示继续复用 Builder。
     * @return 无。
     */
    override fun setCompatibilityModeEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (compatibilityModeEnabled == enabled) return
            compatibilityModeEnabled = enabled
            bridge.writer?.writeCompatibilityModeEnabled(enabled)
        }
    }

    override fun setIconCompatibilityModeEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (iconCompatibilityModeEnabled == enabled) return
            iconCompatibilityModeEnabled = enabled
            bridge.writer?.writeIconCompatibilityModeEnabled(enabled)
        }
    }

    override fun updateNotification(info: NotificationInfo) {
        synchronized(lock) {
            bridge.writer?.write(info)
        }
    }

    override fun cancelNotification() {
        synchronized(lock) {
            bridge.writer?.writeCancel()
        }
    }
}
