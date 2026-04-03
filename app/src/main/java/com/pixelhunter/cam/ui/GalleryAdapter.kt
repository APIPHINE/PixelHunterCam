package com.pixelhunter.cam.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pixelhunter.cam.R
import com.pixelhunter.cam.db.ShootImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecyclerView Adapter for the gallery grid.
 */
class GalleryAdapter(
    private val images: List<ShootImage>,
    private val onImageClick: (ShootImage) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgThumbnail: ImageView = view.findViewById(R.id.imgThumbnail)
        val tvThumbDate: TextView = view.findViewById(R.id.tvThumbDate)
        val flagIndicator: View = view.findViewById(R.id.flagIndicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_image, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val image = images[position]

        // Load thumbnail asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            val bitmap = BitmapFactory.decodeFile(image.thumbnailPath)
            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    holder.imgThumbnail.setImageBitmap(bitmap)
                } else {
                    // Try loading full image if thumbnail is missing
                    val fullBitmap = BitmapFactory.decodeFile(image.imagePath)
                    if (fullBitmap != null) {
                        holder.imgThumbnail.setImageBitmap(fullBitmap)
                    } else {
                        holder.imgThumbnail.setImageResource(android.R.drawable.ic_delete)
                    }
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

    override fun getItemCount(): Int = images.size
}
