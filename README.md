# 流光自动化 · Liquid Glass Demo

安卓自动化 App 的界面 Demo：Jetpack Compose + Material 3 Expressive 视觉语言 + 液态玻璃（Liquid Glass）效果。

- 液态玻璃效果来自 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0），`backdrop/` 目录为其库源码（精简为仅安卓目标），`androidApp/.../liquid/` 下的玻璃组件改编自其示例。
- M3E 异形（扇贝、太阳花、四叶草）用 `androidx.graphics:graphics-shapes` 生成。
- 推送到 `main` 后，GitHub Actions 自动编译 APK，发布在 Releases 的 `latest` 中。

玻璃折射效果需要 Android 13 及以上，Android 12 只有模糊，更低版本降级为半透明。
