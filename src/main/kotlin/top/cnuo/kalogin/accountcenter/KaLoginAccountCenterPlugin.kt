@file:Suppress("UnstableApiUsage")

package top.cnuo.kalogin.accountcenter

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture

class KaLoginAccountCenterPlugin : JavaPlugin(), Listener {
    lateinit var storage: AccountStorage
    lateinit var codes: CodeManager
    lateinit var mailer: MailService
    lateinit var centerConfig: FileConfiguration

    override fun onEnable() {
        saveDefaultConfig()
        saveResource("center.yml", false)
        reloadInternal()
        server.pluginManager.registerEvents(this, this)
        logger.info("KaLogin-AccountCenter 已启用。")
    }

    override fun onDisable() {
        if (::storage.isInitialized) storage.close()
    }



    /**
     * 未登录状态命令入口绕过：
     * KaLogin 可能会禁止未登录玩家执行普通 Bukkit 命令。
     * 因此这里直接在 PlayerCommandPreprocessEvent 中捕获 /kac recover/reset/verify/help，
     * 取消原始命令，并由本插件直接打开 Dialog 或处理逻辑。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onPreCommand(event: PlayerCommandPreprocessEvent) {
        if (!config.getBoolean("command-bypass.enabled", true)) return

        val raw = event.message.trim()
        if (!raw.startsWith("/")) return

        val parts = raw.substring(1).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return

        val root = parts[0].lowercase(Locale.ROOT)
        val aliases = config.getStringList("command-bypass.aliases").ifEmpty { listOf("kac", "accountcenter", "kaloginaccountcenter") }
            .map { it.lowercase(Locale.ROOT) }
        if (root !in aliases) return

        val sub = parts.getOrNull(1)?.lowercase(Locale.ROOT) ?: "help"
        val allowed = config.getStringList("command-bypass.allowed-while-not-logged")
            .ifEmpty { listOf("recover", "forgot", "reset", "verify", "help") }
            .map { it.lowercase(Locale.ROOT) }

        if (sub !in allowed) return

        event.isCancelled = true
        debug("Command bypass captured: player=${event.player.name}, raw='$raw', sub='$sub'")
        // 延迟一小段时间再打开，避开 KaLogin 自己在同 tick 补开的登录 Dialog。
        server.scheduler.runTaskLater(this, Runnable {
            when (sub) {
                "recover", "forgot" -> showRecoverDialog(event.player)
                "reset" -> showResetDialog(event.player)
                "verify" -> showVerifyBindDialog(event.player)
                "help" -> showRecoverDialog(event.player)
                else -> showRecoverDialog(event.player)
            }
        }, config.getLong("dialog.command-open-delay-ticks", 4L).coerceAtLeast(0L))
    }


    private fun reloadInternal() {
        reloadConfig()
        if (::storage.isInitialized) storage.close()
        storage = AccountStorage(this)
        storage.init()
        codes = CodeManager(
            config.getLong("verification.expire-minutes", 10),
            config.getInt("verification.code-length", 6),
            config.getInt("verification.max-attempts", 5)
        )
        mailer = MailService(this)
        centerConfig = YamlConfiguration.loadConfiguration(File(dataFolder, "center.yml"))
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            if (sender is Player) showMainDialog(sender) else sender.sendMessage("/kac reload")
            return true
        }
        when (args[0].lowercase(Locale.ROOT)) {
            "reload" -> {
                if (!sender.hasPermission("kac.admin")) {
                    sender.sendMessage(TextUtil.parse(msg("no-permission")))
                    return true
                }
                reloadInternal()
                sender.sendMessage(TextUtil.parse(msg("reload")))
            }
            "center", "main", "home" -> requirePlayer(sender)?.let { showMainDialog(it) }
            "bind" -> requirePlayer(sender)?.let { showBindDialog(it) }
            "verify" -> requirePlayer(sender)?.let { showVerifyBindDialog(it) }
            "recover", "forgot" -> requirePlayer(sender)?.let { showRecoverDialog(it) }
            "reset" -> requirePlayer(sender)?.let { showResetDialog(it) }
            "change", "changepassword", "password" -> requirePlayer(sender)?.let { showChangePasswordDialog(it) }
            "close", "finish", "back" -> requirePlayer(sender)?.let { closeCenter(it) }
            else -> requirePlayer(sender)?.let { showMainDialog(it) }
        }
        return true
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender !is Player) {
            sender.sendMessage("这个指令只能由玩家执行。")
            return null
        }
        return sender
    }

    fun msg(path: String): String = config.getString("messages.$path", "") ?: ""

    fun logMailError(prefix: String, e: Exception) {
        logger.warning("$prefix: ${e.javaClass.name}: ${e.message}")
        if (config.getBoolean("mail.debug", false) || config.getBoolean("debug", false)) {
            e.printStackTrace()
        }
    }

    fun debug(message: String) {
        if (config.getBoolean("debug", false)) {
            logger.info("[Debug] $message")
        }
    }

    private fun ui(path: String): String = config.getString("ui.$path", path) ?: path
    private fun pref(path: String): String = (config.getString("messages.prefix", "") ?: "") + msg(path)

    private fun runAsync(block: () -> Unit) = CompletableFuture.runAsync(block)
    private fun sync(block: () -> Unit) = server.scheduler.runTask(this, Runnable(block))

    /**
     * KaLogin 登录 Dialog 可能会在同一 tick 或随后几 tick 再次弹出，
     * 导致 AccountCenter 的 Dialog 被顶掉。
     * 这里把所有 KAC Dialog 统一延迟/重试打开，尽量保证流程连续。
     */
    private fun showManagedDialog(player: Player, dialogFactory: () -> Dialog) {
        val delay = config.getLong("dialog.open-delay-ticks", 2L).coerceAtLeast(0L)
        val attempts = config.getInt("dialog.reopen-attempts", 2).coerceAtLeast(1)
        val interval = config.getLong("dialog.reopen-interval-ticks", 6L).coerceAtLeast(1L)

        repeat(attempts) { index ->
            val ticks = delay + index * interval
            server.scheduler.runTaskLater(this, Runnable {
                if (!player.isOnline) return@Runnable
                debug("Show managed dialog: player=${player.name}, attempt=${index + 1}/$attempts, delayTicks=$ticks")
                player.showDialog(dialogFactory())
            }, ticks)
        }
    }

