package com.pixelhunter.cam.ui

import com.pixelhunter.cam.util.ImageLoader
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.pixelhunter.cam.R
import com.pixelhunter.cam.db.ShootImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecyclerView Adapter for the gallery grid.
 * Safely loads thumbnails with lifecycle-aware coroutines and cancels
 * in-flight loads when views are recycled.
 */
class GalleryAdapter(
    private val images: List<ShootImage>,
    private val onImageClick: (ShootImage) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgThumbnail: ImageView = view.findViewById(R.id.imgThumbnail)
        val tvThumbDate: TextView = view.findViewById(R.id.tvThumbDate)
        val flagIndicator: View = view.findViewById(R.id.flagIndicator)
        var loadJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_image, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val image = images[position]

        // Cancel any previous load for this holder
        holder.loadJob?.cancel()
        holder.imgThumbnail.setImageResource(R.drawable.placeholder_image)

        // Load thumbnail asynchronously using the view tree lifecycle scope
        val lifecycleOwner = holder.itemView.findViewTreeLifecycleOwner()
        holder.loadJob = lifecycleOwner?.lifecycleScope?.launch(Dispatchers.IO) {
            val context = holder.itemView.context
            // Try thumbnail first, then full image (scaled down to avoid OOM)
            val bitmap = ImageLoader.loadBitmap(context, image.thumbnailPath)
                ?: ImageLoader.loadBitmap(context, image.imagePath)
            withContext(Dispatchers.Main) {
                // Guard against recycled view / stale position
                if (holder.bindingAdapterPosition == position && bitmap != null) {
                    holder.imgThumbnail.setImageBitmap(bitmap)
                } else if (holder.bindingAdapterPosition == position) {
                    holder.imgThumbnail.setImageResource(R.drawable.placeholder_image)
                }
            }
        }

        // Show date
        val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
        holder.tvThumbDate.text = dateFormat.format(Date(image.capturedAt))

        // Show flag indicator if image had issues
        holder.flagIndicator.visibility = if (image.hadFlags) View.VISIBLE else View.GONE

        // Click handler
        holder.itemView.setOnClickListener {
            onImageClick(image)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.loadJob?.cancel()
        holder.imgThumbnail.setImageResource(R.drawable.placeholder_image)
    }

    override fun getItemCount(): Int = images.size
}
