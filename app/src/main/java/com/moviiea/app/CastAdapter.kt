package com.moviiea.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/**
 * The Cast row on the details screen: a round photo, the actor name and
 * the character they play. Purely informational — no tap action.
 */
class CastAdapter : RecyclerView.Adapter<CastAdapter.VH>() {

    private val items = ArrayList<Tmdb.Cast>()

    fun submit(newItems: List<Tmdb.Cast>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cast, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.name.text = c.name
        holder.role.text = c.character
        holder.photo.load(c.profile) {
            placeholder(R.drawable.poster_ph)
            error(R.drawable.poster_ph)
            crossfade(true)
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val photo: AppCompatImageView = view.findViewById(R.id.imgCast)
        val name: TextView = view.findViewById(R.id.textCastName)
        val role: TextView = view.findViewById(R.id.textCastRole)
    }
}
