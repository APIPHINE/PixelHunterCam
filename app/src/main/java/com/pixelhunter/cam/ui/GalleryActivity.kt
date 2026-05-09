package com.pixelhunter.cam.ui

import android.app.Activity
import android.content.Intent
import com.pixelhunter.cam.util.ImageLoader
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pixelhunter.cam.R
import com.pixelhunter.cam.db.PixelHunterDatabase
import com.pixelhunter.cam.db.ShootImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Gallery Activity to view captured images.
 * - Grid view of all captured images
 * - Full-screen viewer with metadata
 * - Share and delete functionality
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var fullImageView: ImageView
    private lateinit var imageInfoPanel: View
    private lateinit var tvImageDate: TextView
    private lateinit var tvImageLocation: TextView
    private lateinit var tvImageSettings: TextView

    private var images: List<ShootImage> = emptyList()
    private var currentImage: ShootImage? = null
    private var viewMode = ViewMode.GRID
    private var fullImageLoadJob: kotlinx.coroutines.Job? = null

    private enum class ViewMode { GRID, FULLSCREEN }

    companion object {
        const val EXTRA_IMAGE_PATH = "extra_image_path"
        const val EXTRA_SHOW_SINGLE = "extra_show_single"

        fun start(activity: Activity, imagePath: String? = null) {
            val intent = Intent(activity, GalleryActivity::class.java).apply {
                imagePath?.let {
                    putExtra(EXTRA_IMAGE_PATH, it)
                    putExtra(EXTRA_SHOW_SINGLE, true)
                }
            }
            activity.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        setupViews()
        setupToolbar()

        // Check if we should show a single image directly
        val singleImagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val showSingle = intent.getBooleanExtra(EXTRA_SHOW_SINGLE, false)

        if (showSingle && singleImagePath != null) {
            loadSingleImage(singleImagePath)
        } else {
            loadGallery()
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewMode == ViewMode.GRID) {
            loadGallery()
        }
    }

    private fun setupViews() {
        recyclerView = findViewById(R.id.recyclerGallery)
        emptyView = findViewById(R.id.tvEmptyGallery)
        fullImageView = findViewById(R.id.fullImageView)
        imageInfoPanel = findViewById(R.id.imageInfoPanel)
        tvImageDate = findViewById(R.id.tvImageDate)
        tvImageLocation = findViewById(R.id.tvImageLocation)
        tvImageSettings = findViewById(R.id.tvImageSettings)

        recyclerView.layoutManager = GridLayoutManager(this, 3)

        findViewById<View>(R.id.btnShare).setOnClickListener { shareCurrentImage() }
        findViewById<View>(R.id.btnDelete).setOnClickListener { deleteCurrentImage() }
        findViewById<View>(R.id.btnBackToGallery).setOnClickListener { showGridView() }
    }

    private fun setupToolbar() {
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
    }

    private fun loadGallery() {
        CoroutineScope(Dispatchers.IO).launch {
            val db = PixelHunterDatabase.getInstance(this@GalleryActivity)
            images = db.imageDao().getAllImages()

            withContext(Dispatchers.Main) {
                if (images.isEmpty()) {
                    showEmptyState()
                } else {
                    showGrid()
                }
            }
        }
    }

    private fun loadSingleImage(imagePath: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = PixelHunterDatabase.getInstance(this@GalleryActivity)
            currentImage = db.imageDao().getImageByPath(imagePath)

            withContext(Dispatchers.Main) {
                currentImage?.let { image ->
                    showFullImage(image)
                } ?: run {
                    Toast.makeText(this@GalleryActivity, "Image not found", Toast.LENGTH_SHORT).show()
                    loadGallery()
                }
            }
        }
    }

    private fun showEmptyState() {
        viewMode = ViewMode.GRID
        recyclerView.visibility = View.GONE
        fullImageView.visibility = View.GONE
        imageInfoPanel.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
    }

    private fun showGrid() {
        viewMode = ViewMode.GRID
        recyclerView.visibility = View.VISIBLE
        fullImageView.visibility = View.GONE
        imageInfoPanel.visibility = View.GONE
        emptyView.visibility = View.GONE

        recyclerView.adapter = GalleryAdapter(images) { image ->
            showFullImage(image)
        }
    }

    private fun showFullImage(image: ShootImage) {
        viewMode = ViewMode.FULLSCREEN
        currentImage = image

        recyclerView.visibility = View.GONE
        fullImageView.visibility = View.VISIBLE
        imageInfoPanel.visibility = View.VISIBLE
        emptyView.visibility = View.GONE

        // Cancel any previous full-image load
        fullImageLoadJob?.cancel()
        fullImageView.setImageResource(R.drawable.placeholder_image)

        // Load the full image scaled to screen width to avoid OOM
        val screenWidth = resources.displayMetrics.widthPixels
        fullImageLoadJob = CoroutineScope(Dispatchers.IO).launch {
            val bitmap = ImageLoader.loadBitmap(this@GalleryActivity, image.imagePath, screenWidth)
            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    fullImageView.setImageBitmap(bitmap)
                } else {
                    fullImageView.setImageResource(R.drawable.placeholder_image)
                }
            }
        }

        // Update info panel
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
        tvImageDate.text = "Date: ${dateFormat.format(Date(image.capturedAt))}"
        tvImageLocation.text = "Location: ${image.locationId}"
        
        val settings = buildString {
            append("ISO: ${if (image.iso > 0) image.iso else "Auto"}, ")
            append("WB: ${if (image.whiteBalanceK > 0) "${image.whiteBalanceK}K" else "Auto"}")
        }
        tvImageSettings.text = settings

        supportActionBar?.title = "Image"
    }

    private fun showGridView() {
        currentImage = null
        loadGallery()
        supportActionBar?.title = "Gallery"
    }

    private fun shareCurrentImage() {
        currentImage?.let { image ->
            val shareIntent = when {
                image.imagePath.startsWith("content://") -> {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(image.imagePath))
                        putExtra(Intent.EXTRA_SUBJECT, "Pixel Hunter Image")
                    }
                }
                File(image.imagePath).exists() -> {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this,
                        "${packageName}.fileprovider",
                        File(image.imagePath)
                    )
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Pixel Hunter Image")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                else -> null
            }
            shareIntent?.let {
                startActivity(Intent.createChooser(it, "Share Image"))
            }
        }
    }

    private fun deleteCurrentImage() {
        currentImage?.let { image ->
            AlertDialog.Builder(this)
                .setTitle("Delete Image")
                .setMessage("Are you sure you want to delete this image?")
                .setPositiveButton("Delete") { _, _ ->
                    performDelete(image)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun performDelete(image: ShootImage) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = PixelHunterDatabase.getInstance(this@GalleryActivity)
            db.imageDao().deleteImage(image)

            // Delete the actual image file — handle both content:// and file paths
            if (image.imagePath.startsWith("content://")) {
                try {
                    contentResolver.delete(Uri.parse(image.imagePath), null, null)
                } catch (e: Exception) {
                    android.util.Log.e("Gallery", "MediaStore delete failed", e)
                }
            } else {
                File(image.imagePath).delete()
            }

            // Thumbnail is always a local file path
            File(image.thumbnailPath).delete()

            withContext(Dispatchers.Main) {
                Toast.makeText(this@GalleryActivity, "Image deleted", Toast.LENGTH_SHORT).show()
                showGridView()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                if (viewMode == ViewMode.FULLSCREEN) {
                    showGridView()
                } else {
                    finish()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        if (viewMode == ViewMode.FULLSCREEN) {
            showGridView()
        } else {
            super.onBackPressed()
        }
    }
}
