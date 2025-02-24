package space.coljac.FreeAudio.data

data class Talk(
    val id: String,
    val title: String,
    val speaker: String,
    val year: String,
    val blurb: String,
    val imageUrl: String,
    val tracks: List<Track>
)

data class Track(
    val title: String,
    val number: Int,
    val path: String,
    val duration: String
)

data class SearchResponse(
    val total: Int,
    val results: List<Talk>
)

sealed class SearchState {
    data object Loading : SearchState()
    data class Success(val response: SearchResponse) : SearchState()
    data class Error(val message: String) : SearchState()
    data object Empty : SearchState()
}