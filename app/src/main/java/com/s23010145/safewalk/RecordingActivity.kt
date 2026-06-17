package com.s23010145.safewalk

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingFile(
    val name: String,
    val path: String,
    val date: String,
    val isVideo: Boolean
)

class RecordingActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var cardModeAudio: CardView
    private lateinit var cardModeVideo: CardView
    private lateinit var ivAudioIcon: ImageView
    private lateinit var ivVideoIcon: ImageView
    private lateinit var tvAudioLabel: TextView
    private lateinit var tvVideoLabel: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnRecord: MaterialButton
    private lateinit var tvRecordingBadge: TextView
    private lateinit var surfaceView: SurfaceView        // preview surface for video
    private lateinit var recyclerRecordings: RecyclerView
    private lateinit var tvNoRecordings: TextView

    private var isVideoMode     = false
    private var isRecording     = false
    private var mediaRecorder: MediaRecorder? = null
    private var currentFilePath = ""
    private var timerSeconds    = 0
    private val handler         = Handler(Looper.getMainLooper())
    private val recordings      = mutableListOf<RecordingFile>()
    private lateinit var recordingsAdapter: RecordingsAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) startRecording()
        else Toast.makeText(this, "Permissions required to record.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording)

        initViews()
        setupRecycler()
        loadExistingRecordings()
        setClickListeners()
        setupBackHandler()
    }

    // Init
    private fun initViews() {
        btnBack            = findViewById(R.id.btnBack)
        cardModeAudio      = findViewById(R.id.cardModeAudio)
        cardModeVideo      = findViewById(R.id.cardModeVideo)
        ivAudioIcon        = findViewById(R.id.ivAudioIcon)
        ivVideoIcon        = findViewById(R.id.ivVideoIcon)
        tvAudioLabel       = findViewById(R.id.tvAudioLabel)
        tvVideoLabel       = findViewById(R.id.tvVideoLabel)
        tvTimer            = findViewById(R.id.tvTimer)
        btnRecord          = findViewById(R.id.btnRecord)
        tvRecordingBadge   = findViewById(R.id.tvRecordingBadge)
        surfaceView        = findViewById(R.id.surfaceView)
        recyclerRecordings = findViewById(R.id.recyclerRecordings)
        tvNoRecordings     = findViewById(R.id.tvNoRecordings)

        // Hide preview surface until video mode is selected
        surfaceView.visibility = View.GONE
    }

    private fun setupRecycler() {
        recordingsAdapter = RecordingsAdapter(recordings) { confirmDeleteRecording(it) }
        recyclerRecordings.layoutManager = LinearLayoutManager(this)
        recyclerRecordings.adapter = recordingsAdapter
    }

    // Load existing recordings
    private fun loadExistingRecordings() {
        recordings.clear()
        val dir = getExternalFilesDir(null) ?: filesDir
        dir.listFiles()
            ?.filter { it.name.startsWith("safewalk_") &&
                    (it.name.endsWith(".mp4") || it.name.endsWith(".m4a")) }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { file ->
                val isVideo = file.name.endsWith(".mp4")
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(file.lastModified()))
                recordings.add(RecordingFile(file.name, file.absolutePath, date, isVideo))
            }
        updateRecordingsUI()
    }

    private fun updateRecordingsUI() {
        recordingsAdapter.notifyDataSetChanged()
        tvNoRecordings.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
    }

    // Click listeners
    private fun setClickListeners() {
        btnBack.setOnClickListener {
            if (isRecording) Toast.makeText(this, "Stop recording before going back.", Toast.LENGTH_SHORT).show()
            else finish()
        }
        cardModeAudio.setOnClickListener { selectMode(videoMode = false) }
        cardModeVideo.setOnClickListener { selectMode(videoMode = true)  }
        btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else checkPermissionsAndRecord()
        }
    }

    // Mode selector
    private fun selectMode(videoMode: Boolean) {
        if (isRecording) {
            Toast.makeText(this, "Stop recording to change mode.", Toast.LENGTH_SHORT).show()
            return
        }
        isVideoMode = videoMode

        val activeCard    = getColor(R.color.primary)
        val inactiveCard  = getColor(R.color.surface)
        val activeIcon    = getColor(R.color.white)
        val inactiveIcon  = getColor(R.color.text_secondary)

        cardModeAudio.setCardBackgroundColor(if (!videoMode) activeCard   else inactiveCard)
        cardModeVideo.setCardBackgroundColor(if ( videoMode) activeCard   else inactiveCard)
        ivAudioIcon.setColorFilter          (if (!videoMode) activeIcon   else inactiveIcon)
        ivVideoIcon.setColorFilter          (if ( videoMode) activeIcon   else inactiveIcon)
        tvAudioLabel.setTextColor           (if (!videoMode) activeIcon   else inactiveIcon)
        tvVideoLabel.setTextColor           (if ( videoMode) activeIcon   else inactiveIcon)

        // Show preview surface only in video mode
        surfaceView.visibility = if (videoMode) View.VISIBLE else View.GONE
        btnRecord.setIconResource(if (videoMode) R.drawable.ic_videocam else R.drawable.ic_mic)
    }

    // Permissions
    private fun checkPermissionsAndRecord() {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (isVideoMode) required.add(Manifest.permission.CAMERA)
        val allGranted = required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) startRecording() else permissionLauncher.launch(required.toTypedArray())
    }

    // Start recording
    @Suppress("DEPRECATION")
    private fun startRecording() {
        val timestamp   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir         = getExternalFilesDir(null) ?: filesDir
        val extension   = if (isVideoMode) "mp4" else "m4a"
        currentFilePath = "${dir.absolutePath}/safewalk_$timestamp.$extension"

        if (isVideoMode) {
            // Video: must wait for SurfaceHolder to be ready before calling prepare()
            startVideoRecording()
        } else {
            // Audio only: no surface needed
            startAudioRecording()
        }
    }

    // Audio-only recording
    @Suppress("DEPRECATION")
    private fun startAudioRecording() {
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }

            mediaRecorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)   // must be before setAudioEncoder
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(currentFilePath)
                prepare()
                start()
            }

            onRecordingStarted()

        } catch (e: Exception) {
            mediaRecorder?.release()
            mediaRecorder = null
            Toast.makeText(this, "Audio recording failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Video + audio recording
    @Suppress("DEPRECATION")
    private fun startVideoRecording() {
        val holder = surfaceView.holder

        // If surface is already available, start immediately
        if (holder.surface.isValid) {
            startVideoRecordingWithSurface(holder)
            return
        }

        // Otherwise wait for surface to be created
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                holder.removeCallback(this)
                startVideoRecordingWithSurface(h)
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
    }

    @Suppress("DEPRECATION")
    private fun startVideoRecordingWithSurface(holder: SurfaceHolder) {
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }

            mediaRecorder!!.apply {
                // Sources must be set before output format
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)

                // Output format before encoders
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                // Encoders after output format
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(1280, 720)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(3_000_000)

                // Preview surface — must be set before prepare()
                setPreviewDisplay(holder.surface)

                setOutputFile(currentFilePath)
                prepare()
                start()
            }

            onRecordingStarted()

        } catch (e: Exception) {
            mediaRecorder?.release()
            mediaRecorder = null
            Toast.makeText(this, "Video recording failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Shared UI update when recording starts
    private fun onRecordingStarted() {
        isRecording  = true
        timerSeconds = 0
        startTimer()
        btnRecord.text = "Stop"
        btnRecord.setIconResource(R.drawable.ic_stop)
        tvRecordingBadge.visibility = View.VISIBLE
    }

    // Stop recording
    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            // Can throw if stop() called within ~500ms of start()
        }
        mediaRecorder = null
        isRecording   = false
        stopTimer()

        btnRecord.text = "Record"
        btnRecord.setIconResource(if (isVideoMode) R.drawable.ic_videocam else R.drawable.ic_mic)
        tvRecordingBadge.visibility = View.GONE
        surfaceView.visibility      = if (isVideoMode) View.VISIBLE else View.GONE

        Toast.makeText(this, "Recording saved.", Toast.LENGTH_SHORT).show()
        loadExistingRecordings()
    }

    // Timer
    private val timerRunnable = object : Runnable {
        override fun run() {
            timerSeconds++
            tvTimer.text = "%02d:%02d".format(timerSeconds / 60, timerSeconds % 60)
            handler.postDelayed(this, 1000)
        }
    }

    private fun startTimer() { handler.post(timerRunnable) }
    private fun stopTimer()  { handler.removeCallbacks(timerRunnable); tvTimer.text = "00:00" }


    // Delete
    private fun confirmDeleteRecording(recording: RecordingFile) {
        AlertDialog.Builder(this)
            .setTitle("Delete Recording")
            .setMessage("Delete ${recording.name}?")
            .setPositiveButton("Delete") { _, _ ->
                File(recording.path).delete()
                loadExistingRecordings()
                Toast.makeText(this, "Recording deleted.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    // Back handler
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isRecording) Toast.makeText(this@RecordingActivity, "Stop recording before going back.", Toast.LENGTH_SHORT).show()
                else finish()
            }
        })
    }

    override fun onDestroy() {
        if (isRecording) stopRecording()
        stopTimer()
        super.onDestroy()
    }
}


// RecordingsAdapter
class RecordingsAdapter(
    private val recordings: List<RecordingFile>,
    private val onDelete: (RecordingFile) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivType: ImageView      = view.findViewById(R.id.ivRecordingType)
        val tvName: TextView       = view.findViewById(R.id.tvRecordingName)
        val tvDate: TextView       = view.findViewById(R.id.tvRecordingDate)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteRecording)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val rec = recordings[position]
        holder.tvName.text = rec.name
        holder.tvDate.text = rec.date
        holder.ivType.setImageResource(if (rec.isVideo) R.drawable.ic_videocam else R.drawable.ic_mic)
        holder.btnDelete.setOnClickListener { onDelete(rec) }
    }

    override fun getItemCount() = recordings.size
}