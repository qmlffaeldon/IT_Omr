package com.example.it_scann

import android.content.pm.PackageManager
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.JavaCameraView
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat


class camera_scan : AppCompatActivity(), CameraBridgeViewBase.CvCameraViewListener2 {

    private lateinit var cameraView: JavaCameraView
    private val CAMERA_PERMISSION_REQUEST = 101

    private val loaderCallback = object : BaseLoaderCallback(this) {
        override fun onManagerConnected(status: Int) {
            if (status == LoaderCallbackInterface.SUCCESS) {
                Log.d("camera_scan", "OpenCV initialized")
                cameraView.enableView()
            } else {
                super.onManagerConnected(status)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_scan)

        cameraView = findViewById(R.id.camera_view)
        cameraView.visibility = SurfaceView.VISIBLE
        cameraView.setCvCameraViewListener(this)
        cameraView.setCameraIndex(CameraBridgeViewBase.CAMERA_ID_BACK)

        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(android.Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == CAMERA_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {

            // 🔥 REQUIRED
            cameraView.setCameraPermissionGranted()

            onResume()
        }
    }

    override fun onResume() {
        super.onResume()

        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {

            // 🔥 REQUIRED for Samsung / Android 11
            cameraView.setCameraPermissionGranted()

            if (!OpenCVLoader.initDebug()) {
                Log.d("camera_scan", "Using OpenCV Manager")
                OpenCVLoader.initAsync(
                    OpenCVLoader.OPENCV_VERSION,
                    this,
                    loaderCallback
                )
            } else {
                Log.d("camera_scan", "OpenCV loaded")
                loaderCallback.onManagerConnected(
                    LoaderCallbackInterface.SUCCESS
                )
            }
        }
    }


    override fun onPause() {
        super.onPause()
        if (::cameraView.isInitialized) {
            cameraView.disableView()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraView.isInitialized) {
            cameraView.disableView()
        }
    }

    override fun onCameraViewStarted(width: Int, height: Int) {
        Log.d("camera_scan", "Camera view started: $width x $height")
    }

    override fun onCameraViewStopped() {
        Log.d("camera_scan", "Camera view stopped")
    }

    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame): Mat {
        return inputFrame.rgba()

    }
}
