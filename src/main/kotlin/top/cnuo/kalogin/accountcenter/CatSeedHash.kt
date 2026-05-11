package top.cnuo.kalogin.accountcenter

import java.security.MessageDigest

object CatSeedHash {
    private fun sha512HexCatSeedBugCompatible(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-512")
        md.update(bytes, 0, input.length)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun hash(name: String, password: String): String {
        val raw = "\u00DC\u00C4aeut//&/=I " +
                password +
                "7421\u20AC547" +
                name +
                "__+I\u00C4IH\u00A7%NK " +
                password
        return sha512HexCatSeedBugCompatible(raw)
    }
}
