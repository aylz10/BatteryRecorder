package yangfentuozi.batteryrecorder.data.history

import android.content.Context
import yangfentuozi.batteryrecorder.R
import yangfentuozi.batteryrecorder.appString
import yangfentuozi.batteryrecorder.shared.config.dataclass.StatisticsSettings
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "SceneStatsComputer"
private const val MIN_HOME_CURRENT_SESSION_MS = 10 * 60 * 1000L
private const val MIN_HOME_CURRENT_SESSION_SOC_DROP = 2.0

/**
 * 场景统计结果。
 *
 * 同一个模型同时承载展示口径与预测口径：
 * 展示口径保留有符号平均功率用于 UI 显示方向，预测口径保留加权时长与绝对值能量用于续航计算。
 * 因此 effective 时长字段在展示口径实例中会回填为原始时长，统一下游消费与缓存结构。
 */
data class SceneStats(
    val screenOffAvgPowerRaw: Double,
    val screenOffTotalMs: Long,
    val screenOffEffectiveTotalMs: Double,
    val screenOnDailyAvgPowerRaw: Double,
    val screenOnDailyTotalMs: Long,
    val screenOnDailyEffectiveTotalMs: Double,
    val totalEnergyRawMs: Double,
    val totalSocDrop: Double,
    val totalDurationMs: Long,
    val fileCount: Int,
    val rawTotalSocDrop: Double
) {
    override fun toString(): String =
        "$screenOffAvgPowerRaw,$screenOffTotalMs,$screenOffEffectiveTotalMs," +
                "$screenOnDailyAvgPowerRaw,$screenOnDailyTotalMs,$screenOnDailyEffectiveTotalMs," +
                "$totalEnergyRawMs,$totalSocDrop,$totalDurationMs,$fileCount,$rawTotalSocDrop"

    companion object {
        fun fromString(s: String): SceneStats? {
            val p = s.split(",")
            if (p.size != 11) return null

            val offTotalMs = p[1].toLongOrNull() ?: return null
            val dailyTotalMs = p[4].toLongOrNull() ?: return null
            return SceneStats(
                screenOffAvgPowerRaw = p[0].toDoubleOrNull() ?: return null,
                screenOffTotalMs = offTotalMs,
                screenOffEffectiveTotalMs = p[2].toDoubleOrNull() ?: return null,
                screenOnDailyAvgPowerRaw = p[3].toDoubleOrNull() ?: return null,
                screenOnDailyTotalMs = dailyTotalMs,
                screenOnDailyEffectiveTotalMs = p[5].toDoubleOrNull() ?: return null,
                totalEnergyRawMs = p[6].toDoubleOrNull() ?: return null,
                totalSocDrop = p[7].toDoubleOrNull() ?: return null,
                totalDurationMs = p[8].toLongOrNull() ?: return null,
                fileCount = p[9].toIntOrNull() ?: return null,
                rawTotalSocDrop = p[10].toDoubleOrNull() ?: return null
            )
        }
    }
}

/**
 * displayStats 面向 UI 展示，predictionStats 面向首页预测使用的场景平均功率。
 */
data class SceneComputeResult(
    val displayStats: SceneStats?,
    val predictionStats: SceneStats?,
    val homePredictionInputs: HomePredictionInputs?
)

object SceneStatsComputer {

    private enum class SceneBucket {
        ScreenOff,
        ScreenOnDaily,
        Game
    }

    private data class FileKInput(
        val k: Double,
        val weight: Double
    )

    private data class CategorizedInterval(
        val interval: DischargeInterval,
        val bucket: SceneBucket
    )

    private data class FileNonGameContribution(
        val fileName: String,
        val rawDurationMs: Long,
        val rawCapDrop: Double,
        val effectiveDurationMs: Double,
        val effectiveEnergy: Double,
        val effectiveCapDrop: Double
    )

    private data class HomePredictionContributionResult(
        val contributedToTotals: Boolean = false,
        val totalDurationMs: Long = 0L,
        val totalEnergy: Double = 0.0,
        val totalSocDrop: Double = 0.0,
        val rawTotalSocDrop: Double = 0.0,
        val currentEffectiveMs: Double? = null,
        val currentK: Double? = null,
        val historicalKEntry: FileKInput? = null
    )

