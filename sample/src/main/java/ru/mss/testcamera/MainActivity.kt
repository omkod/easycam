package ru.mss.testcamera

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import com.omkod.easycam.CameraView

class MainActivity : AppCompatActivity() {

    private lateinit var cameraView: CameraView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onStart() {
        super.onStart()

        cameraView = findViewById(R.id.cameraView)
        cameraView.photoCallback =
            { bitmap ->
                Log.i("CameraSample", "You have image ${bitmap.width}x${bitmap.height}")
            }
        cameraView.startCamera()
    }

    override fun onStop() {
        super.onStop()

        cameraView.stopCamera()
    }
}