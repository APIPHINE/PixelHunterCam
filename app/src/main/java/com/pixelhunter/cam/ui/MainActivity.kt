package com.pixelhunter.cam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pixelhunter.cam.R
import com.pixelhunter.cam.camera.EnhancedCameraController
import com.pixelhunter.cam.databinding.ActivityMainBinding
import com.pixelhunter.cam.location.HighAccuracyLocationManager
import com.pixelhunter.cam.location.LocationMemory
import com.pixelhunter.cam.session.FlagType
import com.pixelhunter.cam.session.SessionFlag
import com.pixelhunter.cam.session.Severity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Enhanced Main Activity with:
 * - Tap to focus with visual feedback
 * - Pinch to zoom
 * - Grid overlay toggle
 * - Real-time histogram
 * - Camera metadata display
 * - Clear settings reset UI
 * - GPS accuracy indicator
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private var currentZoom = 1.0f

    private val requiredPermissions: Array<String> get() {
        val base = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            base.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            base.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return base.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.all { it.value }) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera and location permissions required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGestureDetectors()
        setupButtons()
        setupOverlaySlider()
        setupZoomSlider()
        observeState()
        observeCameraState()

        if (hasPermissions()) {
            startCamera()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // ─── Gesture Detection ────────────────────────────────────────

    private fun setupGestureDetectors() {
        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val scale = detector.scaleFactor
                    currentZoom *= scale
                    currentZoom = currentZoom.coerceIn(1.0f, viewModel.getMaxZoom())
                    viewModel.setZoom(currentZoom)
                    binding.zoomSlider.value = currentZoom
                    return true
                }
            })

        gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    handleTapToFocus(e)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    currentZoom = 1.0f
                    viewModel.setZoom(1.0f)
                    binding.zoomSlider.value = 1.0f
                    return true
                }
            })

        binding.previewView.setOnTouchListener { _, event ->
            var handled = scaleGestureDetector.onTouchEvent(event)
            handled = gestureDetector.onTouchEvent(event) || handled
            if (event.action == MotionEvent.ACTION_UP) {
                binding.previewView.performClick()
            }
            handled || super.onTouchEvent(event)
        }
    }

    private fun handleTapToFocus(event: MotionEvent) {
        viewModel.handleTapToFocus(event, binding.previewView)
        binding.focusOverlayView.showFocusPoint(event.x, event.y)
        binding.focusOverlayView.postDelayed({
            binding.focusOverlayView.setFocusResult(true)
        }, 500)
    }

    // ─── Camera Startup ───────────────────────────────────────────

    private fun startCamera() {
        lifecycleScope.launch {
            viewModel.startCamera(this@MainActivity, binding.previewView)
            viewModel.checkNearbyLocations()
        }
    }

    // ─── Button Setup ─────────────────────────────────────────────

    private fun setupButtons() {
        // Shutter
        binding.btnCapture.setOnClickListener {
            viewModel.capturePhoto()
        }
        
        // Emergency reset on long press
        binding.btnCapture.setOnLongClickListener {
            viewModel.emergencyReset()
            Toast.makeText(this, "Reset capture state", Toast.LENGTH_SHORT).show()
            true
        }

        // Session lock / unlock with clear visual feedback
        binding.btnSessionLock.setOnClickListener {
            if (viewModel.sessionManager.settings.value.isLocked) {
                showUnlockConfirmation()
            }
        }

        // New Session button
        binding.btnNewSession?.setOnClickListener {
            showNewSessionConfirmation()
        }

        // Grid toggle
        binding.btnGrid.setOnClickListener {
            val mode = viewModel.cycleGridMode()
            val modeName = when (mode) {
                com.pixelhunter.cam.ui.view.GridOverlayView.GridMode.NONE -> "Grid Off"
                com.pixelhunter.cam.ui.view.GridOverlayView.GridMode.RULE_OF_THIRDS -> "Rule of Thirds"
                com.pixelhunter.cam.ui.view.GridOverlayView.GridMode.GOLDEN_RATIO -> "Golden Ratio"
                com.pixelhunter.cam.ui.view.GridOverlayView.GridMode.SQUARE -> "Square"
                com.pixelhunter.cam.ui.view.GridOverlayView.GridMode.CROSSHAIR -> "Crosshair"
            }
            Toast.makeText(this, modeName, Toast.LENGTH_SHORT).show()
        }

        // Histogram toggle
        binding.btnHistogram.setOnClickListener {
            val showing = viewModel.toggleHistogram()
            binding.histogramView.isVisible = showing
            Toast.makeText(this, if (showing) "Histogram On" else "Histogram Off", Toast.LENGTH_SHORT).show()
        }

        // Lens switch
        binding.btnLens.setOnClickListener {
            if (viewModel.switchLens()) {
                viewModel.rebindCamera(this, binding.previewView)
                Toast.makeText(this, "Switched to ${viewModel.getCurrentLensName()}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No additional lenses available", Toast.LENGTH_SHORT).show()
            }
        }

        // Flash toggle
        binding.btnFlash.setOnClickListener {
            val modes = listOf(
                androidx.camera.core.ImageCapture.FLASH_MODE_AUTO to "Auto",
                androidx.camera.core.ImageCapture.FLASH_MODE_ON to "On",
                androidx.camera.core.ImageCapture.FLASH_MODE_OFF to "Off"
            )
            val currentMode = viewModel.getFlashMode()
            val nextIndex = (modes.indexOfFirst { it.first == currentMode } + 1) % modes.size
            val (newMode, name) = modes[nextIndex]
            viewModel.setFlashMode(newMode)
            binding.btnFlash.text = "⚡$name"
        }

        // Overlay toggle
        binding.btnOverlay.setOnClickListener {
            if (viewModel.uiState.value.activeOverlayBitmap != null) {
                viewModel.clearOverlay()
            } else {
                showOverlayPicker()
            }
        }

        // Dismiss flag banner
        binding.btnDismissFlag.setOnClickListener {
            viewModel.dismissFlags()
        }

        // Request API review
        binding.btnReviewSettings.setOnClickListener {
            viewModel.requestApiReview()
        }
        
        // Quick reset button (shown when drift detected)
        binding.btnQuickReset?.setOnClickListener {
            viewModel.unlockSession()
        }

        // Gallery button
        binding.btnGallery.setOnClickListener {
            GalleryActivity.start(this)
        }

        // View last image button
        binding.btnViewLast?.setOnClickListener {
            viewModel.uiState.value.lastCapturePath.let { path ->
                if (path.isNotEmpty()) {
                    GalleryActivity.start(this, path)
                }
            }
        }
    }

    private fun setupOverlaySlider() {
        binding.sliderOverlayOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                viewModel.setOverlayOpacity(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun setupZoomSlider() {
        binding.zoomSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentZoom = value
                viewModel.setZoom(value)
            }
        }
    }

    // ─── State Observation ────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            combine(
                viewModel.sessionManager.settings,
                viewModel.uiState
            ) { settings, ui -> Pair(settings, ui) }.collect { (settings, ui) ->

                updateSessionStatusUI(settings, ui)
                updateGpsIndicator(ui)

                // Location
                binding.tvLocation.text = if (settings.locationLabel.isNotEmpty())
                    "📍 ${settings.locationLabel}" else ""
                binding.tvLocation.visibility =
                    if (settings.locationLabel.isNotEmpty()) View.VISIBLE else View.GONE

                // Capture count
                binding.tvCaptureCount.text = "${ui.captureCount} shots"

                // Show "View Last" button if we have a last capture
                binding.btnViewLast?.visibility = if (ui.lastCapturePath.isNotEmpty()) View.VISIBLE else View.GONE

                // Capturing state
                binding.btnCapture.isEnabled = !ui.isCapturing
                binding.progressCapture.visibility =
                    if (ui.isCapturing) View.VISIBLE else View.GONE

                // Status message
                if (ui.statusMessage.isNotEmpty()) {
                    binding.tvStatus.text = ui.statusMessage
                    binding.tvStatus.visibility = View.VISIBLE
                } else {
                    binding.tvStatus.visibility = View.GONE
                }

                // Flag banner with reset option for drift
                if (ui.showFlagPrompt && ui.lastCaptureFlags.isNotEmpty()) {
                    showFlagBanner(ui.lastCaptureFlags, settings.isLocked)
                } else {
                    binding.flagBanner.visibility = View.GONE
                }

                // Show quick reset button when drift detected
                val hasDrift = ui.lastCaptureFlags.any { 
                    it.type == FlagType.EXPOSURE_DRIFT || it.type == FlagType.WHITE_BALANCE_DRIFT 
                }
                binding.btnQuickReset?.visibility = if (hasDrift && settings.isLocked) View.VISIBLE else View.GONE

                // Ghost overlay
                if (ui.activeOverlayBitmap != null) {
                    binding.overlayImageView.setImageBitmap(ui.activeOverlayBitmap)
                    binding.overlayImageView.alpha = ui.overlayOpacity
                    binding.overlayImageView.visibility = View.VISIBLE
                    binding.overlayControls.visibility = View.VISIBLE
                    binding.sliderOverlayOpacity.progress = (ui.overlayOpacity * 100).toInt()
                    binding.btnOverlay.text = "Clear Ghost"
                } else {
                    binding.overlayImageView.visibility = View.GONE
                    binding.overlayControls.visibility = View.GONE
                    binding.btnOverlay.text = "Ghost"
                }

                // Location prompt
                if (ui.showLocationPrompt && ui.nearbyLocations.isNotEmpty()) {
                    showLocationPrompt(ui.nearbyLocations)
                }
            }
        }
    }
    
    private fun updateSessionStatusUI(settings: com.pixelhunter.cam.session.SessionSettings, ui: EnhancedCameraUiState) {
        val isLocked = settings.isLocked
        
        // Main status text
        binding.tvSessionStatus.text = if (isLocked) {
            "🔒 SESSION LOCKED\n${settings.toDisplayString()}"
        } else {
            "🔓 AUTO MODE\nTap shutter to lock settings"
        }
        
        // Background color based on state
        val bgColor = when {
            !isLocked -> R.color.session_auto
            ui.lastCaptureFlags.any { it.severity == Severity.HIGH } -> R.color.flag_high
            ui.lastCaptureFlags.any { it.severity == Severity.MEDIUM } -> R.color.flag_medium
            else -> R.color.session_locked
        }
        binding.tvSessionStatus.setBackgroundResource(bgColor)
        
        // Lock button text
        binding.btnSessionLock.text = if (isLocked) "🔓 Unlock" else "Waiting..."
        binding.btnSessionLock.isEnabled = isLocked
    }
    
    private fun updateGpsIndicator(ui: EnhancedCameraUiState) {
        ui.lastGpsAccuracy?.let { accuracy ->
            val (icon, color) = when (accuracy) {
                HighAccuracyLocationManager.AccuracyRating.EXCELLENT -> "📡" to android.graphics.Color.GREEN
                HighAccuracyLocationManager.AccuracyRating.GOOD -> "📡" to android.graphics.Color.CYAN
                HighAccuracyLocationManager.AccuracyRating.ACCEPTABLE -> "📡" to android.graphics.Color.YELLOW
                else -> "⚠️" to android.graphics.Color.RED
            }
            binding.tvGpsStatus?.text = "$icon GPS: ${accuracy.name}"
            binding.tvGpsStatus?.setTextColor(color)
            binding.tvGpsStatus?.visibility = View.VISIBLE
        } ?: run {
            binding.tvGpsStatus?.visibility = View.GONE
        }
    }

    private fun observeCameraState() {
        lifecycleScope.launch {
            viewModel.cameraState.collect { state ->
                // Ensure valueTo is always > valueFrom (1.0f) to avoid Slider crash
                val maxZoom = state.maxZoom.coerceAtLeast(1.01f)
                if (binding.zoomSlider.valueTo != maxZoom) {
                    binding.zoomSlider.valueTo = maxZoom
                }
                
                val isoText = if (state.iso > 0) "ISO ${state.iso}" else "ISO Auto"
                val shutterText = if (state.shutterSpeedNs > 0) {
                    val ms = state.shutterSpeedNs / 1_000_000
                    if (ms >= 1000) "${ms/1000}s" else "${ms}ms"
                } else "Auto"
                
                binding.tvCameraMetadata.text = "$isoText · $shutterText · ${state.activeCameraName}"
                binding.tvCameraMetadata.visibility = View.VISIBLE
            }
        }
    }

    // ─── UI Helpers ───────────────────────────────────────────────

    private fun showFlagBanner(flags: List<SessionFlag>, isLocked: Boolean) {
        val topFlag = flags.maxByOrNull { it.severity.ordinal }!!
        binding.flagBanner.visibility = View.VISIBLE
        binding.tvFlagMessage.text = topFlag.message

        // Show reset button for drift flags when locked
        val isDrift = topFlag.type == FlagType.EXPOSURE_DRIFT || topFlag.type == FlagType.WHITE_BALANCE_DRIFT
        binding.btnReviewSettings.visibility =
            if (topFlag.suggestApiReview || (isDrift && isLocked)) View.VISIBLE else View.GONE
        
        binding.btnReviewSettings.text = if (isDrift && isLocked) "🔄 Reset" else "Review"

        val color = when (topFlag.severity) {
            Severity.HIGH -> getColor(R.color.flag_high)
            Severity.MEDIUM -> getColor(R.color.flag_medium)
            Severity.LOW -> getColor(R.color.flag_low)
        }
        binding.flagBanner.setBackgroundColor(color)
    }

    private var locationPromptShown = false

    private fun showLocationPrompt(locations: List<LocationMemory.NearbyResult>) {
        if (locationPromptShown) return
        locationPromptShown = true

        val top = locations.first()
        val distStr = "${top.distanceMeters.toInt()}m away"
        val shotStr = "${top.imageCount} shots logged"

        AlertDialog.Builder(this)
            .setTitle("📍 Known Location")
            .setMessage("${top.location.label}\n$distStr · $shotStr\n\nRestore settings from last visit?\n${top.suggestedSettings?.toDisplayString() ?: "No saved settings"}")
            .setPositiveButton("Yes, restore") { _, _ ->
                viewModel.applyLocationSettings(top)
            }
            .setNegativeButton("No, fresh start") { _, _ -> }
            .show()
    }

    private fun showOverlayPicker() {
        val images = viewModel.uiState.value.overlayImages
        if (images.isEmpty()) {
            Toast.makeText(this, "No past images at this location yet", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = images.map {
            android.text.format.DateFormat.format("MMM dd, HH:mm", it.capturedAt).toString()
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Ghost Image")
            .setItems(labels) { _, index ->
                viewModel.selectOverlayImage(images[index].imagePath)
            }
            .show()
    }

    private fun showUnlockConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("🔓 Unlock Session?")
            .setMessage("This will clear all locked settings and return to auto mode.\n\nYou'll need to tap the shutter again to lock new settings.")
            .setPositiveButton("Unlock") { _, _ -> viewModel.unlockSession() }
            .setNegativeButton("Keep Locked", null)
            .show()
    }
    
    private fun showNewSessionConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("📁 New Session?")
            .setMessage("Start a new capture session? This will reset the shot counter.")
            .setPositiveButton("Start New") { _, _ -> viewModel.startNewSession() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hasPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

// Extension for View visibility
private var View.isVisible: Boolean
    get() = visibility == View.VISIBLE
    set(value) { visibility = if (value) View.VISIBLE else View.GONE }
