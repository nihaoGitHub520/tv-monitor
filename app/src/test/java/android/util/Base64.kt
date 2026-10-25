package android.util

/**
 * JVM 单元测试专用的 [android.util.Base64] 替身（**仅存在于测试源码，不会进入 APK**）。
 *
 * 背景（QA 基建说明）：
 * - AGP 单元测试运行时所使用的 mockable android.jar 会把 `android.*` 方法体替换为
 *   “抛异常 / 返回默认值”，导致 [OnvifSoap] 的 `passwordDigest = Base64(SHA1(...))`
 *   与 Nonce 编码无法在 JVM 单测中执行；
 * - 本替身提供与 Android 语义一致的 Base64 实现（NO_WRAP / NO_PADDING），
 *   使 ONVIF WS-Security 摘要计算可在纯 JVM 环境验证；
 * - AGP 的 mockable jar 位于单元测试类路径**末尾**，测试类输出目录在其之前，
 *   因此运行时本替身优先于 mockable jar 中的同名类（已通过 AGP 8.7.3 字节码核对）；
 * - 若未来为工程引入 Robolectric，可删除本文件并改回 Robolectric 阴影实现。
 *
 * 实现说明：`java.util.Base64` 的基础编码器与 `android.util.Base64.NO_WRAP` 等价；
 * `NO_PADDING` 通过 `withoutPadding()` 支持；解码侧与 Android 一致容忍缺失填充。
 */
object Base64 {

    /** 不追加换行（与 android.util.Base64.NO_WRAP 同值）。 */
    const val NO_WRAP: Int = 2

    /** 省略 Base64 填充字符 '='（与 android.util.Base64.NO_PADDING 同值）。 */
    const val NO_PADDING: Int = 1

    /**
     * 编码为 Base64 字符串。
     *
     * @param flags 支持 [NO_WRAP] / [NO_PADDING] 组合；其余标志按 Android 语义忽略。
     */
    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        if (input.isEmpty()) return ""
        var encoder = java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) {
            encoder = encoder.withoutPadding()
        }
        return encoder.encodeToString(input)
    }

    /**
     * 解码 Base64 字符串（容忍缺失填充，与 Android 行为一致）。
     *
     * @param flags 忽略（Android 解码不消费 NO_WRAP/NO_PADDING）。
     */
    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray =
        java.util.Base64.getDecoder().decode(str)
}
