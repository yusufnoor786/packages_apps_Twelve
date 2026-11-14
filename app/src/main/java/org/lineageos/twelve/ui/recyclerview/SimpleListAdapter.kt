/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.recyclerview

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * A very basic ListAdapter that holds only one type of item.
 * @param diffCallback A [DiffUtil.ItemCallback] provided by the derived class
 * @param factory The factory of the [View]
 */
abstract class SimpleListAdapter<T, V : View>(
    diffCallback: DiffUtil.ItemCallback<T>,
    private val factory: (Context) -> V,
) : ListAdapter<T, SimpleListAdapter<T, V>.ViewHolder>(diffCallback) {
    abstract fun ViewHolder.onBindView(item: T)

    open fun ViewHolder.onPrepareView() {}

    /**
     * Whether the rows are drawn as one connected group: the first and last rows have round
     * outer corners, so they are bound again when the list grows or shrinks.
     */
    open val groupedRows = false

    override fun onCurrentListChanged(previousList: MutableList<T>, currentList: MutableList<T>) {
        super.onCurrentListChanged(previousList, currentList)

        if (groupedRows && previousList.size != currentList.size && currentList.isNotEmpty()) {
            listOf(0, 1, currentList.size - 2, currentList.size - 1)
                .filter { it in currentList.indices }
                .distinct()
                .forEach { notifyItemChanged(it) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        factory(parent.context),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(val view: V) : RecyclerView.ViewHolder(view) {
        var item: T? = null

        init {
            onPrepareView()
        }

        fun bind(item: T) {
            this.item = item

            onBindView(item)
        }
    }
}
