package com.gpstracker.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LocationAdapter : RecyclerView.Adapter<LocationAdapter.ViewHolder>() {

    private var data: List<LocationData> = emptyList()

    fun setData(newData: List<LocationData>) {
        data = newData
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tvIndex)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvLocation: TextView = view.findViewById(R.id.tvLocation)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_location, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = data[position]
        holder.tvIndex.text = "#${item.id}"
        holder.tvTime.text = item.clientTimeString
        holder.tvLocation.text = String.format("%.6f, %.6f", item.latitude, item.longitude)

        if (item.uploaded) {
            holder.tvStatus.text = "✓ 已上传"
            holder.tvStatus.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            holder.tvStatus.text = "待上传"
            holder.tvStatus.setTextColor(Color.parseColor("#FF9800"))
        }
    }

    override fun getItemCount() = data.size
}