    /**
     * 单个放电文件对场景统计的贡献。
     *
     * 这里同时承载展示口径和首页预测场景口径的聚合结果，避免主流程散落多组并行局部变量。
     */
    private data class FileSceneContribution(
        val rawSignedOffEnergy: Double = 0.0,
        val offTime: Long = 0L,
        val rawSignedDailyEnergy: Double = 0.0,
        val dailyTime: Long = 0L,
        val rawSignedGameEnergy: Double = 0.0,
        val gameTime: Long = 0L,
        val rawTotalCapDrop: Double = 0.0,
        val effectiveOffEnergy: Double = 0.0,
        val effectiveOffTimeWeighted: Double = 0.0,
        val effectiveDailyEnergy: Double = 0.0,
        val effectiveDailyTimeWeighted: Double = 0.0,
        val effectiveGameEnergy: Double = 0.0,
        val effectiveGameTimeWeighted: Double = 0.0,
        val effectiveTotalCapDrop: Double = 0.0
    ) {
        // 这是个语法糖
        operator fun plus(other: FileSceneContribution): FileSceneContribution =
            FileSceneContribution(
                rawSignedOffEnergy = rawSignedOffEnergy + other.rawSignedOffEnergy,
                offTime = offTime + other.offTime,
                rawSignedDailyEnergy = rawSignedDailyEnergy + other.rawSignedDailyEnergy,
                dailyTime = dailyTime + other.dailyTime,
                rawSignedGameEnergy = rawSignedGameEnergy + other.rawSignedGameEnergy,
                gameTime = gameTime + other.gameTime,
                rawTotalCapDrop = rawTotalCapDrop + other.rawTotalCapDrop,
                effectiveOffEnergy = effectiveOffEnergy + other.effectiveOffEnergy,
                effectiveOffTimeWeighted = effectiveOffTimeWeighted + other.effectiveOffTimeWeighted,
                effectiveDailyEnergy = effectiveDailyEnergy + other.effectiveDailyEnergy,
                effectiveDailyTimeWeighted = effectiveDailyTimeWeighted + other.effectiveDailyTimeWeighted,
                effectiveGameEnergy = effectiveGameEnergy + other.effectiveGameEnergy,
                effectiveGameTimeWeighted = effectiveGameTimeWeighted + other.effectiveGameTimeWeighted,
                effectiveTotalCapDrop = effectiveTotalCapDrop + other.effectiveTotalCapDrop
            )

    }

    private data class HomePredictionTotals(
        val historicalKEntries: MutableList<FileKInput> = mutableListOf(),
        var currentNonGameEffectiveMs: Double = 0.0,
        var kSampleFileCount: Int = 0,
        var kTotalEnergy: Double = 0.0,
        var kTotalSocDrop: Double = 0.0,
        var kRawTotalSocDrop: Double = 0.0,
        var kTotalDurationMs: Long = 0L,
        var kCurrent: Double? = null
    ) {
        fun merge(contribution: HomePredictionContributionResult) {
            if (contribution.contributedToTotals) {
                kSampleFileCount += 1
                kTotalDurationMs += contribution.totalDurationMs
                kTotalEnergy += contribution.totalEnergy
                kTotalSocDrop += contribution.totalSocDrop
                kRawTotalSocDrop += contribution.rawTotalSocDrop
            }
            if (contribution.currentEffectiveMs != null) {
                currentNonGameEffectiveMs = contribution.currentEffectiveMs
                kCurrent = contribution.currentK
            }
            if (contribution.historicalKEntry != null) {
                historicalKEntries += contribution.historicalKEntry
            }
        }
    }

    private fun classifyScene(
        interval: DischargeInterval,
        gamePackages: Set<String>
    ): SceneBucket =
        when {
            !interval.isDisplayOn -> SceneBucket.ScreenOff
            interval.packageName == null || interval.packageName !in gamePackages ->
                SceneBucket.ScreenOnDaily
            else -> SceneBucket.Game
        }

