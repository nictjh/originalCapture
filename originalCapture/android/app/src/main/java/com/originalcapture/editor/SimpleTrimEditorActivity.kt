package com.originalcapture.editor

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.originalcapture.R
import com.originalcapture.editor.model.TrimTracker
import java.io.File
import java.util.*
import kotlin.math.max
import kotlin.math.min

class SimpleTrimEditorActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"
        private const val TAG = "TrimEditor"
        private const val UPDATE_INTERVAL = 100L
        private const val THUMBNAIL_COUNT = 20
        private const val THUMBNAIL_WIDTH = 80
        private const val THUMBNAIL_HEIGHT = 60
    }

    // UI Components
    private lateinit var closeBtn: ImageButton
    private lateinit var exportBtn: TextView
    private lateinit var videoView: VideoView
    private lateinit var playPauseBtn: ImageButton
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView

    // Timeline components
    private lateinit var timelineTrack: View
    private lateinit var thumbnailScrollView: HorizontalScrollView
    private lateinit var thumbnailContainer: LinearLayout
    private lateinit var trimOverlay: View
    private lateinit var leftTrimHandleContainer: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var rightTrimHandleContainer: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var leftTrimHandle: View
    private lateinit var rightTrimHandle: View
    private lateinit var positionIndicator: View
    private lateinit var timelineTouchArea: View
    private lateinit var trimStartTimeText: TextView
    private lateinit var trimEndTimeText: TextView
    private lateinit var trimDurationText: TextView

    // State
    private lateinit var trimTracker: TrimTracker
    private var videoPath: String = ""
    private var videoDurationMs: Long = 0
    private var isPlaying = false
    private var trimStartMs: Long = 0
    private var trimEndMs: Long = 0
    private var timelineWidth = 0
    private var isDraggingLeft = false
    private var isDraggingRight = false

    private val timelineHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateTimeline()
            if (isPlaying) {
                timelineHandler.postDelayed(this, UPDATE_INTERVAL)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_trim_editor)

        videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH) ?: run {
            finishWithError("No video path provided")
            return
        }

        initViews()
        setupVideo()
        setupTrimHandles()
        generateThumbnails()
        trimTracker = TrimTracker()
    }

    private fun initViews() {
        closeBtn = findViewById(R.id.closeBtn)
        exportBtn = findViewById(R.id.exportBtn)
        videoView = findViewById(R.id.videoView)
        playPauseBtn = findViewById(R.id.playPauseBtn)
        currentTimeText = findViewById(R.id.currentTimeText)
        totalTimeText = findViewById(R.id.totalTimeText)

        timelineTrack = findViewById(R.id.timelineTrack)
        thumbnailScrollView = findViewById(R.id.thumbnailScrollView)
        thumbnailContainer = findViewById(R.id.thumbnailContainer)
        trimOverlay = findViewById(R.id.trimOverlay)
        leftTrimHandleContainer = findViewById(R.id.leftTrimHandleContainer)
        rightTrimHandleContainer = findViewById(R.id.rightTrimHandleContainer)
        leftTrimHandle = findViewById(R.id.leftTrimHandle)
        rightTrimHandle = findViewById(R.id.rightTrimHandle)
        positionIndicator = findViewById(R.id.positionIndicator)
        timelineTouchArea = findViewById(R.id.timelineTouchArea)
        trimStartTimeText = findViewById(R.id.trimStartTimeText)
        trimEndTimeText = findViewById(R.id.trimEndTimeText)
        trimDurationText = findViewById(R.id.trimDurationText)

        setupClickListeners()
    }

    private fun setupClickListeners() {
        closeBtn.setOnClickListener { onBackPressed() }
        exportBtn.setOnClickListener { exportTrim() }
        playPauseBtn.setOnClickListener { togglePlayback() }

        // Timeline seeking - separate from trim handles
        timelineTouchArea.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val x = event.x
                    val ratio = x / view.width
                    seekToPosition(ratio.coerceIn(0f, 1f))
                    true
                }
                else -> false
            }
        }
    }

    private fun setupVideo() {
        try {
            val videoUri = Uri.fromFile(File(videoPath))
            videoView.setVideoURI(videoUri)

            // Get video metadata
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoPath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            videoDurationMs = duration?.toLong() ?: 0
            retriever.release()

            // Initialize trim to full video
            trimStartMs = 0
            trimEndMs = videoDurationMs

            // Update UI
            totalTimeText.text = formatTime(videoDurationMs)
            updateTrimInfo()

            // Wait for layout to get timeline width
            timelineTrack.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    timelineTrack.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    timelineWidth = timelineTrack.width
                    updateTrimHandles()
                    setupTrimHandles() // Setup after we have dimensions
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up video", e)
            finishWithError("Failed to load video: ${e.message}")
        }

        videoView.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            mediaPlayer.setOnSeekCompleteListener {
                updatePositionIndicator()
            }
        }

        videoView.setOnCompletionListener {
            // If trimmed, loop within trim bounds
            if (trimStartMs > 0 || trimEndMs < videoDurationMs) {
                videoView.seekTo(trimStartMs.toInt())
                if (isPlaying) {
                    videoView.start()
                }
            } else {
                isPlaying = false
                playPauseBtn.setImageResource(R.drawable.ic_play_circle)
                timelineHandler.removeCallbacks(updateRunnable)
            }
        }

        videoView.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "VideoView error: what=$what, extra=$extra")
            finishWithError("Video playback error")
            true
        }
    }

    private fun generateThumbnails() {
        Thread {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(videoPath)

                val thumbnails = mutableListOf<Bitmap>()
                val interval = videoDurationMs / THUMBNAIL_COUNT

                for (i in 0 until THUMBNAIL_COUNT) {
                    val timeUs = (i * interval * 1000) // Convert to microseconds
                    val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

                    if (bitmap != null) {
                        val scaledBitmap = ThumbnailUtils.extractThumbnail(bitmap, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)
                        thumbnails.add(scaledBitmap)
                        bitmap.recycle()
                    }
                }

                retriever.release()

                runOnUiThread {
                    displayThumbnails(thumbnails)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error generating thumbnails", e)
                runOnUiThread {
                    showMessage("Could not generate thumbnails")
                }
            }
        }.start()
    }

    private fun displayThumbnails(thumbnails: List<Bitmap>) {
        thumbnailContainer.removeAllViews()

        thumbnails.forEach { bitmap ->
            val imageView = ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)
            }
            thumbnailContainer.addView(imageView)
        }
    }

    private fun setupTrimHandles() {
        // Make handles larger and easier to grab
        val touchPadding = 20.dpToPx()

        // Left handle touch listener with expanded touch area
        leftTrimHandleContainer.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingLeft = true
                    pauseVideo()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingLeft) {
                        val timelineRect = IntArray(2)
                        timelineTrack.getLocationOnScreen(timelineRect)
                        val parentRect = IntArray(2)
                        view.parent.let { parent ->
                            (parent as View).getLocationOnScreen(parentRect)
                        }

                        // Calculate position relative to timeline
                        val x = event.rawX - timelineRect[0]
                        moveTrimHandle(true, x)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingLeft = false
                    // Immediately show the trimmed start frame
                    videoView.seekTo(trimStartMs.toInt())
                    true
                }
                else -> false
            }
        }

        // Right handle touch listener with expanded touch area
        rightTrimHandleContainer.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingRight = true
                    pauseVideo()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingRight) {
                        val timelineRect = IntArray(2)
                        timelineTrack.getLocationOnScreen(timelineRect)
                        val parentRect = IntArray(2)
                        view.parent.let { parent ->
                            (parent as View).getLocationOnScreen(parentRect)
                        }

                        // Calculate position relative to timeline
                        val x = event.rawX - timelineRect[0]
                        moveTrimHandle(false, x)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingRight = false
                    // Immediately show the trimmed end frame
                    videoView.seekTo(trimEndMs.toInt())
                    true
                }
                else -> false
            }
        }
    }

    private fun moveTrimHandle(isLeft: Boolean, x: Float) {
        if (timelineWidth == 0) return

        val constrainedX = x.coerceIn(0f, timelineWidth.toFloat())
        val ratio = constrainedX / timelineWidth
        val timeMs = (ratio * videoDurationMs).toLong()

        if (isLeft) {
            // Ensure minimum 1 second gap
            val maxTime = (trimEndMs - 1000).coerceAtLeast(0)
            val newStartTime = timeMs.coerceAtMost(maxTime)
            if (newStartTime != trimStartMs) {
                trimStartMs = newStartTime
                // Show preview of trim start immediately
                videoView.seekTo(trimStartMs.toInt())
                updateTrimHandles()
                updateTrimInfo()
            }
        } else {
            // Ensure minimum 1 second gap
            val minTime = (trimStartMs + 1000).coerceAtMost(videoDurationMs)
            val newEndTime = timeMs.coerceAtLeast(minTime)
            if (newEndTime != trimEndMs) {
                trimEndMs = newEndTime
                // Show preview of trim end immediately
                videoView.seekTo(trimEndMs.toInt())
                updateTrimHandles()
                updateTrimInfo()
            }
        }
    }

    private fun updateTrimHandles() {
        if (timelineWidth == 0) return

        val startRatio = trimStartMs.toFloat() / videoDurationMs
        val endRatio = trimEndMs.toFloat() / videoDurationMs

        val startX = (startRatio * timelineWidth)
        val endX = (endRatio * timelineWidth)

        // Update left handle container position
        val leftParams = leftTrimHandleContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        leftParams.leftMargin = (32.dpToPx() + startX - 10.dpToPx()).toInt() // Center the handle
        leftTrimHandleContainer.layoutParams = leftParams

        // Update right handle container position
        val rightParams = rightTrimHandleContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        rightParams.leftMargin = (32.dpToPx() + endX - 10.dpToPx()).toInt() // Center the handle
        rightTrimHandleContainer.layoutParams = rightParams

        // Update trim overlay to show selected region
        val overlayParams = trimOverlay.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        overlayParams.leftMargin = (32.dpToPx() + startX).toInt()
        overlayParams.rightMargin = (timelineWidth + 32.dpToPx() - endX).toInt()
        trimOverlay.layoutParams = overlayParams
    }

    private fun updatePositionIndicator() {
        if (timelineWidth == 0) return

        val currentPosition = videoView.currentPosition.toLong()
        val isTrimmed = trimStartMs > 0 || trimEndMs < videoDurationMs

        val ratio = if (isTrimmed) {
            // Show position relative to entire timeline, not just trimmed section
            currentPosition.toFloat() / videoDurationMs
        } else {
            currentPosition.toFloat() / videoDurationMs
        }

        val x = (ratio * timelineWidth)

        val params = positionIndicator.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        params.leftMargin = (32.dpToPx() + x).toInt()
        positionIndicator.layoutParams = params
    }

    private fun updateTrimInfo() {
        trimStartTimeText.text = formatTime(trimStartMs)
        trimEndTimeText.text = formatTime(trimEndMs)
        val duration = trimEndMs - trimStartMs
        trimDurationText.text = "Duration: ${formatTime(duration)}"
    }

    private fun togglePlayback() {
        if (isPlaying) {
            pauseVideo()
        } else {
            playVideo()
        }
    }

    private fun playVideo() {
        videoView.start()
        isPlaying = true
        playPauseBtn.setImageResource(R.drawable.ic_pause_circle)
        timelineHandler.post(updateRunnable)
    }

    private fun pauseVideo() {
        videoView.pause()
        isPlaying = false
        playPauseBtn.setImageResource(R.drawable.ic_play_circle)
        timelineHandler.removeCallbacks(updateRunnable)
    }

    private fun updateTimeline() {
        if (videoView.isPlaying) {
            val currentPosition = videoView.currentPosition
            currentTimeText.text = formatTime(currentPosition.toLong())
            updatePositionIndicator()
        }
    }

    private fun seekToPosition(ratio: Float) {
        val position = (ratio * videoDurationMs).toInt()
        videoView.seekTo(position)
        currentTimeText.text = formatTime(position.toLong())
        updatePositionIndicator()
    }

    private fun exportTrim() {
        if (trimStartMs == 0L && trimEndMs == videoDurationMs) {
            showMessage("No trim applied")
            return
        }

        try {
            val trim = trimTracker.setTrim(
                originalPath = videoPath,
                startMs = trimStartMs,
                endMs = trimEndMs,
                originalDurationMs = videoDurationMs
            )

            // Save trim data
            val trimFile = File(filesDir, "trim_${System.currentTimeMillis()}.json")
            val trimmedDuration = trimEndMs - trimStartMs
            val timeSaved = videoDurationMs - trimmedDuration

            val enhancedJson = buildString {
                appendLine("{")
                appendLine("  \"trimId\": \"${trim.trimId}\",")
                appendLine("  \"originalPath\": \"$videoPath\",")
                appendLine("  \"startTimeMs\": $trimStartMs,")
                appendLine("  \"endTimeMs\": $trimEndMs,")
                appendLine("  \"originalDurationMs\": $videoDurationMs,")
                appendLine("  \"trimmedDurationMs\": $trimmedDuration,")
                appendLine("  \"timeSavedMs\": $timeSaved,")
                appendLine("  \"timestamp\": ${System.currentTimeMillis()},")
                appendLine("  \"isTrimmed\": true")
                appendLine("}")
            }

            trimFile.writeText(enhancedJson)

            // Return result
            val resultIntent = Intent().apply {
                putExtra("action", "trim_saved")
                putExtra("ok", true)
                putExtra("originalPath", videoPath)
                putExtra("trimFile", trimFile.absolutePath)
                putExtra("startTimeMs", trimStartMs)
                putExtra("endTimeMs", trimEndMs)
                putExtra("trimmedDurationMs", trimmedDuration)
                putExtra("timeSavedMs", timeSaved)
                putExtra("message", "Video trimmed: ${formatTime(trimmedDuration)} (saved ${formatTime(timeSaved)})")
            }

            setResult(RESULT_OK, resultIntent)
            finish()

        } catch (e: Exception) {
            Log.e(TAG, "Error exporting trim", e)
            showMessage("Error saving trim: ${e.message}")
        }
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun showMessage(message: String) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.snackbar_bg))
            .setTextColor(ContextCompat.getColor(this, android.R.color.white))
            .show()
    }

    private fun finishWithError(message: String) {
        Log.e(TAG, message)
        val resultIntent = Intent().apply {
            putExtra("action", "error")
            putExtra("ok", false)
            putExtra("message", message)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        val isTrimmed = trimStartMs > 0 || trimEndMs < videoDurationMs

        if (isTrimmed) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Unsaved Changes")
                .setMessage("You have unsaved trim changes. Exit without saving?")
                .setPositiveButton("Exit") { _, _ -> super.onBackPressed() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        pauseVideo()
    }

    override fun onDestroy() {
        super.onDestroy()
        timelineHandler.removeCallbacks(updateRunnable)
    }
}