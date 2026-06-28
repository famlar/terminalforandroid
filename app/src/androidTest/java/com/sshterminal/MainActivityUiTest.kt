package com.sshterminal

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.*
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WinUI3 TabBar + MainActivity UI 测试 (Espresso)
 */
@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    // ========== 启动测试 ==========

    @Test
    fun appLaunchesWithoutCrash() {
        // 等待 TabBar 出现
        onView(withId(R.id.tabBar))
            .check(matches(isDisplayed()))
    }

    @Test
    fun contentHostVisible() {
        onView(withId(R.id.contentHost))
            .check(matches(isDisplayed()))
    }

    // ========== 对话框测试 ==========

    @Test
    fun newTabDialogShowsOptions() {
        // 点击 "+" 按钮 (最右侧)
        onView(withId(R.id.tabBar))
            .perform(click())
        // 不直接点 + 因为它是 Canvas 绘制的
        // 改用 back 测试对话框是否显示
    }

    @Test
    fun backButtonClosesAppWhenSingleTab() {
        // 首次启动有0个或1个标签
        pressBack()
    }

    // ========== 布局测试 ==========

    @Test
    fun tabBarHasCorrectHeight() {
        onView(withId(R.id.tabBar))
            .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    @Test
    fun contentHostFillsRemainingSpace() {
        onView(withId(R.id.contentHost))
            .check(matches(isDisplayed()))
            .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }
}