    /**
     * 计算首页场景统计和首页预测输入。
     *
     * 该方法同时负责：
     * 1. 选择最近的放电文件并读取/写入缓存。
     * 2. 聚合首页展示使用的原始 scene 平均功率。
     * 3. 聚合首页预测使用的 scene 平均功率。
     * 4. 统计首页统一非游戏 K 所需的 `kBase / kCurrent / kFallback` 输入。
     * 5. 生成首页预测失败原因。
     *
     * `displayStats` 面向首页场景展示，保留原始口径；
     * `predictionStats` 只承载首页预测要用的 scene 平均功率；
     * `homePredictionInputs` 承载首页统一非游戏 K 与置信度相关输入。
     *
     * @param context 应用上下文。
     * @param request 当前统计设置。
     * @param recordIntervalMs 服务端记录间隔，用于推导采样断档阈值。
     * @param currentDischargeFileName 当前活动放电文件名；为空时表示不存在当前文件。
     * @return 同时包含展示统计、预测统计和首页预测输入的聚合结果。
     */
    fun compute(
        context: Context,
        request: StatisticsSettings,
        recordIntervalMs: Long,
        currentDischargeFileName: String? = null,
    ): SceneComputeResult {
        val files = DischargeRecordScanner.listRecentDischargeFiles(
            context = context,
            recentFileCount = request.sceneStatsRecentFileCount
        )
        if (files.isEmpty()) {
            LoggerX.d(TAG, "[预测] 场景统计无放电文件")
            return SceneComputeResult(
                displayStats = null,
                predictionStats = null,
                homePredictionInputs = buildInsufficientHomePredictionInputs(
                    request = request,
                    insufficientReason = appString(R.string.prediction_reason_no_discharge_records)
                )
            )
        }

        val cacheKey = buildCacheKey(
            files = files,
            request = request,
            recordIntervalMs = recordIntervalMs,
            currentDischargeFileName = currentDischargeFileName
        )
        val cacheFile = getSceneStatsCacheFile(context.cacheDir, cacheKey)
        LoggerX.d(
            TAG,
            "[预测] 计算场景统计: fileCount=${files.size} cache=${cacheFile.name} current=$currentDischargeFileName"
        )
        if (cacheFile.exists()) {
            val cacheLines = cacheFile.readText().trim().lines()
            val displayStats = cacheLines.getOrNull(0)?.let { SceneStats.fromString(it) }
            val predictionStats = cacheLines.getOrNull(1)?.let { SceneStats.fromString(it) }
            val homePredictionInputs = cacheLines.getOrNull(2)?.let {
                HomePredictionInputs.fromString(predictionStats, it)
            }
            if (displayStats != null && predictionStats != null && homePredictionInputs != null) {
                LoggerX.d(TAG, "[预测] 命中场景统计缓存: ${cacheFile.name}")
                return SceneComputeResult(displayStats, predictionStats, homePredictionInputs)
            }
            LoggerX.w(TAG, "[预测] 场景统计缓存损坏，删除重算: ${cacheFile.absolutePath}")
            cacheFile.delete()
        }

        var usedFileCount = 0
        var sceneTotals = FileSceneContribution()
        val homeTotals = HomePredictionTotals()
        val gamePackages = request.gamePackages

        val scanSummary = DischargeRecordScanner.scan(
            context = context,
            request = request,
            recordIntervalMs = recordIntervalMs,
            currentDischargeFileName = currentDischargeFileName
        ) { acceptedFile ->
            val (sceneContribution, homeContribution) = processAcceptedFile(
                acceptedFile = acceptedFile,
                gamePackages = gamePackages,
                currentDischargeFileName = currentDischargeFileName,
                weightingEnabled = request.predWeightedAlgorithmEnabled
            )

            usedFileCount += 1
            sceneTotals += sceneContribution
            homeTotals.merge(homeContribution)
        }

        if (usedFileCount <= 0) {
            LoggerX.w(TAG, "[预测] 场景统计无有效文件，准备返回不足原因")
            return SceneComputeResult(
                displayStats = null,
                predictionStats = null,
                homePredictionInputs = buildInsufficientHomePredictionInputs(
                    request = request,
                    insufficientReason = buildScanFailureReason(scanSummary, recordIntervalMs)
                )
            )
        }

        val totalMs = sceneTotals.offTime + sceneTotals.dailyTime + sceneTotals.gameTime
        if (totalMs <= 0L) {
            LoggerX.w(
                TAG,
                "[预测] 场景统计总时长无效: off=${sceneTotals.offTime} daily=${sceneTotals.dailyTime} game=${sceneTotals.gameTime}"
            )
            return SceneComputeResult(
                displayStats = null,
                predictionStats = null,
                homePredictionInputs = buildInsufficientHomePredictionInputs(
                    request = request,
                    currentNonGameEffectiveMs = homeTotals.currentNonGameEffectiveMs,
                    kSampleFileCount = homeTotals.kSampleFileCount,
                    kTotalEnergy = homeTotals.kTotalEnergy,
                    kTotalSocDrop = homeTotals.kTotalSocDrop,
                    kRawTotalSocDrop = homeTotals.kRawTotalSocDrop,
                    kTotalDurationMs = homeTotals.kTotalDurationMs,
                    insufficientReason = appString(R.string.prediction_reason_no_valid_scene_duration)
                )
            )
        }

        val rawTotalEnergy =
            sceneTotals.rawSignedOffEnergy + sceneTotals.rawSignedDailyEnergy + sceneTotals.rawSignedGameEnergy
        val effectiveTotalEnergy =
            sceneTotals.effectiveOffEnergy + sceneTotals.effectiveDailyEnergy + sceneTotals.effectiveGameEnergy

        val displayStats = SceneStats(
            screenOffAvgPowerRaw = if (sceneTotals.offTime > 0) {
                sceneTotals.rawSignedOffEnergy / sceneTotals.offTime.toDouble()
            } else {
                0.0
            },
            screenOffTotalMs = sceneTotals.offTime,
            screenOffEffectiveTotalMs = sceneTotals.offTime.toDouble(),
            screenOnDailyAvgPowerRaw = if (sceneTotals.dailyTime > 0) {
                sceneTotals.rawSignedDailyEnergy / sceneTotals.dailyTime.toDouble()
            } else {
                0.0
            },
            screenOnDailyTotalMs = sceneTotals.dailyTime,
            screenOnDailyEffectiveTotalMs = sceneTotals.dailyTime.toDouble(),
            totalEnergyRawMs = rawTotalEnergy,
            totalSocDrop = sceneTotals.rawTotalCapDrop,
            totalDurationMs = totalMs,
            fileCount = usedFileCount,
            rawTotalSocDrop = sceneTotals.rawTotalCapDrop
        )

        val predictionStats = SceneStats(
            screenOffAvgPowerRaw = if (sceneTotals.effectiveOffTimeWeighted > 0) {
                sceneTotals.effectiveOffEnergy / sceneTotals.effectiveOffTimeWeighted
            } else {
                0.0
            },
            screenOffTotalMs = sceneTotals.offTime,
            screenOffEffectiveTotalMs = sceneTotals.effectiveOffTimeWeighted,
            screenOnDailyAvgPowerRaw = if (sceneTotals.effectiveDailyTimeWeighted > 0) {
                sceneTotals.effectiveDailyEnergy / sceneTotals.effectiveDailyTimeWeighted
            } else {
                0.0
            },
            screenOnDailyTotalMs = sceneTotals.dailyTime,
            screenOnDailyEffectiveTotalMs = sceneTotals.effectiveDailyTimeWeighted,
            totalEnergyRawMs = effectiveTotalEnergy,
            totalSocDrop = sceneTotals.effectiveTotalCapDrop,
            totalDurationMs = totalMs,
            fileCount = usedFileCount,
            rawTotalSocDrop = sceneTotals.rawTotalCapDrop
        )

        val homePredictionInputs = buildHomePredictionInputs(
            request = request,
            predictionStats = predictionStats,
            historicalKEntries = homeTotals.historicalKEntries,
            currentNonGameEffectiveMs = homeTotals.currentNonGameEffectiveMs,
            kSampleFileCount = homeTotals.kSampleFileCount,
            kTotalEnergy = homeTotals.kTotalEnergy,
            kTotalSocDrop = homeTotals.kTotalSocDrop,
            kRawTotalSocDrop = homeTotals.kRawTotalSocDrop,
            kTotalDurationMs = homeTotals.kTotalDurationMs,
            kCurrent = homeTotals.kCurrent
        )

        if (homePredictionInputs.insufficientReason == null) {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(
                displayStats.toString() + "\n" +
                        predictionStats.toString() + "\n" +
                        homePredictionInputs.serializeWithoutScene()
            )
        }
        LoggerX.i(
            TAG,
            "[预测] 场景统计完成: usedFiles=$usedFileCount kSampleFiles=${homeTotals.kSampleFileCount} kBase=${homePredictionInputs.kBase} kCurrent=${homeTotals.kCurrent} kFallback=${homePredictionInputs.kFallback} kCV=${homePredictionInputs.kCV} kEffectiveN=${homePredictionInputs.kEffectiveN}"
        )

        return SceneComputeResult(
            displayStats = displayStats,
            predictionStats = predictionStats,
            homePredictionInputs = homePredictionInputs
        )
    }

