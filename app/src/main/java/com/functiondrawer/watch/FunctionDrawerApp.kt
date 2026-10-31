package com.functiondrawer.watch

import android.app.Application

/**
 * 应用入口（**只做一件事：尽早装崩溃黑匣子**）。
 *
 * 为什么需要它，而不是在 `MainActivity.onCreate` 里装：
 *
 * `MainActivity.onCreate` 的第一行之前还有一段路 —— 进程启动、`Application.onCreate`、
 * 静态初始化。我们这一轮踩的正是"点图标立刻退回桌面"这种崩溃，而它完全可能发生在
 * Activity 被创建之前（比如某个 `object` 的静态初始化里）。只装在 Activity 里，
 * 那类崩溃就抓不到，屏幕上一片空白，什么线索都没有。
 *
 * 所以把它提前到 `Application.onCreate` —— 这是应用代码能执行到的**最早**一点。
 *
 * ⚠️ 这里**不要**加任何别的东西：不做初始化框架、不建线程池、不读设置。
 * 这一层一旦自己崩了，整套黑匣子就跟着失效（连它自己都记不下来）。
 */
class FunctionDrawerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
