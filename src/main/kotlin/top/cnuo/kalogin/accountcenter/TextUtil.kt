package top.cnuo.kalogin.accountcenter

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player

object TextUtil {
    private val mini = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacySection()

    fun parse(raw: String, player: Player? = null, vars: Map<String, Any?> = emptyMap()): Component {
        var text = raw.replace('&', '§')
        player?.let {
            text = text.replace("%player%", it.name)
                .replace("%player_name%", it.name)
                .replace("%uuid%", it.uniqueId.toString())
        }
        vars.forEach { (k, v) -> text = text.replace("%$k%", v?.toString() ?: "") }
        return if (text.contains(Regex("<[a-zA-Z_#:/!][^>]*>"))) mini.deserialize(text.replace('§', '&')) else legacy.deserialize(text)
    }

    fun color(raw: String): String = raw.replace('&', '§')
}