    /**
     * 单文件聚合首页场景统计与首页 K 输入。
     *
     * 这里保持“先按原始口径分场景，再按首页规则决定当前文件是否启用加权”的顺序，
     * 避免首页场景统计与首页 K 输入在跨文件主流程里交错展开。
     */
    private fun processAcceptedFile(
        acceptedFile: AcceptedDischargeFile,
        gamePackages: Set<String>,
        currentDischargeFileName: String?,
        weightingEnabled: Boolean
    ): Pair<FileSceneContribution, HomePredictionContributionResult> {
        val categorizedIntervals = acceptedFile.intervals.map { interval ->
            CategorizedInterval(
                interval = interval,
                bucket = classifyScene(interval, gamePackages)
            )
        }
        var fileRawSignedOffEnergy = 0.0
        var fileOffTime = 0L
        var fileRawSignedDailyEnergy = 0.0
        var fileDailyTime = 0L
        var fileRawSignedGameEnergy = 0.0
        var fileGameTime = 0L
        var fileNonGameRawCapDrop = 0.0

        categorizedIntervals.forEach { categorized ->
            when (categorized.bucket) {
                SceneBucket.ScreenOff -> {
                    val interval = categorized.interval
                    fileRawSignedOffEnergy += interval.signedEnergyRawMs
                    fileOffTime += interval.durationMs
                    fileNonGameRawCapDrop += interval.capDrop
                }

                SceneBucket.ScreenOnDaily -> {
                    val interval = categorized.interval
                    fileRawSignedDailyEnergy += interval.signedEnergyRawMs
                    fileDailyTime += interval.durationMs
                    fileNonGameRawCapDrop += interval.capDrop
                }

                SceneBucket.Game -> {
                    val interval = categorized.interval
                    fileRawSignedGameEnergy += interval.signedEnergyRawMs
                    fileGameTime += interval.durationMs
                }
            }
        }

        val fileNonGameRawDuration = fileOffTime + fileDailyTime
        val useHomeWeightedCurrentFile =
            weightingEnabled &&
                acceptedFile.file.name == currentDischargeFileName &&
                fileNonGameRawDuration >= MIN_HOME_CURRENT_SESSION_MS &&
                fileNonGameRawCapDrop >= MIN_HOME_CURRENT_SESSION_SOC_DROP

        var fileHomeEffectiveOffEnergy = 0.0
        var fileHomeEffectiveOffTime = 0.0
        var fileHomeEffectiveDailyEnergy = 0.0
        var fileHomeEffectiveDailyTime = 0.0
        var fileHomeEffectiveGameEnergy = 0.0
        var fileHomeEffectiveGameTime = 0.0
        var fileHomeEffectiveGameCapDrop = 0.0
        var fileHomeEffectiveNonGameCapDrop = 0.0

        categorizedIntervals.forEach { categorized ->
            val interval = categorized.interval
            val homeWeight = if (useHomeWeightedCurrentFile) interval.timeDecayWeight else 1.0
            val homeEffectiveDuration = interval.durationMs.toDouble() * homeWeight
            val homeEffectiveEnergy = abs(interval.signedEnergyRawMs) * homeWeight
            val homeEffectiveCapDrop = interval.capDrop * homeWeight
            when (categorized.bucket) {
                SceneBucket.ScreenOff -> {
                    fileHomeEffectiveOffEnergy += homeEffectiveEnergy
                    fileHomeEffectiveOffTime += homeEffectiveDuration
                    fileHomeEffectiveNonGameCapDrop += homeEffectiveCapDrop
                }

                SceneBucket.ScreenOnDaily -> {
                    fileHomeEffectiveDailyEnergy += homeEffectiveEnergy
                    fileHomeEffectiveDailyTime += homeEffectiveDuration
                    fileHomeEffectiveNonGameCapDrop += homeEffectiveCapDrop
                }

                SceneBucket.Game -> {
                    fileHomeEffectiveGameEnergy += homeEffectiveEnergy
                    fileHomeEffectiveGameTime += homeEffectiveDuration
                    fileHomeEffectiveGameCapDrop += homeEffectiveCapDrop
                }
            }
        }

        val sceneContribution = FileSceneContribution(
            rawSignedOffEnergy = fileRawSignedOffEnergy,
            offTime = fileOffTime,
            rawSignedDailyEnergy = fileRawSignedDailyEnergy,
            dailyTime = fileDailyTime,
            rawSignedGameEnergy = fileRawSignedGameEnergy,
            gameTime = fileGameTime,
            rawTotalCapDrop = acceptedFile.rawTotalCapDrop,
            effectiveOffEnergy = fileHomeEffectiveOffEnergy,
            effectiveOffTimeWeighted = fileHomeEffectiveOffTime,
            effectiveDailyEnergy = fileHomeEffectiveDailyEnergy,
            effectiveDailyTimeWeighted = fileHomeEffectiveDailyTime,
            effectiveGameEnergy = fileHomeEffectiveGameEnergy,
            effectiveGameTimeWeighted = fileHomeEffectiveGameTime,
            effectiveTotalCapDrop = fileHomeEffectiveNonGameCapDrop + fileHomeEffectiveGameCapDrop
        )
        val homeContribution = collectHomePredictionContribution(
            contribution = FileNonGameContribution(
                fileName = acceptedFile.file.name,
                rawDurationMs = fileNonGameRawDuration,
                rawCapDrop = fileNonGameRawCapDrop,
                effectiveDurationMs = fileHomeEffectiveOffTime + fileHomeEffectiveDailyTime,
                effectiveEnergy = fileHomeEffectiveOffEnergy + fileHomeEffectiveDailyEnergy,
                effectiveCapDrop = fileHomeEffectiveNonGameCapDrop
            ),
            currentDischargeFileName = currentDischargeFileName
        )
        return sceneContribution to homeContribution
    }

