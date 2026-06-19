package com.sshterminal

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主 Activity — SSH 终端
 *
 * 连接面板 + 全屏 TerminalView
 * TerminalView 直接处理键盘输入（软键盘/物理键盘）
 * 无需独立的命令输入栏
 */
class MainActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var terminalView: TerminalView

    private val terminalSession = TerminalSession()
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isConnected) {
            lifecycleScope.launch { terminalSession.disconnect() }
        }
    }

    private fun bindViews() {
        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        userInput = findViewById(R.id.userInput)
        passwordInput = findViewById(R.id.passwordInput)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)
        terminalView = findViewById(R.id.terminalView)

        // 默认值
        hostInput.setText("192.168.1.1")
        portInput.setText("22")
    }

    private fun setupListeners() {
        connectButton.setOnClickListener {
            if (isConnected) disconnect() else connect()
        }

        // TerminalView 键盘输入直接发往 SSH
        terminalView.onKeyInput = { text ->
            if (isConnected) {
                terminalSession.writeInput(text)
            }
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

        setConnectingState(true)
        setStatus("正在连接 $username@$host:$port ...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val emulator = terminalSession.connect(host, port, username, password)

                withContext(Dispatchers.Main) {
                    // 将 TerminalEmulator 绑定到 TerminalView
                    terminalView.emulator = emulator
                    terminalView.postInvalidate()
                    terminalView.requestFocus()

                    isConnected = true
                    setConnectingState(false)
                    setStatus("已连接 $username@$host:$port  (双击回到底部)")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setConnectingState(false)
                    setStatus("连接失败: ${e.message}")
                }
            }
        }
    }

    private fun disconnect() {
        lifecycleScope.launch {
            terminalSession.disconnect()
            isConnected = false
            connectButton.text = "Connect"
            terminalView.emulator = null
            terminalView.postInvalidate()
            setStatus("已断开")
        }
    }

    private fun setConnectingState(isConnecting: Boolean) {
        connectButton.isEnabled = !isConnecting
        connectButton.text = if (isConnecting) "连接中..." else "Disconnect"
        hostInput.isEnabled = !isConnecting
        portInput.isEnabled = !isConnecting
        userInput.isEnabled = !isConnecting
        passwordInput.isEnabled = !isConnecting
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }
}
