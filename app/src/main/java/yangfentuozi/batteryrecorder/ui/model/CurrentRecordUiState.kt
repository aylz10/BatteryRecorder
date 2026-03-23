package yangfentuozi.batteryrecorder.ui.model

import yangfentuozi.batteryrecorder.data.history.HistoryRecord
import yangfentuozi.batteryrecorder.shared.data.BatteryStatus

/**
 * 首页当前记录卡片展示状态。
 *
 * 该状态只描述当前记录卡片自身，不承载场景统计或预测结果。
 */
data class CurrentRecordUiState(
    val recordsFileName: String? = null,
    val displayStatus: BatteryStatus = BatteryStatus.Unknown,
    val isSwitching: Boolean = false,
    val record: HistoryRecord? = null,
    val livePoints: List<Long> = emptyList(),
    val lastTemp: Int = 0
)

/**
 * 首页实时采样事件。
 *
 * @param power 原始功率采样值。
 * @param status 当前电池状态。
 * @param temp 当前电池温度（0.1°C）。
 */
data class LiveRecordSample(
    val power: Long,
    val status: BatteryStatus,
    val temp: Int
)