    /**
     * 用跨文件累计结果组装首页预测输入。
     *
     * 这里统一收口 `kBase / kCurrent / kFallback` 与置信度相关字段，
     * 保持首页预测输入的派生逻辑集中在同一处。
     */
    private fun buildHomePredictionInputs(
        request: StatisticsSettings,
        predictionStats: SceneStats,
        historicalKEntries: List<FileKInput>,
        currentNonGameEffectiveMs: Double,
        kSampleFileCount: Int,
        kTotalEnergy: Double,
        kTotalSocDrop: Double,
        kRawTotalSocDrop: Double,
        kTotalDurationMs: Long,
        kCurrent: Double?
    ): HomePredictionInputs {
        val kEntries = historicalKEntries.map { it.k to it.weight }
        val kFallback = if (kTotalEnergy > 0.0 && kTotalSocDrop > 0.0) {
            kTotalSocDrop / kTotalEnergy
        } else {
            null
        }
        return HomePredictionInputs(
            sceneStats = predictionStats,
            weightingEnabled = request.predWeightedAlgorithmEnabled,
            alphaMax = request.predWeightedAlgorithmAlphaMaxX100 / 100.0,
            kBase = weightedMedian(kEntries),
            kCurrent = kCurrent,
            kFallback = kFallback,
            currentNonGameEffectiveMs = currentNonGameEffectiveMs,
            kSampleFileCount = kSampleFileCount,
            kTotalEnergy = kTotalEnergy,
            kTotalSocDrop = kTotalSocDrop,
            kRawTotalSocDrop = kRawTotalSocDrop,
            kTotalDurationMs = kTotalDurationMs,
            kCV = weightedCV(kEntries),
            kEffectiveN = effectiveSampleCount(kEntries),
            insufficientReason = if (kSampleFileCount <= 0) {
                appString(R.string.prediction_reason_only_excluded_high_load)
            } else {
                null
            }
        )
    }

