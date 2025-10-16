package space.coljac.FreeAudio.data

data class PlaybackProgress(
    val talkId: String,
    val trackIndex: Int,
    val positionMs: Long,
    val lastPlayedTimestamp: Long = System.currentTimeMillis()
)