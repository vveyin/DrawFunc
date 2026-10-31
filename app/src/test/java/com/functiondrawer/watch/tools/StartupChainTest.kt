package com.functiondrawer.watch.tools

import com.functiondrawer.watch.ui.SettingsFields

import org.junit.Assert.*
import org.junit.Test

/**
 * 启动链路冒烟：存档那一行 → SettingsFields → WatchSettings 里那几个"新字段"。
 *
 * 为什么单独写：`MainActivity.onCreate` 会先读一次设置（`loadCrownPrefs`），
 * `GraphScreen` 的 `remember` 里又会读一次完整设置。这两处**任何一处抛异常
 * 都是"点图标立刻退回桌面"**，而在设备上完全看不到是哪一行挂的。
 * 这里把"读设置"这一步在 JVM 上真实跑一遍（不碰 Android API），
 * 至少保证字段解码、枚举映射、默认值补齐三件事不会把启动带崩。
 */
class StartupChainTest {

    @Test
    fun `全新安装：没有存档时读出来的是默认设置，且新字段有值`() {
        val f = SettingsFields.decode(null, coordinateSystemCount = 3)
        assertTrue("模糊背景默认开", f.blurBackdrop)
        assertEquals("主题风格默认 Material", 0, f.themeStyle)
    }

    @Test
    fun `每一代老存档都能读出设置，不会抛异常`() {
        // 从四段（最老）一路到 15 段（本版本）全部过一遍。
        val lines = listOf(
            "0,1,0,1",
            "0,1,1,0,0,0,1",
            "0,1,1,0,0,0,1,0,1",
            "0,1,1,0,1,1,0,1,0,1,1,1,55",
            "0,1,1,0,1,1,0,1,0,1,0,0,10,1",
            "0,1,1,0,1,1,0,1,0,1,0,0,10,1,0"
        )
        lines.forEach { line ->
            val f = SettingsFields.decode(line, coordinateSystemCount = 3)
            // 枚举越界必须被 getOrNull 兜住（否则就是 ArrayIndexOutOfBounds → 启动崩）
            assertTrue(f.themeStyle >= 0)
            assertTrue(f.crownMode >= 0)
            assertTrue("灵敏度必须在可乘范围内", f.crownSpeedTenths in 1..100 || f.crownSpeedTenths == 10)
        }
    }

    @Test
    fun `坏存档不抛异常，整组退回默认`() {
        assertTrue(SettingsFields.decode("", 3).blurBackdrop)
        assertTrue(SettingsFields.decode("x,y,z,w", 3).blurBackdrop)
        assertTrue(SettingsFields.decode("99,1,1,1", 3).blurBackdrop)
    }

    @Test
    fun `新字段在两个方向都能编码解码`() {
        val off = SettingsFields(themeStyle = 1, blurBackdrop = false)
        val offBack = SettingsFields.decode(off.encode(), coordinateSystemCount = 3)
        assertEquals(1, offBack.themeStyle)
        assertFalse("关掉的模糊背景读回来必须还是关的", offBack.blurBackdrop)

        val on = SettingsFields(themeStyle = 0, blurBackdrop = true)
        val onBack = SettingsFields.decode(on.encode(), coordinateSystemCount = 3)
        assertEquals(0, onBack.themeStyle)
        assertTrue(onBack.blurBackdrop)
    }
}
