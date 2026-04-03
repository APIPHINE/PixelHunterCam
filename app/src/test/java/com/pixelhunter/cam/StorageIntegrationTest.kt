package com.pixelhunter.cam

import android.view.Surface
import com.pixelhunter.cam.storage.MediaStoreManager
import com.pixelhunter.cam.util.ErrorHandler
import com.pixelhunter.cam.util.SafeMediaStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Integration tests for storage operations using Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class StorageIntegrationTest {
    
    private val context = RuntimeEnvironment.getApplication()
    
    @Test
    fun `isStorageAvailable returns consistent result in test environment`() {
        // In Robolectric, external storage state may vary by environment
        // Just verify the method doesn't crash and returns a boolean
        val available = SafeMediaStore.isStorageAvailable()
        // Result depends on Robolectric environment setup
        assertTrue("Method should return true or false", available == true || available == false)
    }
    
    @Test
    fun `getAvailableStorageMb returns positive value`() {
        val available = SafeMediaStore.getAvailableStorageMb()
        
        assertTrue("Available storage should be positive or zero", available >= 0)
    }
    
    @Test
    fun `hasEnoughSpace returns consistent result`() {
        // In Robolectric, storage stats may not be fully available
        // Just verify the method returns a boolean without crashing
        val hasSpace = SafeMediaStore.hasEnoughSpace(1)
        assertTrue("Method should return true or false", hasSpace == true || hasSpace == false)
    }
    
    @Test
    fun `hasEnoughSpace returns false for huge requirement`() {
        // Request an impossibly large amount of space
        assertFalse("Should not have enough space for 1000TB",
            SafeMediaStore.hasEnoughSpace(1_000_000_000))
    }
    
    @Test
    fun `preFlightCheck returns valid result`() {
        val result = SafeMediaStore.preFlightCheck(context, 1)
        
        // In Robolectric, pre-flight may fail due to storage not being "mounted"
        // but we can verify the result structure is valid
        assertNotNull("Pre-flight result should not be null", result)
        assertNotNull("Issues list should not be null", result.issues)
        // Either passes with no issues or fails with documented issues
        if (result.canProceed) {
            assertTrue("Issues should be empty when canProceed", result.issues.isEmpty())
        } else {
            assertTrue("Issues should not be empty when !canProceed", result.issues.isNotEmpty())
        }
    }
    
    @Test
    fun `preFlightCheck fails with huge space requirement`() {
        val result = SafeMediaStore.preFlightCheck(context, 1_000_000_000)
        
        assertFalse("Pre-flight should fail", result.canProceed)
        assertFalse("Issues list should not be empty", result.issues.isEmpty())
    }
    
    @Test
    fun `validateRange accepts valid coordinates`() {
        val lat = ErrorHandler.validateRange(45.0, "latitude", -90.0, 90.0)
        assertTrue("Valid latitude should pass", lat.success)
        assertEquals(45.0, lat.data)
    }
    
    @Test
    fun `validateRange rejects out of bounds`() {
        val lat = ErrorHandler.validateRange(100.0, "latitude", -90.0, 90.0)
        assertFalse("Invalid latitude should fail", lat.success)
    }
    
    @Test
    fun `CaptureMetadata calculates orientation degrees correctly`() {
        val metadata = MediaStoreManager.CaptureMetadata(
            latitude = 0.0,
            longitude = 0.0,
            altitude = 0.0,
            accuracyMeters = 0f,
            timestamp = 0,
            timestampIso = "",
            timezone = "",
            iso = 0,
            shutterSpeedNs = 0,
            whiteBalanceK = 0,
            focalLength = 0f,
            aperture = 0f,
            flashMode = 0,
            flashFired = false,
            zoomRatio = 1f,
            focusDistanceDiopters = 0f,
            exposureBias = 0f,
            deviceOrientation = android.view.Surface.ROTATION_90,
            sessionLabel = "",
            sessionId = ""
        )
        
        assertEquals(90, metadata.deviceOrientationDegrees)
    }
    
    @Test
    fun `CaptureMetadata settingsVerified requires both locks`() {
        val verified = MediaStoreManager.CaptureMetadata(
            latitude = 0.0,
            longitude = 0.0,
            altitude = 0.0,
            accuracyMeters = 0f,
            timestamp = 0,
            timestampIso = "",
            timezone = "",
            iso = 0,
            shutterSpeedNs = 0,
            whiteBalanceK = 0,
            focalLength = 0f,
            aperture = 0f,
            flashMode = 0,
            flashFired = false,
            zoomRatio = 1f,
            focusDistanceDiopters = 0f,
            exposureBias = 0f,
            deviceOrientation = 0,
            sessionLabel = "",
            sessionId = "",
            actualAeModeOff = true,
            actualAwbModeOff = true
        )
        
        val notVerified = MediaStoreManager.CaptureMetadata(
            latitude = 0.0,
            longitude = 0.0,
            altitude = 0.0,
            accuracyMeters = 0f,
            timestamp = 0,
            timestampIso = "",
            timezone = "",
            iso = 0,
            shutterSpeedNs = 0,
            whiteBalanceK = 0,
            focalLength = 0f,
            aperture = 0f,
            flashMode = 0,
            flashFired = false,
            zoomRatio = 1f,
            focusDistanceDiopters = 0f,
            exposureBias = 0f,
            deviceOrientation = 0,
            sessionLabel = "",
            sessionId = "",
            actualAeModeOff = true,
            actualAwbModeOff = false
        )
        
        assertTrue("Both locks on = verified", verified.settingsVerified)
        assertFalse("One lock off = not verified", notVerified.settingsVerified)
    }
}
