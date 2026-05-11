package top.cnuo.kalogin.accountcenter

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CodeManager(private val expireMinutes: Long, private val codeLength: Int, private val maxAttempts: Int) {
    data class Session(
        val playerId: UUID,
        val username: String,
        val email: String,
        val code: String,
        val type: Type,
        val expireAt: Long,
        var attempts: Int = 0
    )

    enum class Type { BIND, RESET }

    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val cooldowns = ConcurrentHashMap<UUID, Long>()

    fun inCooldown(playerId: UUID, cooldownSeconds: Long): Boolean {
        val last = cooldowns[playerId] ?: return false
        return System.currentTimeMillis() - last < cooldownSeconds * 1000L
    }

    fun create(playerId: UUID, username: String, email: String, type: Type): Session {
        val code = buildString {
            repeat(codeLength.coerceAtLeast(4)) { append(random.nextInt(10)) }
        }
        val session = Session(
            playerId = playerId,
            username = username,
            email = email,
            code = code,
            type = type,
            expireAt = System.currentTimeMillis() + expireMinutes * 60_000L
        )
        sessions[playerId] = session
        cooldowns[playerId] = System.currentTimeMillis()
        return session
    }

    fun get(playerId: UUID): Session? = sessions[playerId]

    fun verify(playerId: UUID, code: String, type: Type): Session? {
        val session = sessions[playerId] ?: return null
        if (session.type != type || System.currentTimeMillis() > session.expireAt) {
            sessions.remove(playerId)
            return null
        }
        session.attempts++
        if (session.attempts > maxAttempts) {
            sessions.remove(playerId)
            return null
        }
        return if (session.code == code.trim()) session else null
    }

    fun clear(playerId: UUID) {
        sessions.remove(playerId)
    }
}
