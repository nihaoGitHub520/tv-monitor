// 根构建脚本：仅统一声明插件版本（apply false），具体应用到 :app 模块
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
