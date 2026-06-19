package com.s23010145.safewalk

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingFile(
    val name: String,
    val uri: Uri,
    val date: String,
    val isVideo: Boolean
)

class RecordingActivity : AppCompatActivity() {

    companion object {
        // Sub-folder name shown inside Movies/ and Music/ in the Gallery / Files app
        private const val ALBUM_NAME = "SafeWalk"
    }

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
    private lateinit var surfaceView: SurfaceView
    private lateinit var recyclerRecordings: RecyclerView
    private lateinit var tvNoRecordings: TextView

    private var isVideoMode     = false
    private var isRecording     = false
    private var mediaRecorder: MediaRecorder? = null

    // Current recording's MediaStore Uri + ParcelFileDescriptor (needed for MediaStore output)
    private var currentUri: Uri? = null
    private var currentPfd: ParcelFileDescriptor? = null

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

        surfaceView.visibility = View.GONE
    }

    private fun setupRecycler() {
        recordingsAdapter = RecordingsAdapter(recordings) { confirmDeleteRecording(it) }
        recyclerRecordings.layoutManager = LinearLayoutManager(this)
        recyclerRecordings.adapter = recordingsAdapter
    }


    // Load existing recordings from MediaStore
    private fun loadExistingRecordings() {
        recordings.clear()

        // Query video recordings (Movies/SafeWalk)
        queryMediaStore(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            relativePath = "${Environment.DIRECTORY_MOVIES}/$ALBUM_NAME",
            isVideo = true
        )

        // Query audio recordings (Music/SafeWalk)
        queryMediaStore(
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            relativePath = "${Environment.DIRECTORY_MUSIC}/$ALBUM_NAME",
            isVideo = false
        )

        recordings.sortByDescending { it.date }
        updateRecordingsUI()
    }

    private fun queryMediaStore(collection: Uri, relativePath: String, isVideo: Boolean) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%$ALBUM_NAME%")

        try {
            contentResolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
                ?.use { cursor ->
                    val idCol   = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)

                    while (cursor.moveToNext()) {
                        val id   = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol)
                        val dateSeconds = cursor.getLong(dateCol)
                        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                            .format(Date(dateSeconds * 1000))
                        val uri = Uri.withAppendedPath(collection, id.toString())
                        recordings.add(RecordingFile(name, uri, date, isVideo))
                    }
                }
        } catch (e: Exception) {
            // Non-fatal — just means this collection had no results or query failed
        }
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

        val activeCard   = getColor(R.color.primary)
        val inactiveCard = getColor(R.color.surface)
        val activeText   = getColor(R.color.white)
        val inactiveText = getColor(R.color.text_secondary)

        cardModeAudio.setCardBackgroundColor(if (!videoMode) activeCard else inactiveCard)
        cardModeVideo.setCardBackgroundColor(if ( videoMode) activeCard else inactiveCard)
        ivAudioIcon.setColorFilter          (if (!videoMode) activeText else inactiveText)
        ivVideoIcon.setColorFilter          (if ( videoMode) activeText else inactiveText)
        tvAudioLabel.setTextColor           (if (!videoMode) activeText else inactiveText)
        tvVideoLabel.setTextColor           (if ( videoMode) activeText else inactiveText)

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


    // Start recording — creates a MediaStore entry first, then points
    // MediaRecorder's output file descriptor at it.
    private fun startRecording() {
        if (isVideoMode) startVideoRecording() else startAudioRecording()
    }


    // Create a new MediaStore entry in Movies/SafeWalk or Music/SafeWalk
    // Returns the Uri to write to, or null on failure.
    private fun createMediaStoreEntry(isVideo: Boolean, timestamp: String): Uri? {
        val fileName = "safewalk_$timestamp.${if (isVideo) "mp4" else "m4a"}"
        val mimeType = if (isVideo) "video/mp4" else "audio/mp4"
        val collection = if (isVideo)
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val relativeDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDir/$ALBUM_NAME")
            // Mark as "pending" while we write to it — MediaStore hides it
            // from other apps' galleries until we clear this flag in stopRecording()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        return contentResolver.insert(collection, values)
    }

    // Audio-only recording
    @Suppress("DEPRECATION")
    private fun startAudioRecording() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val uri = createMediaStoreEntry(isVideo = false, timestamp = timestamp)

        if (uri == null) {
            Toast.makeText(this, "Could not create recording file.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val pfd = contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Could not open file descriptor")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
            mediaRecorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(pfd.fileDescriptor)
                prepare()
                start()
            }

            currentUri = uri
            currentPfd = pfd
            onRecordingStarted()

        } catch (e: Exception) {
            // Clean up the half-created MediaStore entry on failure
            contentResolver.delete(uri, null, null)
            mediaRecorder?.release()
            mediaRecorder = null
            Toast.makeText(this, "Audio recording failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }


    // Video + audio recording
    private fun startVideoRecording() {
        val holder = surfaceView.holder
        if (holder.surface.isValid) {
            startVideoRecordingWithSurface(holder)
        } else {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) {
                    holder.removeCallback(this)
                    startVideoRecordingWithSurface(h)
                }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {}
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun startVideoRecordingWithSurface(holder: SurfaceHolder) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val uri = createMediaStoreEntry(isVideo = true, timestamp = timestamp)

        if (uri == null) {
            Toast.makeText(this, "Could not create recording file.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val pfd = contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Could not open file descriptor")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
            mediaRecorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(1280, 720)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(3_000_000)
                setPreviewDisplay(holder.surface)
                setOutputFile(pfd.fileDescriptor)
                prepare()
                start()
            }

            currentUri = uri
            currentPfd = pfd
            onRecordingStarted()

        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            mediaRecorder?.release()
            mediaRecorder = null
            Toast.makeText(this, "Video recording failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }


    // Shared UI update
    private fun onRecordingStarted() {
        isRecording  = true
        timerSeconds = 0
        startTimer()
        btnRecord.text = "Stop"
        btnRecord.setIconResource(R.drawable.ic_stop)
        tvRecordingBadge.visibility = View.VISIBLE
    }


    // Stop recording — clear IS_PENDING so the file becomes visible
    // in the Gallery / Files app immediately.
    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            // Can throw if stop() called within ~500ms of start()
        }
        mediaRecorder = null

        try {
            currentPfd?.close()
        } catch (e: Exception) { /* ignore */ }
        currentPfd = null

        // Mark the file as no longer pending — this is what makes it
        // show up in Gallery apps and other file browsers.
        currentUri?.let { uri ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                try {
                    contentResolver.update(uri, values, null, null)
                } catch (e: Exception) { /* ignore */ }
            }
        }
        currentUri = null

        isRecording = false
        stopTimer()

        btnRecord.text = "Record"
        btnRecord.setIconResource(if (isVideoMode) R.drawable.ic_videocam else R.drawable.ic_mic)
        tvRecordingBadge.visibility = View.GONE
        surfaceView.visibility      = if (isVideoMode) View.VISIBLE else View.GONE

        Toast.makeText(
            this,
            "Recording saved to ${if (isVideoMode) "Movies" else "Music"}/$ALBUM_NAME",
            Toast.LENGTH_LONG
        ).show()
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


    // Delete a recording via MediaStore Uri
    private fun confirmDeleteRecording(recording: RecordingFile) {
        AlertDialog.Builder(this)
            .setTitle("Delete Recording")
            .setMessage("Delete ${recording.name}?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    contentResolver.delete(recording.uri, null, null)
                    loadExistingRecordings()
                    Toast.makeText(this, "Recording deleted.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
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