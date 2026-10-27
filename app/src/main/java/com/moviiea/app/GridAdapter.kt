package com.moviiea.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/**
 * Shows poster cards — used for the browse grid, search results, My List and
 * the "More like this" row (with a different, smaller card layout).
 *
 * Every card carries a heart button (only on layouts that have one): tapping
 * it saves the title to My List or removes it, with a little toast as
 * feedback. Tapping the card itself opens the details screen.
 */
class GridAdapter(
    private val layoutRes: Int,
    private val onClick: (Tmdb.Title) -> Unit,
    private val onFavChanged: (Tmdb.Title) -> Unit = {}
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

        // Heart button (present on poster cards, absent on the small row).
        val fav = holder.btnFav
        if (fav != null) {
            fav.setImageResource(
                if (FavoritesStore.contains(fav.context, item.id)) R.drawable.ic_heart_filled
                else R.drawable.ic_heart
            )
            fav.setOnClickListener {
                val nowFav = FavoritesStore.toggle(fav.context, item)
                fav.setImageResource(if (nowFav) R.drawable.ic_heart_filled else R.drawable.ic_heart)
                Toast.makeText(
                    fav.context,
                    if (nowFav) R.string.added_to_mylist else R.string.removed_from_mylist,
                    Toast.LENGTH_SHORT
                ).show()
                onFavChanged(item)
            }
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val poster: AppCompatImageView = view.findViewById(R.id.imgPoster)
        val title: TextView = view.findViewById(R.id.textTitle)
        // Null on card layouts without a heart (e.g. the small "More like this" row).
        val btnFav: ImageButton? = view.findViewById(R.id.btnFav)
    }
}
