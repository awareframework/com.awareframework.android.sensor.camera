package com.awareframework.android.sensor.camera.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.preference.PreferenceManager
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.StaggeredGridLayoutManager
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import com.awareframework.android.core.db.Engine
import com.awareframework.android.sensor.camera.Camera
import com.awareframework.android.sensor.camera.CameraFace
import com.awareframework.android.sensor.camera.R.string.*
import com.awareframework.android.sensor.camera.example.adapters.VideoListAdapter
import com.awareframework.android.sensor.camera.model.VideoData
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread


class MainActivity : AppCompatActivity() {

    private val dataset: ArrayList<VideoData> = ArrayList()
    private lateinit var viewAdapter: VideoListAdapter
    private lateinit var viewManager: RecyclerView.LayoutManager

    private val semaphore: Semaphore = Semaphore(1)

    companion object {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_PHONE_STATE)
        const val REQUEST_PERMISSION = 1

        // const val SHARED_CAMERA_CONFIG = "camera_config"
        const val SHARED_CAMERA_CONFIG_KEY = "config"
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_toolbar, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.delete_selected -> {
                viewAdapter.selectionList.forEach {
                    File(it.filePath).delete()

                    if (it.parentFilePath != null) {
                        File(it.parentFilePath).delete()
                    }
                }

                viewAdapter.clearSelections()

                refreshVideos()
            }
            R.id.settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            R.id.record_now -> {
                sendBroadcast(Intent(EventReceiver.ACTION_RECORD_NOW))
            }
            R.id.help -> {
                Toast.makeText(this, getString(R.string.usage_hint), Toast.LENGTH_LONG).show()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Camera.ACTION_VIDEO_RECORDED -> Handler().postDelayed({
                    refreshVideos()
                }, 1000)
            }
        }
    }

    private val eventReceiver: EventReceiver = EventReceiver()

    @SuppressLint("ApplySharedPref")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this,
                    permissions,
                    REQUEST_PERMISSION)
        }

        viewManager = StaggeredGridLayoutManager(3, StaggeredGridLayoutManager.VERTICAL)
        viewAdapter = VideoListAdapter(dataset)

        video_list_view.apply {
            setHasFixedSize(true)
            layoutManager = viewManager
            adapter = viewAdapter
        }

        refreshVideos()

        registerReceiver(receiver, IntentFilter().apply {
            addAction(Camera.ACTION_VIDEO_RECORDED)
        })

        swipe_to_refresh.setOnRefreshListener {
            refreshVideos()
        }


        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            registerReceiver(eventReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(EventReceiver.ACTION_RECORD_NOW)
            })
        }
    }

    override fun onResume() {
        super.onResume()

        updateConfig()

        val config = getStoredConfig()

        val camera = Camera.Builder(this).build()

        if (config.enabled)
            camera.start()
        else
            camera.stop()
    }

    private fun updateConfig() {
        val sPref = PreferenceManager
                .getDefaultSharedPreferences(this)

        val cameraConfig = getStoredConfig()

        val storedEnabled = sPref.getBoolean(getString(key_camera_sensor_enabled), true)
        val storedPrimaryCamera = sPref.getString(getString(key_primary_camera), cameraConfig.facing.toInt().toString())
        val storedSecondaryCamera = sPref.getString(getString(key_secondary_camera), cameraConfig.secondaryFacing.toInt().toString())
        val storedVideoBitrate = sPref.getString(getString(key_video_bitrate), cameraConfig.bitrate.toString())
        val storedVideoFrameRate = sPref.getString(getString(key_video_frame_rate), cameraConfig.frameRate.toString())
        val storedVideoLength = sPref.getString(getString(key_video_length), cameraConfig.videoLength.toString())
        val storedDataLabel = sPref.getString(getString(key_data_label), cameraConfig.label)

        cameraConfig.apply {
            enabled = storedEnabled
            facing = CameraFace.fromInt(storedPrimaryCamera.toInt())
            secondaryFacing = CameraFace.fromInt(storedSecondaryCamera.toInt())
            bitrate = storedVideoBitrate.toInt()
            frameRate = storedVideoFrameRate.toInt()
            videoLength = storedVideoLength.toFloat()
            label = storedDataLabel
        }

        saveStoredConfig(cameraConfig)
    }

    private fun getStoredConfig(): Camera.CameraConfig =
            PreferenceManager
                    .getDefaultSharedPreferences(this)
                    .getString(SHARED_CAMERA_CONFIG_KEY, null)?.let {
                        return@let Camera.CameraConfig.fromJson(it)
                    }
                    ?: Camera.CameraConfig().apply {
                        dbType = Engine.DatabaseType.ROOM
                        contentPath = getExternalFilesDir(Environment.DIRECTORY_MOVIES).absolutePath
                    }

    private fun saveStoredConfig(config: Camera.CameraConfig) {
        PreferenceManager
                .getDefaultSharedPreferences(this)
                .edit()
                .putString(SHARED_CAMERA_CONFIG_KEY, config.toJson())
                .apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)

        try {
            unregisterReceiver(eventReceiver)
        } catch (e: RuntimeException) {
            // ignore
        }
    }

    private fun hasPermissions(): Boolean {
        return permissions.none {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_PERMISSION) {
            if (hasPermissions())
                Toast.makeText(this, "Permissions are granted.", Toast.LENGTH_SHORT).show()
            else
                Toast.makeText(this, "Unable to get permissions. Please grant them.", Toast.LENGTH_SHORT).show()

        }
    }

    private fun getAllMedia(block: (List<VideoData>) -> Unit) {
        val camera = Camera.Builder(this)
                .build(getStoredConfig())

        thread {
            block(camera.getVideoData())
        }
    }

    private fun refreshVideos() {
        getAllMedia({
            try {
                semaphore.acquire()
                dataset.clear()

                // filter deleted videos
                dataset.addAll(it.filter {
                    File(it.filePath).exists()
                })

                dataset.removeAll { a ->
                    dataset.any { b ->
                        a.filePath == b.parentFilePath
                    }
                }

                runOnUiThread {
                    viewAdapter.notifyDataSetChanged()

                    swipe_to_refresh.isRefreshing = false

                    semaphore.release()
                }
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        })
    }
}