    private fun validEmail(email: String): Boolean = email.matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$"))
    private fun validPassword(password: String): Boolean {
        val min = config.getInt("password.min-length", 6)
        val max = config.getInt("password.max-length", 20)
        return password.length in min..max
    }

    private fun sendCodeEmail(player: Player, email: String, code: String, type: CodeManager.Type) {
        if (!config.getBoolean("mail.enabled", true)) throw IllegalStateException("mail disabled")
        val subject = if (type == CodeManager.Type.BIND) config.getString("mail.subject-bind", "云墨工艺邮箱绑定验证码")!! else config.getString("mail.subject-reset", "云墨工艺找回密码验证码")!!
        val typeName = if (type == CodeManager.Type.BIND) "绑定邮箱" else "找回密码"
        mailer.sendCode(email, subject, player.name, code, typeName)
    }

    private fun maskEmail(email: String?): String {
        val e = email?.trim().orEmpty()
        if (e.isEmpty()) return "未绑定"
        val at = e.indexOf('@')
        return if (at <= 1) "***${e.substring(at.coerceAtLeast(0))}" else e.take(1) + "***" + e.substring(at)
    }

    private fun runConfiguredActions(player: Player, section: String) {
        val path = "after-actions.$section"
        if (!config.getBoolean("$path.enabled", false)) return
        val mode = config.getString("$path.command-mode", "console")?.lowercase(Locale.ROOT) ?: "console"
        val commands = config.getStringList("$path.commands")
        if (commands.isEmpty()) return
        debug("Run after-actions: section=$section, mode=$mode, commands=${commands.size}, player=${player.name}")
        server.scheduler.runTask(this, Runnable {
            for (raw in commands) {
                val command = raw
                    .replace("%player%", player.name)
                    .replace("%player_name%", player.name)
                    .replace("%uuid%", player.uniqueId.toString())
                    .replace("%ip%", player.address?.address?.hostAddress ?: "")
                    .trim()
                if (command.isEmpty()) continue
                if (mode == "player") {
                    player.performCommand(command.removePrefix("/"))
                } else {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.removePrefix("/"))
                }
            }
        })
    }

    private fun closeCenter(player: Player) {
        player.sendMessage(TextUtil.parse(pref("center-closed"), player))
        runConfiguredActions(player, "center-close")
    }

    private fun bodyCommon(player: Player, subtitle: String): MutableList<DialogBody> = mutableListOf(
        DialogBody.plainMessage(TextUtil.parse("""
            <gradient:#55C7FF:#B47CFF><bold>云墨工艺账号中心</bold></gradient>
            <dark_gray>━━━━━━━━━━━━━━━━━━━━</dark_gray>
            <gray>$subtitle</gray>
        """.trimIndent(), player)),
        DialogBody.plainMessage(TextUtil.parse("<gray>当前账号：</gray><gradient:gold:yellow><bold>%player_name%</bold></gradient>", player))
    )

    fun showMainDialog(player: Player) {
        showConfiguredCenterDialog(player, "main")
    }

    private fun showConfiguredCenterDialog(player: Player, page: String) {
        runAsync {
            val account = try { storage.findByUuid(player.uniqueId, player.name) ?: storage.findByName(player.name) } catch (e: Exception) {
                debug("Center account lookup failed: player=${player.name}, error=${e.javaClass.simpleName}: ${e.message}")
                null
            }
            val emailText = maskEmail(account?.email)
            sync {
                val basePath = "pages.$page"
                val titleRaw = centerConfig.getString("$basePath.title") ?: centerConfig.getString("pages.main.title") ?: ui("title-main")
                val subtitle = centerConfig.getString("$basePath.subtitle") ?: "这里是你的账号安全中心。"
                val body = bodyCommon(player, subtitle)

                val lines = centerConfig.getStringList("$basePath.body")
                if (lines.isNotEmpty()) {
                    body.add(DialogBody.plainMessage(TextUtil.parse(lines.joinToString("
"), player, mapOf("email" to emailText))))
                }

                val tip = centerConfig.getStringList("$basePath.tip")
                if (tip.isNotEmpty()) {
                    body.add(DialogBody.plainMessage(TextUtil.parse(tip.joinToString("
"), player, mapOf("email" to emailText))))
                }

                val primaryPath = "$basePath.buttons.primary"
                val secondaryPath = "$basePath.buttons.secondary"
                val primaryText = centerConfig.getString("$primaryPath.text") ?: "<aqua><bold>绑定 / 更换邮箱</bold></aqua>"
                val secondaryText = centerConfig.getString("$secondaryPath.text") ?: "<green><bold>完成并返回</bold></green>"
                val primaryAction = centerConfig.getString("$primaryPath.action") ?: "bind"
                val secondaryAction = centerConfig.getString("$secondaryPath.action") ?: "close"

                val primary = ActionButton.builder(TextUtil.parse(primaryText, player))
                    .action(DialogAction.customClick(DialogActionCallback { _, _ -> handleCenterAction(player, primaryAction) }, ClickCallback.Options.builder().lifetime(Duration.ofMinutes(5)).build()))
                    .build()
                val secondary = ActionButton.builder(TextUtil.parse(secondaryText, player))
                    .action(DialogAction.customClick(DialogActionCallback { _, _ -> handleCenterAction(player, secondaryAction) }, ClickCallback.Options.builder().lifetime(Duration.ofMinutes(5)).build()))
                    .build()

                val dialog = Dialog.create { builder ->
                    builder.empty()
                        .base(DialogBase.builder(TextUtil.parse(titleRaw, player)).body(body).canCloseWithEscape(centerConfig.getBoolean("$basePath.can-close-with-escape", true)).build())
                        .type(DialogType.confirmation(primary, secondary))
                }
                showManagedDialog(player) { dialog }
            }
        }
    }

    private fun handleCenterAction(player: Player, actionRaw: String) {
        val action = actionRaw.trim()
        debug("Center action: player=${player.name}, action='$action'")
        when {
            action.equals("bind", true) -> showBindDialog(player)
            action.equals("change", true) || action.equals("password", true) || action.equals("changepassword", true) -> showChangePasswordDialog(player)
            action.equals("close", true) || action.equals("back", true) || action.equals("finish", true) -> closeCenter(player)
            action.startsWith("page:", true) -> showConfiguredCenterDialog(player, action.substringAfter(':').trim().ifEmpty { "main" })
            action.startsWith("command:", true) -> {
                val command = action.substringAfter(':').trim()
                    .replace("%player%", player.name)
                    .replace("%player_name%", player.name)
                    .replace("%uuid%", player.uniqueId.toString())
                    .replace("%ip%", player.address?.address?.hostAddress ?: "")
                    .removePrefix("/")
                if (command.isNotBlank()) player.performCommand(command)
            }
            action.startsWith("console:", true) -> {
                val command = action.substringAfter(':').trim()
                    .replace("%player%", player.name)
                    .replace("%player_name%", player.name)
                    .replace("%uuid%", player.uniqueId.toString())
                    .replace("%ip%", player.address?.address?.hostAddress ?: "")
                    .removePrefix("/")
                if (command.isNotBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
            }
            else -> showConfiguredCenterDialog(player, "main")
        }
    }

    fun showBindDialog(player: Player, error: String? = null) {
        val body = bodyCommon(player, "绑定邮箱用于找回密码。")
        body.add(DialogBody.plainMessage(TextUtil.parse("<gray>请输入你的邮箱地址，系统会发送验证码。</gray>", player)))
        error?.let { body.add(DialogBody.plainMessage(TextUtil.parse(it, player))) }

        val input = DialogInput.text("email", TextUtil.parse("邮箱地址", player)).labelVisible(true).maxLength(128).build()
        val action = DialogAction.customClick(DialogActionCallback { response, _ ->
            val email = response.getText("email")?.trim() ?: ""
            if (!validEmail(email)) { showBindDialog(player, pref("invalid-email")); return@DialogActionCallback }
            if (codes.inCooldown(player.uniqueId, config.getLong("verification.cooldown-seconds", 60))) { showBindDialog(player, pref("cooldown")); return@DialogActionCallback }
            runAsync {
                try {
                    val session = codes.create(player.uniqueId, player.name, email, CodeManager.Type.BIND)
                    sendCodeEmail(player, email, session.code, CodeManager.Type.BIND)
                    sync { player.sendMessage(TextUtil.parse(pref("mail-sent"), player)); showVerifyBindDialog(player) }
                } catch (e: Exception) {
                    logMailError("发送绑定验证码失败", e)
                    sync { showBindDialog(player, pref("mail-disabled")) }
                }
            }
        }, ClickCallback.Options.builder().lifetime(Duration.ofMinutes(5)).build())

        val confirm = ActionButton.builder(TextUtil.parse(ui("button-send"), player)).action(action).build()
        showManagedDialog(player) {
            Dialog.create { builder ->
                builder.empty().base(DialogBase.builder(TextUtil.parse(ui("title-bind"), player)).body(body).inputs(listOf(input)).canCloseWithEscape(true).build()).type(DialogType.notice(confirm))
            }
        }
    }

    fun showVerifyBindDialog(player: Player, error: String? = null) {
        val body = bodyCommon(player, "请输入邮箱验证码完成绑定。")
        error?.let { body.add(DialogBody.plainMessage(TextUtil.parse(it, player))) }
        val input = DialogInput.text("code", TextUtil.parse("验证码", player)).labelVisible(true).maxLength(12).build()
        val action = DialogAction.customClick(DialogActionCallback { response, _ ->
            val code = response.getText("code") ?: ""
            val session = codes.verify(player.uniqueId, code, CodeManager.Type.BIND)
            if (session == null) { showVerifyBindDialog(player, pref("code-wrong")); return@DialogActionCallback }
            runAsync {
                try {
                    debug("Verify bind success: player=${player.name}, email=${session.email}")
                    storage.setEmail(player.uniqueId, player.name, session.email)
                    codes.clear(player.uniqueId)
                    sync {
                        player.sendMessage(TextUtil.parse(pref("bind-success"), player))
                        runConfiguredActions(player, "bind-success")
                        if (!config.getBoolean("after-actions.bind-success.close-dialog", false)) showMainDialog(player)
                    }
                } catch (e: Exception) {
                    logger.warning("绑定邮箱失败: ${e.message}")
                    sync { showVerifyBindDialog(player, "<red>数据库写入失败，请联系管理员。</red>") }
                }
            }
        }, ClickCallback.Options.builder().lifetime(Duration.ofMinutes(5)).build())
        val confirm = ActionButton.builder(TextUtil.parse(ui("button-confirm"), player)).action(action).build()
        showManagedDialog(player) {
            Dialog.create { builder ->
                builder.empty().base(DialogBase.builder(TextUtil.parse(ui("title-verify"), player)).body(body).inputs(listOf(input)).canCloseWithEscape(true).build()).type(DialogType.notice(confirm))
            }
        }
    }

    fun showChangePasswordDialog(player: Player, error: String? = null) {
        val body = bodyCommon(player, "通过绑定邮箱修改当前账号密码。")
        body.add(DialogBody.plainMessage(TextUtil.parse("<gray>系统会向你已绑定的邮箱发送验证码。</gray>", player)))
        error?.let { body.add(DialogBody.plainMessage(TextUtil.parse(it, player))) }

        val sendAction = DialogAction.customClick(DialogActionCallback { _, _ ->
            if (codes.inCooldown(player.uniqueId, config.getLong("verification.cooldown-seconds", 60))) { showChangePasswordDialog(player, pref("cooldown")); return@DialogActionCallback }
            runAsync {
                val account = try { storage.findByUuid(player.uniqueId, player.name) ?: storage.findByName(player.name) } catch (e: Exception) {
                    debug("Change password lookup failed: player=${player.name}, error=${e.javaClass.simpleName}: ${e.message}")
                    null
                }
                if (account == null) {
                    sync { showChangePasswordDialog(player, pref("user-not-found")) }
                    return@runAsync
                }
                val email = account.email?.trim().orEmpty()
                if (email.isEmpty()) {
                    sync { showChangePasswordDialog(player, pref("email-empty")) }
                    return@runAsync
                }
                try {
                    val session = codes.create(player.uniqueId, account.username, email, CodeManager.Type.RESET)
                    sendCodeEmail(player, email, session.code, CodeManager.Type.RESET)
                    sync { player.sendMessage(TextUtil.parse(pref("mail-sent"), player)); showResetDialog(player) }
                } catch (e: Exception) {
                    logMailError("发送改密验证码失败", e)
                    sync { showChangePasswordDialog(player, pref("mail-disabled")) }
                }
            }
        }, ClickCallback.Options.builder().lifetime(Duration.ofMinutes(5)).build())
        val cancelAction = DialogAction.customClick(DialogActionCallback { _, _ -> showMainDialog(player) }, ClickCallback.Options.builder().lifetime(Duration.ofMinutes(5)).build())
        val send = ActionButton.builder(TextUtil.parse(ui("button-send"), player)).action(sendAction).build()
        val cancel = ActionButton.builder(TextUtil.parse(ui("button-back"), player)).action(cancelAction).build()
        showManagedDialog(player) {
            Dialog.create { builder ->
                builder.empty().base(DialogBase.builder(TextUtil.parse(ui("title-change"), player)).body(body).canCloseWithEscape(true).build()).type(DialogType.confirmation(send, cancel))
            }
        }
    }

    fun showRecoverDialog(player: Player, error: String? = null) {
        val body = bodyCommon(player, "通过绑定邮箱找回密码。")
        body.add(DialogBody.plainMessage(TextUtil.parse("<gray>请输入账号名和已绑定邮箱，验证通过后可重置密码。</gray>", player)))
        error?.let { body.add(DialogBody.plainMessage(TextUtil.parse(it, player))) }
        val usernameInput = DialogInput.text("username", TextUtil.parse("账号名", player)).initial(player.name).labelVisible(true).maxLength(32).build()
        val emailInput = DialogInput.text("email", TextUtil.parse("绑定邮箱", player)).labelVisible(true).maxLength(128).build()
        val action = DialogAction.customClick(DialogActionCallback { response, _ ->
            val username = response.getText("username")?.trim() ?: ""
            val email = response.getText("email")?.trim() ?: ""
            if (!validEmail(email)) { showRecoverDialog(player, pref("invalid-email")); return@DialogActionCallback }
            if (codes.inCooldown(player.uniqueId, config.getLong("verification.cooldown-seconds", 60))) { showRecoverDialog(player, pref("cooldown")); return@DialogActionCallback }
            runAsync {
                val account = try { storage.findByName(username) } catch (e: Exception) {
                    debug("Recover lookup failed: username='$username', error=${e.javaClass.simpleName}: ${e.message}")
                    null
                }
                if (account == null) {
                    debug("Recover failed: account not found, username='$username', inputEmail='$email'")
                    sync { showRecoverDialog(player, pref("user-not-found")) }
                    return@runAsync
                }
                val dbEmail = account.email?.trim().orEmpty()
                val inputEmail = email.trim()
                debug("Recover compare: inputUser='$username', dbUser='${account.username}', inputEmail='$inputEmail', dbEmail='$dbEmail'")
                if (!dbEmail.equals(inputEmail, ignoreCase = true)) {
                    sync { showRecoverDialog(player, pref("email-not-match")) }
                    return@runAsync
                }
                try {
                    val session = codes.create(player.uniqueId, account.username, email, CodeManager.Type.RESET)
                    sendCodeEmail(player, email, session.code, CodeManager.Type.RESET)
                    sync { player.sendMessage(TextUtil.parse(pref("mail-sent"), player)); showResetDialog(player) }
                } catch (e: Exception) {
                    logMailError("发送找回验证码失败", e)
                    sync { showRecoverDialog(player, pref("mail-disabled")) }
                }
            }
        }, ClickCallback.Options.builder().lifetime(Duration.ofMinutes(5)).build())
        val confirm = ActionButton.builder(TextUtil.parse(ui("button-send"), player)).action(action).build()
        showManagedDialog(player) {
            Dialog.create { builder ->
                builder.empty().base(DialogBase.builder(TextUtil.parse(ui("title-recover"), player)).body(body).inputs(listOf(usernameInput, emailInput)).canCloseWithEscape(true).build()).type(DialogType.notice(confirm))
            }
        }
    }

    fun showResetDialog(player: Player, error: String? = null) {
        val session = codes.get(player.uniqueId)
        if (session == null || session.type != CodeManager.Type.RESET) {
            showRecoverDialog(player, pref("code-wrong"))
            return
        }
        val body = bodyCommon(player, "正在为账号 ${session.username} 重置密码。")
        error?.let { body.add(DialogBody.plainMessage(TextUtil.parse(it, player))) }
        val codeInput = DialogInput.text("code", TextUtil.parse("邮箱验证码", player)).labelVisible(true).maxLength(12).build()
        val passInput = DialogInput.text("password", TextUtil.parse("新密码", player)).labelVisible(true).maxLength(64).build()
        val pass2Input = DialogInput.text("password2", TextUtil.parse("确认新密码", player)).labelVisible(true).maxLength(64).build()
        val action = DialogAction.customClick(DialogActionCallback { response, _ ->
            val code = response.getText("code") ?: ""
            val password = response.getText("password") ?: ""
            val password2 = response.getText("password2") ?: ""
            if (!validPassword(password)) { showResetDialog(player, pref("password-invalid")); return@DialogActionCallback }
            if (password != password2) { showResetDialog(player, pref("password-not-same")); return@DialogActionCallback }
            val verified = codes.verify(player.uniqueId, code, CodeManager.Type.RESET)
            if (verified == null) { showResetDialog(player, pref("code-wrong")); return@DialogActionCallback }
            runAsync {
                try {
                    debug("Reset password verified: requester=${player.name}, target=${verified.username}")
                    storage.resetPassword(verified.username, password, player.address?.address?.hostAddress)
                    codes.clear(player.uniqueId)
                    sync {
                        player.sendMessage(TextUtil.parse(pref("reset-success"), player))
                        runConfiguredActions(player, "password-reset-success")
                        if (!config.getBoolean("after-actions.password-reset-success.close-dialog", false)) showMainDialog(player)
                    }
                } catch (e: Exception) {
                    logger.warning("重置密码失败: ${e.message}")
                    sync { showResetDialog(player, "<red>数据库写入失败，请联系管理员。</red>") }
                }
            }
        }, ClickCallback.Options.builder().lifetime(Duration.ofMinutes(5)).build())
        val confirm = ActionButton.builder(TextUtil.parse(ui("button-confirm"), player)).action(action).build()
        showManagedDialog(player) {
            Dialog.create { builder ->
                builder.empty().base(DialogBase.builder(TextUtil.parse(ui("title-reset"), player)).body(body).inputs(listOf(codeInput, passInput, pass2Input)).canCloseWithEscape(true).build()).type(DialogType.notice(confirm))
            }
        }
    }
}
