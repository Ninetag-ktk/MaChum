package com.ninetag.machum.external

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** App-wide presentation preferences for the document information section. */
class DocumentInfoPreferences(private val dataStore: DataStore<Preferences>) {
    val expanded: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[EXPANDED] ?: false }
        .distinctUntilChanged()

    suspend fun setExpanded(expanded: Boolean) {
        dataStore.edit { preferences -> preferences[EXPANDED] = expanded }
    }

    private companion object {
        val EXPANDED = booleanPreferencesKey("document_info_expanded")
    }
}
