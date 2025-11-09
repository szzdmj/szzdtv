package com.example.tvapp.ui

import android.os.Bundle
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.Presenter

class MainRowsFragment : BrowseSupportFragment() {

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        title = "TV Demo"
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true

        buildRows()
    }

    private fun buildRows() {
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

        val cardPresenter = SimpleCardPresenter()

        val listRowAdapter = ArrayObjectAdapter(cardPresenter).apply {
            add("Video A")
            add("Video B")
            add("Video C")
        }
        rowsAdapter.add(ListRow(HeaderItem(0, "Samples"), listRowAdapter))

        adapter = rowsAdapter
    }

    private class SimpleCardPresenter : Presenter() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup): ViewHolder {
            val v = android.widget.TextView(parent.context).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                textSize = 18f
                setPadding(32, 32, 32, 32)
                setBackgroundColor(0x303F51B5)
                setTextColor(0xFFFFFFFF.toInt())
            }
            return ViewHolder(v)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
            (viewHolder.view as android.widget.TextView).text = item.toString()
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
    }
}
