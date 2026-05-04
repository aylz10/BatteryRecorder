package yangfentuozi.batteryrecorder.server.daemon

import androidx.annotation.Keep

@Keep
object Main {

    @Keep
    @JvmStatic
    fun main(args: Array<String>) {
        val isNotificationServer = args.isNotEmpty() && args[0] == "--notification-server"
        if (isNotificationServer) {
            NDaemon()
        } else {
            SDaemon()
        }
    }
}
