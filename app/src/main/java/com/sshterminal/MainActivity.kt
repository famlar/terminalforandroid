package com.sshterminal

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 主 Activity — SSH 终端 UI
 *
 * 提供连接面板、终端输出显示和命令输入栏。
 * 所有网络和 IO 操作在后台协程中执行。
 */
class MainActivity : AppCompatActivity() {

    // UI 组件
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var connectButton: Button
    private lateinit var terminalOutput: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var commandInput: EditText
    private lateinit var sendButton: Button

    // 核心逻辑
    private val terminalSession = TerminalSession()
    private var displayJob: Job? = null
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }

    private fun bindViews() {
        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        userInput = findViewById(R.id.userInput)
        passwordInput = findViewById(R.id.passwordInput)
        connectButton = findViewById(R.id.connectButton)
        terminalOutput = findViewById(R.id.terminalOutput)
        scrollView = findViewById(R.id.scrollView)
        commandInput = findViewById(R.id.commandInput)
        sendButton = findViewById(R.id.sendButton)

        // 设置默认值以便快速测试
        hostInput.setText("192.168.1.1")
        portInput.setText("22")
    }

    private fun setupListeners() {
        connectButton.setOnClickListener {
            if (isConnected) {
                disconnect()
            } else {
                connect()
            }
        }

        sendButton.setOnClickListener {
            sendCommand()
        }

        commandInput.setOnEditorActionListener { _, _, _ ->
            sendCommand()
            true
        }
    }

    private fun connect() {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull() ?: 22
        val username = userInput.text.toString().trim()
        val password = passwordInput.text.toString()

        if (host.isEmpty() || username.isEmpty()) {
            Toast.makeText(this, "请输入主机和用户名", Toast.LENGTH_SHORT).show()
            return
        }

        connectButton.isEnabled = false
        connectButton.text = "Connecting..."
        appendOutput("正在连接 $username@$host:$port ...\n")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                terminalSession.startSession(host, port, username, password)
                withContext(Dispatchers.Main) {
                    isConnected = true
                    connectButton.text = "Disconnect"
                    connectButton.isEnabled = true
                    appendOutput("✓ 连接成功！\n")
                }
                // 启动终端输出读取
                startDisplayReader()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    connectButton.isEnabled = true
                    connectButton.text = "Connect"
                    appendOutput("✗ 连接失败: ${e.message}\n")
                }
            }
        }
    }

    private fun disconnect() {
        displayJob?.cancel()
        lifecycleScope.launch {
            terminalSession.disconnect()
            isConnected = false
            connectButton.text = "Connect"
            appendOutput("--- 已断开连接 ---\n")
        }
    }

    /**
     * 在后台读取终端输出并显示到 UI
     */
    private fun startDisplayReader() {
        displayJob?.cancel()
        displayJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val reader = BufferedReader(
                    InputStreamReader(terminalSession.getDisplayStream())
                )
                val buffer = CharArray(4096)
                var charsRead: Int

                while (isActive) {
                    if (reader.ready()) {
                        charsRead = reader.read(buffer)
                        if (charsRead > 0) {
                            val text = String(buffer, 0, charsRead)
                            withContext(Dispatchers.Main) {
                                appendOutput(text)
                            }
                        }
                    } else {
                        delay(50) // 避免忙等
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 向本地终端写入命令（数据会通过 SSH 传到远程）
     */
    private fun sendCommand() {
        if (!isConnected) {
            Toast.makeText(this, "未连接", Toast.LENGTH_SHORT).show()
            return
        }
        val cmd = commandInput.text.toString()
        if (cmd.isBlank()) return

        // 将命令 + 换行符写入本地终端
        terminalSession.keyboardToLocal((cmd + "\n").toByteArray())
        commandInput.text.clear()
    }

    /**
     * 追加文本到终端输出并自动滚动到底部
     */
    private fun appendOutput(text: String) {
        terminalOutput.append(text)
        // 自动滚动到底部
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
