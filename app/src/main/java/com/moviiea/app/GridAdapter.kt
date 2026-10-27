package com.moviiea.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/**
 * Shows poster cards — used for the browse grid, search results and the
 * "More like this" row (with a different, smaller card layout).
 *
 * Cards in the full layout (item_movie) also carry a My List heart in
 * their top corner; the compact layout simply has no heart view, which
 * the nullable lookup handles gracefully.
 */
class GridAdapter(
    private val layoutRes: Int,
    private val onClick: (Tmdb.Title) -> Unit,
    private val onFavChanged: (() -> Unit)? = null
) : RecyclerView.Adapter<GridAdapter.VH>() {

    private val items = ArrayList<Tmdb.Title>()

    /** Replace or append the current list of cards. */
    fun submit(newItems: List<Tmdb.Title>, append: Boolean) {
        if (!append) items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun isEmpty() = items.isEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.name
        holder.poster.load(item.poster) {
            placeholder(R.drawable.poster_ph)
            error(R.drawable.poster_ph)
            crossfade(true)
        }
        holder.itemView.setOnClickListener { onClick(item) }

        // My List heart (only exists on the full card layout)
        holder.btnFav?.let { btn ->
            val saved = FavoritesStore.contains(holder.itemView.context, item.id)
            btn.setImageResource(if (saved) R.drawable.ic_heart_filled else R.drawable.ic_heart)
            btn.setOnClickListener {
                val nowFav = FavoritesStore.toggle(holder.itemView.context, item)
                btn.setImageResource(
                    if (nowFav) R.drawable.ic_heart_filled else R.drawable.ic_heart
                )
                onFavChanged?.invoke()
            }
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val poster: AppCompatImageView = view.findViewById(R.id.imgPoster)
        val title: TextView = view.findViewById(R.id.textTitle)
        val btnFav: ImageButton? = view.findViewById(R.id.btnFav)
    }
}
