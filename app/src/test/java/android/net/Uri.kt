package android.net

import java.io.File

/**
 * 测试替身：`android.net.Uri`（对齐 AGP mockable jar 语义——方法返回占位值，不抛异常）。
 *
 * 存在原因：[com.tvmonitor.playback.SourceUriResolver] 各解析器的 `resolve()` 成功路径
 * 需要构造 `ResolvedMedia(uri = Uri.parse(...))`，而 JVM 单测类路径不含 android.jar。
 * 本替身仅覆盖解析器实际用到的三个静态工厂（parse / encode / fromFile）。
 *
 * 边界说明：SourceUriResolverTest 只断言**失败路径**（协议不支持等），不依赖真实 Uri 行为；
 * 成功路径（ Uri 内容正确性）由真机测试矩阵 M3/M10 覆盖。
 */
object Uri {

    /** 占位解析：返回单例即可（测试不断言其内容）。 */
    @JvmStatic
    fun parse(uriString: String): Uri = this

    /** 占位编码：原样返回（测试 URL 不含需要转义的特殊字符）。 */
    @JvmStatic
    fun encode(s: String): String = s

    /** 占位构造：返回单例即可。 */
    @JvmStatic
    fun fromFile(file: File): Uri = this
}
