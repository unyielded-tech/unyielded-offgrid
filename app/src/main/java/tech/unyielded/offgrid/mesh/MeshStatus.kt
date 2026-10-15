package tech.unyielded.offgrid.mesh

/**
 * Simple status holder so the UI can show pending mesh count.
 */
object MeshStatus {
    @Volatile
    var pendingCount: Int = 0

    @Volatile
    var lastEvent: String = "Mesh idle"
}
