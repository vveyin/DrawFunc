# FunctionDrawer 的 R8 规则。
#
# 目前是空的 —— 这是有意的：Compose + Kotlin 标准库在 AGP 8.5 / R8 下不需要任何
# 额外 keep 规则，实测 :app:assembleRelease 能正常出包（1.58 MB）并压缩资源。
#
# 什么时候需要往这里加东西：
#   * 引入反射 / 序列化库（JSON、Room、Retrofit）时
#   * 引入依赖注入框架时
#   * 引入 JNI 时（native 方法名不能被混淆）
-keepattributes SourceFile,LineNumberTable