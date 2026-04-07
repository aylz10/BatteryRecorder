package yangfentuozi.batteryrecorder.server.notification

import java.io.DataInputStream
import java.io.DataOutputStream

data class NotificationInfo(
    val power: Double,
    val temp: Int,
    val capacity: Int
) {
    fun writeToDos(dos: DataOutputStream) {
        dos.writeDouble(power)
        dos.writeInt(temp)
        dos.writeInt(capacity)
    }
    companion object {
        fun readFromDis(dis: DataInputStream): NotificationInfo {
            val power = dis.readDouble()
            val temp = dis.readInt()
            val capacity = dis.readInt()
            return NotificationInfo(power, temp, capacity)
        }
    }
}
