import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import oshi.SystemInfo
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap

val activeChats = ConcurrentHashMap<Long, Boolean>()

fun main() {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:anticheat.db")
    conn.createStatement().execute("""
        CREATE TABLE IF NOT EXISTS anticheat_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player TEXT,
            cheat_type TEXT,
            coordinates TEXT,
            timestamp TEXT
        )
    """.trimIndent())

    val serverStatus = ConcurrentHashMap<String, String>()
    var isServerRunning = false
    var uptimeStart: Long = 0
    val systemInfo = SystemInfo()
    val processor = systemInfo.hardware.processor
    val memory = systemInfo.hardware.memory

    val inventoryRequests = ConcurrentHashMap<String, Long>()

    val chatInputActive = ConcurrentHashMap<Long, Boolean>()

    val wsClient = object : WebSocketClient(URI("ws://localhost:8080")) {
        override fun onOpen(handshakedata: ServerHandshake?) {
            println("WebSocket connected")
        }

        override fun onMessage(message: String) {
            when {
                message.startsWith("STATUS:") -> {
                    serverStatus["data"] = message.removePrefix("STATUS:")
                    isServerRunning = true
                    if (uptimeStart == 0L) uptimeStart = System.currentTimeMillis()
                }
                message.startsWith("INVENTORY:") -> {
                    val parts = message.removePrefix("INVENTORY:").split("|")
                    val player = parts[0]
                    val inventory = parts[1]
                    val chatId = inventoryRequests[player]
                    if (chatId != null) {
                        bot { token = "YOUR_TOKEN" }.sendMessage(
                            ChatId.fromId(chatId),
                            text = "Inventory of $player:\n$inventory",
                            replyMarkup = mainMenu()
                        )
                        inventoryRequests.remove(player)
                    }
                }
                message.startsWith("ANTICHEAT:") -> {
                    val parts = message.removePrefix("ANTICHEAT:").split("|")
                    val player = parts[0]
                    val cheatType = parts[1]
                    val coords = parts[2]
                    val timestamp = parts[3]
                    conn.createStatement().execute("""
                        INSERT INTO anticheat_logs (player, cheat_type, coordinates, timestamp)
                        VALUES ('$player', '$cheatType', '$coords', '$timestamp')
                    """.trimIndent())
                    activeChats.keys.forEach { chatId ->
                        bot { token = "YOUR_TOKEN" }.sendMessage(
                            ChatId.fromId(chatId),
                            text = "[Anticheat] $player used $cheatType!\nCoords: $coords\nTime: $timestamp"
                        )
                    }
                }
                message.startsWith("CHAT:") -> {
                    val parts = message.removePrefix("CHAT:").split("|")
                    val player = parts[0]
                    val text = parts[1]
                    activeChats.keys.forEach { chatId ->
                        bot { token = "YOUR_TOKEN" }.sendMessage(
                            ChatId.fromId(chatId),
                            text = "[$player]: $text"
                        )
                    }
                }
            }
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            isServerRunning = false
            uptimeStart = 0
            println("WebSocket closed: $reason")
        }

        override fun onError(ex: Exception?) {
            println("WebSocket error: ${ex?.message}")
        }
    }
    wsClient.connect()

    // Telegram Bot
    val bot = bot {
        token = "YOUR_TOKEN"
        dispatch {
            command("start") {
                activeChats[message.chat.id] = true
                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = "Welcome! I'm a bot for managing your Minecraft server.",
                    replyMarkup = mainMenu()
                )
            }

            command("status") {
                if (!isServerRunning) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Server is offline."
                    )
                    return@command
                }
                val status = serverStatus["data"]?.split("|") ?: listOf("0", "", "0")
                val players = status[0].toInt()
                val playerList = status[1]
                val ping = status[2]
                val uptime = (System.currentTimeMillis() - uptimeStart) / 1000 / 60
                val prevTicks = processor.systemCpuLoadTicks
                Thread.sleep(1000)
                val cpuLoad = processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100
                val usedMemory = (memory.total - memory.available) / 1024 / 1024
                val totalMemory = memory.total / 1024 / 1024

                val memoryStats = try {
                    val process = ProcessBuilder("/bin/bash", "-c", "free -m | awk 'FNR==2{print}' | awk '{printf \"RAM (GB) → total: %.2f, used: %.2f, free: %.2f\\n\", \$2/1024, \$3/1024, \$4/1024}'")
                        .redirectErrorStream(true)
                        .start()
                    process.inputStream.bufferedReader().readText().trim()
                } catch (e: Exception) {
                    "Unable to fetch memory stats: ${e.message}"
                }

                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = """
                        Server: Online
                        Players: $players/20
                        Player List: $playerList
                        Ping: $ping ms
                        Uptime: $uptime minutes
                        CPU: ${"%.2f".format(cpuLoad)}%
                        RAM: $usedMemory/$totalMemory MB
                        ─── Shell Memory Stats (GB) ───
                        $memoryStats
                    """.trimIndent(),
                    replyMarkup = mainMenu()
                )
            }

            command("inventory") {
                val args = args.joinToString(" ")
                if (args.isEmpty()) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Usage: /inventory <player>"
                    )
                    return@command
                }
                inventoryRequests[args] = message.chat.id
                wsClient.send("INVENTORY:$args")
            }

            command("anticheat") {
                val anticheatLogs = StringBuilder("Anticheat Logs:\n")
                val result = conn.createStatement().executeQuery("SELECT * FROM anticheat_logs ORDER BY id DESC LIMIT 5")
                while (result.next()) {
                    anticheatLogs.append("Player: ${result.getString("player")}, Cheat: ${result.getString("cheat_type")}, Coords: ${result.getString("coordinates")}, Time: ${result.getString("timestamp")}\n")
                }

                val serverLogs = StringBuilder("Server Logs (last 10 lines):\n")
                try {
                    val logFile = java.io.File("minecraft/logs/latest.log")
                    if (logFile.exists()) {
                        logFile.readLines().takeLast(10).forEach { line ->
                            serverLogs.append("$line\n")
                        }
                    } else {
                        serverLogs.append("Log file not found.\n")
                    }
                } catch (e: Exception) {
                    serverLogs.append("Error reading server logs: ${e.message}\n")
                }

                val combinedLogs = """
        $anticheatLogs
        $serverLogs
    """.trimIndent()

                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = combinedLogs,
                    replyMarkup = mainMenu()
                )
            }

            command("help") {
                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = """
                        Available Commands:
                        /start - Show main menu
                        /status - Show server status
                        /inventory <player> - Show player's inventory
                        /anticheat - Show server and anticheat logs
                        /help - Show this help
                    """.trimIndent(),
                    replyMarkup = mainMenu()
                )
            }

            callbackQuery("status") {
                if (!isServerRunning) {
                    bot.editMessageText(
                        chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@callbackQuery),
                        messageId = callbackQuery.message?.messageId ?: return@callbackQuery,
                        text = "Server is offline.",
                        replyMarkup = mainMenu()
                    )
                    return@callbackQuery
                }
                val status = serverStatus["data"]?.split("|") ?: listOf("0", "", "0")
                val players = status[0].toInt()
                val playerList = status[1]
                val ping = status[2]
                val uptime = (System.currentTimeMillis() - uptimeStart) / 1000 / 60
                val prevTicks = processor.systemCpuLoadTicks
                Thread.sleep(1000)
                val cpuLoad = processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100
                val usedMemory = (memory.total - memory.available) / 1024 / 1024
                val totalMemory = memory.total / 1024 / 1024

                val memoryStats = try {
                    val process = ProcessBuilder("/bin/bash", "-c", "free -m | awk 'FNR==2{print}' | awk '{printf \"RAM (GB) → total: %.2f, used: %.2f, free: %.2f\\n\", \$2/1024, \$3/1024, \$4/1024}'")
                        .redirectErrorStream(true)
                        .start()
                    process.inputStream.bufferedReader().readText().trim()
                } catch (e: Exception) {
                    "Unable to fetch memory stats: ${e.message}"
                }

                bot.editMessageText(
                    chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@callbackQuery),
                    messageId = callbackQuery.message?.messageId ?: return@callbackQuery,
                    text = """
                        Server: Online
                        Players: $players/20
                        Player List: $playerList
                        Ping: $ping ms
                        Uptime: $uptime minutes
                        CPU: ${"%.2f".format(cpuLoad)}%
                        RAM: $usedMemory/$totalMemory MB
                        ─── Shell Memory Stats (GB) ───
                        $memoryStats
                    """.trimIndent(),
                    replyMarkup = mainMenu()
                )
            }

            callbackQuery("help") {
                bot.editMessageText(
                    chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@callbackQuery),
                    messageId = callbackQuery.message?.messageId ?: return@callbackQuery,
                    text = """
            Available Commands:
            /start - Show main menu
            /status - Show server status
            /inventory <player> - Show player's inventory
            /logs - Show recent anticheat logs
            /help - Show this help
        """.trimIndent(),
                    replyMarkup = mainMenu()
                )
            }

            callbackQuery("anticheat") {
                val anticheatLogs = StringBuilder("Anticheat Logs:\n")
                val result = conn.createStatement().executeQuery("SELECT * FROM anticheat_logs ORDER BY id DESC LIMIT 5")
                while (result.next()) {
                    anticheatLogs.append("Player: ${result.getString("player")}, Cheat: ${result.getString("cheat_type")}, Coords: ${result.getString("coordinates")}, Time: ${result.getString("timestamp")}\n")
                }

                val serverLogs = StringBuilder("Server Logs (last 10 lines):\n")
                try {
                    val logFile = java.io.File("minecraft/logs/latest.log") // Шлях до latest.log
                    if (logFile.exists()) {
                        logFile.readLines().takeLast(10).forEach { line ->
                            serverLogs.append("$line\n")
                        }
                    } else {
                        serverLogs.append("Log file not found.\n")
                    }
                } catch (e: Exception) {
                    serverLogs.append("Error reading server logs: ${e.message}\n")
                }

                val combinedLogs = """
        $anticheatLogs
        $serverLogs
    """.trimIndent()

                bot.editMessageText(
                    chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@callbackQuery),
                    messageId = callbackQuery.message?.messageId ?: return@callbackQuery,
                    text = combinedLogs,
                    replyMarkup = mainMenu()
                )
            }

            callbackQuery("send_to_minecraft") {
                chatInputActive[callbackQuery.message?.chat?.id ?: return@callbackQuery] = true
                bot.editMessageText(
                    chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@callbackQuery),
                    messageId = callbackQuery.message?.messageId ?: return@callbackQuery,
                    text = "Enter your message to send to Minecraft chat (text only).",
                    replyMarkup = mainMenu()
                )
            }

            message {
                val chatId = message.chat.id
                if (chatInputActive[chatId] == true && message.text != null) {
                    val text = message.text
                    wsClient.send("CHAT:${message.from?.username ?: "TelegramUser"}|$text")
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = "Message sent to Minecraft: $text",
                        replyMarkup = mainMenu()
                    )
                    chatInputActive[chatId] = false
                }
            }
        }
    }

    bot.startPolling()
}

fun mainMenu(): InlineKeyboardMarkup {
    return InlineKeyboardMarkup.create(
        listOf(
            listOf(
                InlineKeyboardButton.CallbackData("Status", callbackData = "status"),
                InlineKeyboardButton.CallbackData("Help", callbackData = "help")
            ),
            listOf(
                InlineKeyboardButton.CallbackData("Anticheat", callbackData = "anticheat"),
                InlineKeyboardButton.CallbackData("Send to Minecraft", callbackData = "send_to_minecraft")
            )
        )
    )
}