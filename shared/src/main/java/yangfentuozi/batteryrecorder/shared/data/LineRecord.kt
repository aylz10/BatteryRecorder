package yangfentuozi.batteryrecorder.shared.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class LineRecord(
    val timestamp: Long, val power: Long, val packageName: String?,
    val capacity: Int, val isDisplayOn: Int, val status: BatteryStatus?,
    val temp: Int, val voltage: Long, val current: Long
) : Parcelable {
    override fun toString(): String {
        return "$timestamp,$power,$packageName,$capacity,$isDisplayOn,$temp,$voltage,$current"
    }

    companion object {
        fun fromString(line: String) : LineRecord? =
            fromParts(line.split(","))

        internal fun fromParts(parts: List<String>) : LineRecord? {
            // 当前记录文件格式固定为 8 列；旧格式不再兼容，避免缺列数据被误解释。
            if (parts.size < 8) return null

            val timestamp = parts[0].toLongOrNull() ?: return null
            val power = parts[1].toLongOrNull() ?: return null
            val packageName = parts[2]
            val capacity = parts[3].toIntOrNull() ?: return null
            val isDisplayOn = parts[4].toIntOrNull() ?: return null

            val temp = parts[5].toIntOrNull() ?: return null
            val voltage = parts[6].toLongOrNull() ?: return null
            val current = parts[7].toLongOrNull() ?: return null
            return LineRecord(
                timestamp, power, packageName, capacity, isDisplayOn, null, temp, voltage, current
            )
        }
    }
}
