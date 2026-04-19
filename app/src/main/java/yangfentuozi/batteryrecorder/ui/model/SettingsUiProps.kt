package yangfentuozi.batteryrecorder.ui.model

/**
 * 设置页分组组件共用的参数聚合。
 *
 * 它只负责把设置页当前展示状态、交互回调和服务连接状态打包后向下传递，
 * 方便各个 section 复用同一套入参；它不是配置真值源，也不承担持久化语义。
 */
data class SettingsUiProps(
    val state: SettingsUiState,
    val actions: SettingsActions,
    val serviceConnected: Boolean
)
