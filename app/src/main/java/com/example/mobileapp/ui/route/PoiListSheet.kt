package com.example.mobileapp.ui.route

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.mobileapp.R
import com.example.mobileapp.poi.Poi
import com.example.mobileapp.poi.PoiClass
import com.example.mobileapp.ui.route.RouteViewModel.PoiSearchState
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Testing aid: shows the progress of the POI search and then the prepared POIs (class, name,
 * category, distance, description). Will be replaced once POIs are passed on to route planning.
 */
class PoiListSheet : BottomSheetDialogFragment(R.layout.sheet_poi_list) {

    private val viewModel by lazy { ViewModelProvider(requireActivity())[RouteViewModel::class.java] }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val summary = view.findViewById<TextView>(R.id.poiSummary)
        val progress = view.findViewById<View>(R.id.poiProgress)
        val adapter = PoiAdapter()
        view.findViewById<RecyclerView>(R.id.poiList).adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.poiSearch.collect { state ->
                    progress.isVisible = state is PoiSearchState.Searching
                    summary.text = when (state) {
                        PoiSearchState.Idle -> ""
                        is PoiSearchState.Searching -> if (state.stage == null) {
                            getString(R.string.poi_search_starting, state.params.targetCount)
                        } else {
                            getString(
                                R.string.poi_search_progress,
                                state.stage.number, km(state.radiusKm ?: 0.0), state.params.targetCount,
                            )
                        }
                        is PoiSearchState.Done -> with(state.result) {
                            getString(
                                if (isComplete) R.string.poi_search_done else R.string.poi_search_incomplete,
                                pois.size, params.targetCount, stage.number, km(radiusKm), requestCount,
                            ) + "\n" + getString(
                                R.string.poi_search_radii,
                                km(params.minRadiusKm), km(params.startRadiusKm), km(params.maxRadiusKm),
                            )
                        }
                        is PoiSearchState.Failed -> getString(R.string.route_poi_query_failed, state.message)
                    }
                    adapter.submitList(
                        if (state is PoiSearchState.Done) {
                            state.result.pois.map {
                                PoiRow(it, it.distanceKmTo(state.originLatitude, state.originLongitude))
                            }
                        } else {
                            emptyList()
                        }
                    )
                }
            }
        }
    }

    private fun km(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

    private data class PoiRow(val poi: Poi, val distanceKm: Double)

    private inner class PoiAdapter : ListAdapter<PoiRow, PoiAdapter.ViewHolder>(Diff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_poi, parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val poiClass: TextView = view.findViewById(R.id.poiClass)
            private val name: TextView = view.findViewById(R.id.poiName)
            private val details: TextView = view.findViewById(R.id.poiDetails)
            private val description: TextView = view.findViewById(R.id.poiDescription)

            fun bind(row: PoiRow) {
                val poi = row.poi
                poiClass.text = poi.poiClass.name
                poiClass.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(itemView.context, classColor(poi.poiClass))
                )
                name.text = poi.name ?: getString(R.string.poi_unnamed)
                details.text = getString(R.string.poi_details, poi.category, km(row.distanceKm))
                description.text = poi.description
                description.isVisible = !poi.description.isNullOrBlank()
            }
        }
    }

    private fun classColor(poiClass: PoiClass) = when (poiClass) {
        PoiClass.A -> R.color.poi_class_a
        PoiClass.B -> R.color.poi_class_b
        PoiClass.C -> R.color.poi_class_c
        PoiClass.D -> R.color.poi_class_d
    }

    private object Diff : DiffUtil.ItemCallback<PoiRow>() {
        override fun areItemsTheSame(old: PoiRow, new: PoiRow) = old.poi.id == new.poi.id
        override fun areContentsTheSame(old: PoiRow, new: PoiRow) = old == new
    }

    companion object {
        const val TAG = "PoiListSheet"
    }
}
