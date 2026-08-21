package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.local.dao.SearchHistoryDao
import dev.schlubbe.musicagent.data.local.entity.SearchHistoryEntity
import dev.schlubbe.musicagent.data.local.mapper.nowIso
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Recent search history — populated when a user submits a search, displayed
 * when the query field is empty/on focus, tappable to re-run that search. */
@Singleton
class SearchHistoryRepository @Inject constructor(
    private val searchHistoryDao: SearchHistoryDao,
) {
    private val _history = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())
    val history: StateFlow<List<SearchHistoryEntity>> = _history.asStateFlow()

    suspend fun refresh() {
        val recent = searchHistoryDao.getRecent()
        _history.value = recent
    }

    suspend fun addQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        searchHistoryDao.insert(SearchHistoryEntity(trimmed, nowIso()))
        refresh()
    }

    suspend fun deleteQuery(query: String) {
        searchHistoryDao.delete(query)
        refresh()
    }

    suspend fun deleteAll() {
        searchHistoryDao.deleteAll()
        refresh()
    }
}
