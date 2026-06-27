package com.sshterminal

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主 Activity — WinUI3 风格标签页终端
 *
 * 顶部: TabBar (Windows Terminal 风格标签栏)
 * 下方: TabContentHost (标签页内容区，管理多个 TerminalView)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tabBar: TabBar
    private lateinit var contentHost: TabContentHost

    /** 所有标签页 */
    private val tabs = mutableListOf<TerminalTab>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabBar = findViewById(R.id.tabBar)
        contentHost = findViewById(R.id.contentHost)

        setupTabBar()
        setupContentHost()

        // 启动时默认打开一个本地 Termux 标签页
        addLocalTab()
    }

    // ========== TabBar 事件 ==========

    private fun setupTabBar() {
        tabBar.onTabSelected = { index ->
            val tab = tabs.getOrNull(index) ?: return@onTabSelected
            contentHost.switchToTab(tab.id)
        }

        tabBar.onTabClosed = { index ->
            closeTab(index)
        }

        tabBar.onAddTab = {
            showNewTabDialog()
        }
    }

    private fun setupContentHost() {
        // TerminalView 的键盘输入通过 TabContentHost 路由到对应会话
    }

    // ========== 标签页管理 ==========

    private fun addLocalTab() {
        val session = LocalTerminalSession()
        val tab = TerminalTab(
            id = TerminalTab.newId(),
            title = "termux",
            subtitle = "local",
            tabType = TabType.LOCAL,
            session = session,
            accentColor = TerminalTab.ACCENT_COLORS[tabs.size % TerminalTab.ACCENT_COLORS.size]
        )

        // 先创建 View，再注册标签（避免 switchToTab 时 View 不存在）
        val view = contentHost.getOrCreateView(tab)
        contentHost.addTabView(tab.id, view)

        tabs.add(tab)
        tabBar.addTab(tab)

        lifecycleScope.launch {
            try {
                session.start(cols = 80, rows = 24)
                withContext(Dispatchers.Main) {
                    tab.state = TabState.CONNECTED
                    tabBar.updateTabState(tabs.indexOf(tab), TabState.CONNECTED)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tab.state = TabState.DISCONNECTED
                    tabBar.updateTabState(tabs.indexOf(tab), TabState.DISCONNECTED)
                    Toast.makeText(this@MainActivity,
                        "Termux 启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun addSshTab(host: String, port: Int, username: String, password: String) {
        val session = SshTerminalSession()
        val title = "$username@$host"
        val tab = TerminalTab(
            id = TerminalTab.newId(),
            title = title,
            subtitle = "SSH",
            tabType = TabType.SSH,
            session = session,
            accentColor = TerminalTab.ACCENT_COLORS[tabs.size % TerminalTab.ACCENT_COLORS.size]
        )

        // 先创建 View，再注册标签
        val view = contentHost.getOrCreateView(tab)
        contentHost.addTabView(tab.id, view)

        tabs.add(tab)
        tabBar.addTab(tab)

        lifecycleScope.launch {
            try {
                session.connect(host, port, username, password)
                withContext(Dispatchers.Main) {
                    tab.state = TabState.CONNECTED
                    tabBar.updateTabState(tabs.indexOf(tab), TabState.CONNECTED)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tab.state = TabState.DISCONNECTED
                    tabBar.updateTabState(tabs.indexOf(tab), TabState.DISCONNECTED)
                    Toast.makeText(this@MainActivity,
                        "SSH 连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return

        // 断开会话
        lifecycleScope.launch {
            tab.session.disconnect()
        }

        // 移除 UI
        contentHost.removeViewForTab(tab.id)
        tabs.removeAt(index)
        tabBar.removeTab(index)

        // 如果所有标签都关闭了，退出
        if (tabs.isEmpty()) {
            finish()
        }
    }

    // ========== 新建标签对话框 ==========

    private fun showNewTabDialog() {
        val options = arrayOf("Termux 本地终端", "SSH 远程连接")
        AlertDialog.Builder(this)
            .setTitle("新建标签页")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> addLocalTab()
                    1 -> showSshDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSshDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }

        val hostInput = EditText(this).apply {
            hint = "主机 (192.168.1.1)"
            setText("192.168.1.1")
        }
        val portInput = EditText(this).apply {
            hint = "端口"
            setText("22")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val userInput = EditText(this).apply { hint = "用户名" }
        val passInput = EditText(this).apply {
            hint = "密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        container.addView(hostInput)
        container.addView(portInput)
        container.addView(userInput)
        container.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle("SSH 连接")
            .setView(container)
            .setPositiveButton("连接") { _, _ ->
                val host = hostInput.text.toString().trim()
                val port = portInput.text.toString().toIntOrNull() ?: 22
                val user = userInput.text.toString().trim()
                val pass = passInput.text.toString()

                if (host.isEmpty() || user.isEmpty()) {
                    Toast.makeText(this, "请输入主机和用户名", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                addSshTab(host, port, user, pass)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ========== 生命周期 ==========

    override fun onDestroy() {
        super.onDestroy()
        for (tab in tabs) {
            lifecycleScope.launch { tab.session.disconnect() }
        }
        contentHost.destroyAll()
    }

    override fun onBackPressed() {
        if (tabs.size <= 1) {
            super.onBackPressed()
        } else {
            // 关闭当前活动标签
            val idx = tabs.indexOfFirst { it.id == contentHost.activeTabId }
            if (idx >= 0) closeTab(idx)
        }
    }
}