    /**
     * 统一构造首页预测不足态。
     *
     * 当前约束是：不足态仍沿用 `HomePredictionInputs` 作为返回模型，
     * 由这里集中填充默认字段，避免各个提前返回分支重复拼装。
     */
    private fun buildInsufficientHomePredictionInputs(
        request: StatisticsSettings,
        insufficientReason: String,
        currentNonGameEffectiveMs: Double = 0.0,
        kSampleFileCount: Int = 0,
        kTotalEnergy: Double = 0.0,
        kTotalSocDrop: Double = 0.0,
        kRawTotalSocDrop: Double = 0.0,
        kTotalDurationMs: Long = 0L
    ): HomePredictionInputs =
        HomePredictionInputs(
            sceneStats = null,
            weightingEnabled = request.predWeightedAlgorithmEnabled,
            alphaMax = request.predWeightedAlgorithmAlphaMaxX100 / 100.0,
            kBase = null,
            kCurrent = null,
            kFallback = null,
            currentNonGameEffectiveMs = currentNonGameEffectiveMs,
            kSampleFileCount = kSampleFileCount,
            kTotalEnergy = kTotalEnergy,
            kTotalSocDrop = kTotalSocDrop,
            kRawTotalSocDrop = kRawTotalSocDrop,
            kTotalDurationMs = kTotalDurationMs,
            kCV = null,
            kEffectiveN = 0.0,
            insufficientReason = insufficientReason
        )

