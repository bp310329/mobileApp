package com.example.mobileapp.ui.route

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mobileapp.poi.OverpassPoiSource
import com.example.mobileapp.poi.PoiQueryException
import com.example.mobileapp.poi.PoiSearchParams
import com.example.mobileapp.poi.PoiSearchResult
import com.example.mobileapp.poi.PoiSearchStage
import com.example.mobileapp.poi.PoiSearcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Activity-scoped route generation state. Survives rotation, so a running POI search (which can
 * take a while on the public Overpass server) isn't restarted.
 *
 * Currently only step 1 — preparing candidate POIs. Next: AI picks the best ones, A-to-A routing.
 */
class RouteViewModel : ViewModel() {

    sealed interface PoiSearchState {
        data object Idle : PoiSearchState
        data class Searching(val params: PoiSearchParams, val stage: PoiSearchStage?, val radiusKm: Double?) :
            PoiSearchState
        data class Done(val result: PoiSearchResult, val originLatitude: Double, val originLongitude: Double) :
            PoiSearchState
        data class Failed(val message: String?) : PoiSearchState
    }

    private val searcher = PoiSearcher(OverpassPoiSource())

    private val _poiSearch = MutableStateFlow<PoiSearchState>(PoiSearchState.Idle)
    val poiSearch: StateFlow<PoiSearchState> = _poiSearch.asStateFlow()

    private var searchJob: Job? = null

    fun searchPois(settings: RouteSettings, latitude: Double, longitude: Double) {
        searchJob?.cancel()
        val params = PoiSearchParams.from(settings.lengthKm, settings.poiCount)
        _poiSearch.value = PoiSearchState.Searching(params, stage = null, radiusKm = null)
        searchJob = viewModelScope.launch {
            _poiSearch.value = try {
                val result = searcher.search(latitude, longitude, params) { stage, radiusKm ->
                    _poiSearch.value = PoiSearchState.Searching(params, stage, radiusKm)
                }
                Log.d(TAG, "POI search for $settings: ${result.pois.size}/${params.targetCount}, " +
                    "stage ${result.stage.number}, ${result.radiusKm} km, ${result.requestCount} queries")
                PoiSearchState.Done(result, latitude, longitude)
            } catch (e: PoiQueryException) {
                Log.w(TAG, "POI search failed", e)
                PoiSearchState.Failed(e.message)
            }
        }
    }

    fun cancelSearch() {
        searchJob?.cancel()
        _poiSearch.value = PoiSearchState.Idle
    }

    private companion object {
        const val TAG = "RouteViewModel"
    }
}
