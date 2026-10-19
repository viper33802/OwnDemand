package dev.owndemand.app.ui.common

/** Paged list state shared by the channels screens. */
data class PagedListState<T>(
    val items: List<T> = emptyList(),
    val totalSize: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val loadedAll: Boolean get() = !loading && items.size >= totalSize && totalSize > 0
}