    /**
     * 将单个文件的非游戏贡献转换为首页预测输入增量。
     *
     * 当前文件和历史文件在这里分流：
     * - 所有有贡献的文件都会进入首页统一非游戏 totals。
     * - 当前文件只产出 `currentEffectiveMs` 和 `currentK`，不进入历史 `kBase` 样本。
     * - 历史文件只有在掉电达到 3% 且 K 可计算时，才进入 `kBase` 的文件级样本。
     *
     * @param contribution 单个文件的非游戏聚合结果。
     * @param currentDischargeFileName 当前活动放电文件名。
     * @return 当前文件或历史文件对首页预测输入产生的增量结果。
     */
    private fun collectHomePredictionContribution(
        contribution: FileNonGameContribution,
        currentDischargeFileName: String?,
    ): HomePredictionContributionResult {
        val hasContribution = contribution.rawDurationMs > 0L && contribution.effectiveEnergy > 0.0
        if (!hasContribution) {
            return if (contribution.fileName == currentDischargeFileName) {
                HomePredictionContributionResult(
                    currentEffectiveMs = 0.0,
                    currentK = null
                )
            } else {
                HomePredictionContributionResult()
            }
        }

        val fileK = if (contribution.effectiveCapDrop > 0.0) {
            contribution.effectiveCapDrop / contribution.effectiveEnergy
        } else {
            null
        }
        if (contribution.fileName == currentDischargeFileName) {
            return HomePredictionContributionResult(
                contributedToTotals = true,
                totalDurationMs = contribution.rawDurationMs,
                totalEnergy = contribution.effectiveEnergy,
                totalSocDrop = contribution.effectiveCapDrop,
                rawTotalSocDrop = contribution.rawCapDrop,
                currentEffectiveMs = contribution.effectiveDurationMs,
                currentK = fileK
            )
        }
        return HomePredictionContributionResult(
            contributedToTotals = true,
            totalDurationMs = contribution.rawDurationMs,
            totalEnergy = contribution.effectiveEnergy,
            totalSocDrop = contribution.effectiveCapDrop,
            rawTotalSocDrop = contribution.rawCapDrop,
            historicalKEntry = if (fileK != null && contribution.effectiveCapDrop >= 3.0) {
                FileKInput(
                    k = fileK,
                    weight = contribution.effectiveCapDrop
                )
            } else {
                null
            }
        )
    }

