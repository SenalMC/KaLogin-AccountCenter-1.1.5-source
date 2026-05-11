package top.cnuo.kalogin.accountcenter

import org.mindrot.jbcrypt.BCrypt
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class AccountStorage(private val plugin: KaLoginAccountCenterPlugin) {
    private var accountConnection: Connection? = null
    private var localConnection: Connection? = null
    private var mode: String = "catseed"

    fun init() {
        mode = plugin.config.getString("storage.mode", "catseed")!!.lowercase(Locale.ROOT)

        val accountPath = when (mode) {
            "kalogin" -> plugin.config.getString("kalogin.sqlite-file", "plugins/KaLogin/data.db")!!
            else -> plugin.config.getString("catseed.sqlite-file", "plugins/CatSeedLogin/accounts.db")!!
        }

        val accountFile = File(accountPath)
        if (!accountFile.exists()) throw IllegalArgumentException("账号数据库不存在: ${accountFile.absolutePath}")

        Class.forName("org.sqlite.JDBC")
        accountConnection = DriverManager.getConnection("jdbc:sqlite:${accountFile.absolutePath}")

        if (mode == "kalogin") {
            initLocalEmailDatabase()
            createLocalEmailTable()
            plugin.debug("KaLogin 原生模式：账号密码使用 KaLogin 数据库，邮箱绑定数据使用 KAC 本地库。")
        }

        plugin.logger.info("账号中心已连接账号数据库: ${accountFile.absolutePath}, mode=$mode")
    }

    private fun initLocalEmailDatabase() {
        val localPath = plugin.config.getString("kalogin.local-email-db", "plugins/KaLogin-AccountCenter/accountcenter.db")!!
        val localFile = File(localPath)
        localFile.parentFile?.mkdirs()
        localConnection = DriverManager.getConnection("jdbc:sqlite:${localFile.absolutePath}")
        plugin.logger.info("账号中心已连接本地邮箱数据库: ${localFile.absolutePath}")
    }

    fun close() {
        runCatching { accountConnection?.close() }
        runCatching { localConnection?.close() }
        accountConnection = null
        localConnection = null
    }

    private fun accountConn(): Connection {
        if (accountConnection == null || accountConnection!!.isClosed) init()
        return accountConnection!!
    }

    private fun localConn(): Connection {
        if (localConnection == null || localConnection!!.isClosed) initLocalEmailDatabase()
        return localConnection!!
    }

    private fun nowCatSeedTimestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())

    private fun csTable() = plugin.config.getString("catseed.table", "accounts")!!
    private fun csUserCol() = plugin.config.getString("catseed.columns.username", "name")!!
    private fun csPassCol() = plugin.config.getString("catseed.columns.password", "password")!!
    private fun csEmailCol() = plugin.config.getString("catseed.columns.email", "email")!!
    private fun csIpCol() = plugin.config.getString("catseed.columns.ips", "ips")!!
    private fun csLastCol() = plugin.config.getString("catseed.columns.last-action", "lastAction")!!

    private fun klTable() = plugin.config.getString("kalogin.user-table", "kalogin_users")!!
    private fun klEmailTable() = plugin.config.getString("kalogin.accountcenter-table", "kac_emails")!!
    private fun klUuidCol() = plugin.config.getString("kalogin.columns.uuid", "uuid")!!
    private fun klUserCol() = plugin.config.getString("kalogin.columns.username", "username")!!
    private fun klPassCol() = plugin.config.getString("kalogin.columns.password", "password")!!

    private fun createLocalEmailTable() {
        localConn().createStatement().use { st ->
            st.execute("""
                CREATE TABLE IF NOT EXISTS ${klEmailTable()} (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(32),
                    email VARCHAR(255),
                    verified_at VARCHAR(32)
                )
            """.trimIndent())
        }
    }

    private fun nameCondition(column: String): String =
        if (plugin.config.getBoolean("catseed.username-ignore-case", true)) "LOWER($column) = ?" else "$column = ?"

    private fun lookupName(name: String): String =
        if (plugin.config.getBoolean("catseed.username-ignore-case", true)) name.trim().lowercase(Locale.ROOT) else name.trim()

    data class Account(val uuid: UUID?, val username: String, val email: String?)

    fun findByName(username: String): Account? {
        val clean = username.trim()
        plugin.debug("findByName: mode=$mode, input='$username', clean='$clean'")
        return if (mode == "kalogin") findKaLoginByName(clean) else findCatSeedByName(clean)
    }

    fun findByUuid(uuid: UUID, fallbackName: String): Account? {
        return if (mode == "kalogin") {
            val sql = "SELECT ${klUserCol()} FROM ${klTable()} WHERE ${klUuidCol()} = ? LIMIT 1"
            accountConn().prepareStatement(sql).use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        plugin.debug("findByUuid: uuid=$uuid not found in KaLogin table")
                        return null
                    }
                    val username = rs.getString(1)
                    val email = findLocalEmailByUuid(uuid)
                    plugin.debug("findByUuid: uuid=$uuid username=$username localEmail=${maskEmail(email)}")
                    Account(uuid, username, email)
                }
            }
        } else findCatSeedByName(fallbackName)
    }

    private fun findCatSeedByName(username: String): Account? {
        val sql = "SELECT ${csUserCol()}, ${csEmailCol()} FROM ${csTable()} WHERE ${nameCondition(csUserCol())} LIMIT 1"
        plugin.debug("CatSeed SQL findByName: $sql ; arg='${lookupName(username)}'")
        accountConn().prepareStatement(sql).use { ps ->
            ps.setString(1, lookupName(username))
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    plugin.debug("CatSeed account not found: '$username'")
                    return null
                }
                val dbName = rs.getString(1)?.trim() ?: username
                val dbEmail = rs.getString(2)?.trim()
                plugin.debug("CatSeed account found: dbName='$dbName', email=${maskEmail(dbEmail)}")
                return Account(null, dbName, dbEmail)
            }
        }
    }

    private fun findKaLoginByName(username: String): Account? {
        val sql = "SELECT ${klUuidCol()}, ${klUserCol()} FROM ${klTable()} WHERE LOWER(${klUserCol()}) = ? LIMIT 1"
        plugin.debug("KaLogin SQL findByName: $sql ; arg='${username.lowercase(Locale.ROOT)}'")
        accountConn().prepareStatement(sql).use { ps ->
            ps.setString(1, username.lowercase(Locale.ROOT))
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    plugin.debug("KaLogin account not found: '$username'")
                    return null
                }
                val uuid = UUID.fromString(rs.getString(1))
                val dbName = rs.getString(2)
                val email = findLocalEmailByUuid(uuid)
                plugin.debug("KaLogin account found: uuid=$uuid, dbName='$dbName', localEmail=${maskEmail(email)}")
                return Account(uuid, dbName, email)
            }
        }
    }

    private fun findLocalEmailByUuid(uuid: UUID): String? {
        val sql = "SELECT email FROM ${klEmailTable()} WHERE uuid = ? LIMIT 1"
        localConn().prepareStatement(sql).use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getString(1)?.trim() else null
            }
        }
    }

    fun setEmail(uuid: UUID, username: String, email: String) {
        val cleanEmail = email.trim()
        if (mode == "kalogin") {
            // KaLogin 原生模式不修改 KaLogin 用户表，邮箱仅保存到 KAC 本地数据库。
            val sql = "INSERT INTO ${klEmailTable()} (uuid, username, email, verified_at) VALUES (?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET username=excluded.username, email=excluded.email, verified_at=excluded.verified_at"
            plugin.debug("Set local KaLogin email: uuid=$uuid username='$username' email=${maskEmail(cleanEmail)}")
            localConn().prepareStatement(sql).use { ps ->
                ps.setString(1, uuid.toString())
                ps.setString(2, username.trim())
                ps.setString(3, cleanEmail)
                ps.setString(4, nowCatSeedTimestamp())
                ps.executeUpdate()
            }
        } else {
            val sql = "UPDATE ${csTable()} SET ${csEmailCol()} = ?, ${csLastCol()} = ? WHERE ${nameCondition(csUserCol())}"
            plugin.debug("Set CatSeed email: username='$username' email=${maskEmail(cleanEmail)}")
            accountConn().prepareStatement(sql).use { ps ->
                ps.setString(1, cleanEmail)
                ps.setString(2, nowCatSeedTimestamp())
                ps.setString(3, lookupName(username))
                val updated = ps.executeUpdate()
                plugin.debug("Set CatSeed email updatedRows=$updated")
            }
        }
    }

    fun resetPassword(username: String, newPassword: String, ip: String?) {
        val cleanName = username.trim()
        if (mode == "kalogin") {
            val hash = BCrypt.hashpw(newPassword, BCrypt.gensalt(5))
            val sql = "UPDATE ${klTable()} SET ${klPassCol()} = ? WHERE LOWER(${klUserCol()}) = ?"
            plugin.debug("Reset KaLogin password only: username='$cleanName'")
            accountConn().prepareStatement(sql).use { ps ->
                ps.setString(1, hash)
                ps.setString(2, cleanName.lowercase(Locale.ROOT))
                val updated = ps.executeUpdate()
                plugin.debug("Reset KaLogin password updatedRows=$updated")
            }
        } else {
            val storedName = findCatSeedByName(cleanName)?.username ?: cleanName
            val hash = CatSeedHash.hash(storedName, newPassword)
            val sql = "UPDATE ${csTable()} SET ${csPassCol()} = ?, ${csIpCol()} = ?, ${csLastCol()} = ? WHERE ${nameCondition(csUserCol())}"
            plugin.debug("Reset CatSeed password: input='$cleanName', storedName='$storedName', ip='${ip ?: ""}'")
            accountConn().prepareStatement(sql).use { ps ->
                ps.setString(1, hash)
                ps.setString(2, ip ?: "")
                ps.setString(3, nowCatSeedTimestamp())
                ps.setString(4, lookupName(cleanName))
                val updated = ps.executeUpdate()
                plugin.debug("Reset CatSeed password updatedRows=$updated")
            }
        }
    }

    private fun maskEmail(email: String?): String {
        val e = email?.trim().orEmpty()
        if (e.isEmpty()) return "<empty>"
        val at = e.indexOf('@')
        return if (at <= 1) "***${e.substring(at.coerceAtLeast(0))}" else e.take(1) + "***" + e.substring(at)
    }
}
