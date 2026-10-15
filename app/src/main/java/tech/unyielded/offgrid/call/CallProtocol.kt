package tech.unyielded.offgrid.call

/**
 * Control messages for experimental voice calls.
 * Sent as normal encrypted text frames with a clear prefix
 * so they never mix with chat text.
 */
object CallProtocol {
    const val PREFIX = "OFFGRID_CALL:"

    const val OFFER = "OFFER"
    const val ACCEPT = "ACCEPT"
    const val REJECT = "REJECT"
    const val END = "END"
    const val BUSY = "BUSY"

    fun offer(): String = PREFIX + OFFER
    fun accept(): String = PREFIX + ACCEPT
    fun reject(): String = PREFIX + REJECT
    fun end(): String = PREFIX + END
    fun busy(): String = PREFIX + BUSY

    fun parse(text: String): String? {
        if (!text.startsWith(PREFIX)) return null
        return text.removePrefix(PREFIX)
    }

    fun isCallControl(text: String): Boolean = text.startsWith(PREFIX)
}