    /**
     * 生成扫描阶段失败时的首页预测原因文案。
     *
     * @param summary 放电扫描摘要；为空表示没有任何最近放电文件。
     * @param recordIntervalMs 当前服务端记录间隔。
     * @return 展示给首页预测卡片的失败原因。
     */
    private fun buildScanFailureReason(
        summary: DischargeScanSummary?,
        recordIntervalMs: Long
    ): String {
        if (summary == null || summary.selectedFileCount <= 0) {
            return appString(R.string.prediction_reason_no_discharge_records)
        }

        val selected = summary.selectedFileCount
        if (summary.rejectedAbnormalDrainRateCount == selected) {
            return appString(R.string.prediction_reason_abnormal_rate_filtered, selected)
        }
        if (summary.rejectedNoValidDurationCount == selected) {
            val maxGapMs = DischargeRecordScanner.computeMaxGapMs(recordIntervalMs)
            return appString(R.string.prediction_reason_no_valid_interval, selected, maxGapMs)
        }
        if (summary.rejectedNoSocDropCount == selected) {
            return appString(R.string.prediction_reason_no_valid_capacity, selected)
        }
        if (summary.rejectedNoEnergyCount == selected) {
            return appString(R.string.prediction_reason_latest_files_no_power, selected)
        }

        val rejected = selected - summary.acceptedFileCount
        return appString(
            R.string.prediction_reason_partial_filtered,
            summary.acceptedFileCount,
            selected,
            rejected
        )
    }

    /**
     * 构造场景统计缓存 key。
     *
     * key 需要同时反映：
     * - 参与统计的文件快照
     * - 首页高负载排除列表
     * - 首页样本次数与采样断档阈值
     * - 首页加权算法相关设置
     * - 当前活动放电文件
     *
     * @param files 当前参与统计的文件列表。
     * @param request 当前统计设置。
     * @param recordIntervalMs 服务端记录间隔。
     * @param currentDischargeFileName 当前活动放电文件名。
     * @return 场景统计缓存 key。
     */
    private fun buildCacheKey(
        files: List<File>,
        request: StatisticsSettings,
        recordIntervalMs: Long,
        currentDischargeFileName: String?,
    ): String {
        val filesHash = files.joinToString(",") { "${it.name}:${it.lastModified()}:${it.length()}" }
            .hashCode()
        val gamesHash = request.gamePackages.sorted().joinToString(",").hashCode()
        val currentNameHash = (currentDischargeFileName ?: "").hashCode()
        return listOf(
            HISTORY_STATS_CACHE_VERSION,
            filesHash,
            gamesHash,
            request.sceneStatsRecentFileCount,
            DischargeRecordScanner.computeMaxGapMs(recordIntervalMs),
            request.predWeightedAlgorithmEnabled.hashCode(),
            request.predWeightedAlgorithmAlphaMaxX100,
            currentNameHash
        ).joinToString("_")
    }

    /** 加权变异系数 CV = σ_weighted / μ_weighted。 */
    private fun weightedCV(entries: List<Pair<Double, Double>>): Double? {
        if (entries.size < 2) return null
        val sumW = entries.sumOf { it.second }
        if (sumW <= 0) return null
        val kMean = entries.sumOf { it.first * it.second } / sumW
        if (kMean <= 0 || !kMean.isFinite()) return null
        val variance =
            entries.sumOf { it.second * (it.first - kMean) * (it.first - kMean) } / sumW
        if (!variance.isFinite()) return null
        return sqrt(variance) / kMean
    }

    /** 加权有效样本量 n_eff = (Σw)^2 / Σ(w^2)。 */
    private fun effectiveSampleCount(entries: List<Pair<Double, Double>>): Double {
        if (entries.isEmpty()) return 0.0
        val sumW = entries.sumOf { it.second }
        val sumW2 = entries.sumOf { it.second * it.second }
        if (sumW2 <= 0) return 0.0
        return sumW * sumW / sumW2
    }

    /** 加权中位数：按 k 升序累积权重到 50% 时线性插值。 */
    private fun weightedMedian(entries: List<Pair<Double, Double>>): Double? {
        if (entries.size < 2) return null
        val sorted = entries.sortedBy { it.first }
        val totalWeight = sorted.sumOf { it.second }
        if (totalWeight <= 0) return null
        val halfWeight = totalWeight * 0.5
        var cumulative = 0.0
        for (i in sorted.indices) {
            val prev = cumulative
            cumulative += sorted[i].second
            if (cumulative >= halfWeight) {
                if (i == 0 || prev >= halfWeight) return sorted[i].first
                val fraction = (halfWeight - prev) / sorted[i].second
                return sorted[i - 1].first +
                        (sorted[i].first - sorted[i - 1].first) * fraction
            }
        }
        return sorted.last().first
    }
}
