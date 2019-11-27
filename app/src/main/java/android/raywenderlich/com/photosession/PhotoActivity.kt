/*
 * Copyright (c) 2019 Razeware LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * Notwithstanding the foregoing, you may not use, copy, modify, merge, publish,
 * distribute, sublicense, create a derivative work, and/or sell copies of the
 * Software in any work that is designed, intended, or marketed for pedagogical or
 * instructional purposes related to programming, coding, application development,
 * or information technology.  Permission for such use, copying, modification,
 * merger, publication, distribution, sublicensing, creation of derivative works,
 * or sale is expressly withheld.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package android.raywenderlich.com.photosession

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.media.Image
import android.os.Bundle
import android.raywenderlich.com.photosession.ImagePopupView.Companion.ALPHA_TRANSPARENT
import android.raywenderlich.com.photosession.ImagePopupView.Companion.FADING_ANIMATION_DURATION
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.extensions.BokehImageCaptureExtender
import androidx.camera.extensions.HdrImageCaptureExtender
import androidx.camera.extensions.ImageCaptureExtender
import androidx.camera.extensions.NightImageCaptureExtender
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.android.synthetic.main.activity_photo.*
import java.io.File
import java.util.concurrent.Executors

class PhotoActivity : AppCompatActivity() {

  companion object {
    private const val REQUEST_CODE_PERMISSIONS = 10

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
  }

  private val fileUtils: FileUtils by lazy { FileUtilsImpl() }

  private var imageCapture: ImageCapture? = null
  private var imagePreview: Preview? = null
  private var imagePopupView: ImagePopupView? = null
  private var lensFacing = CameraX.LensFacing.BACK

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_photo)
    setClickListeners()
    requestPermissions()
  }

  private fun startCamera() {
    CameraX.unbindAll()

    imagePreview = createPreviewUseCase()

    imagePreview!!.setOnPreviewOutputUpdateListener {

      val parent = previewView.parent as ViewGroup
      parent.removeView(previewView)
      parent.addView(previewView, 0)

      previewView.surfaceTexture = it.surfaceTexture
      updateTransform()
    }

    imageCapture = createCaptureUseCase()
    CameraX.bindToLifecycle(this, imagePreview, imageCapture)
  }

  private fun takePicture() {
    disableActions()
    if (saveImageSwitch.isChecked) {
      savePictureToFile()
    } else {
      savePictureToMemory()
    }
  }

  private fun savePictureToFile() {
    fileUtils.createDirectoryIfNotExist()
    val file = fileUtils.createFile()

    imageCapture?.takePicture(file, getMetadata(), Executors.newSingleThreadExecutor()
        , object : ImageCapture.OnImageSavedListener {
      override fun onImageSaved(file: File) {
        runOnUiThread {
          takenImage.setImageURI(
              FileProvider.getUriForFile(this@PhotoActivity,
                  packageName,
                  file))
          enableActions()
        }
      }

      override fun onError(imageCaptureError: ImageCapture.ImageCaptureError,
                           message: String,
                           cause: Throwable?) {
        Toast.makeText(this@PhotoActivity, getString(R.string.image_capture_failed), Toast.LENGTH_SHORT).show()
      }
    })
  }

  private fun savePictureToMemory() {
    imageCapture?.takePicture(
        Executors.newSingleThreadExecutor()
        , object : ImageCapture.OnImageCapturedListener() {
      override fun onError(
          error: ImageCapture.ImageCaptureError,
          message: String, exc: Throwable?
      ) {
        Toast.makeText(this@PhotoActivity, getString(R.string.image_save_failed), Toast.LENGTH_SHORT).show()
      }

      override fun onCaptureSuccess(imageProxy: ImageProxy?, rotationDegrees: Int) {
        if (imageProxy != null && imageProxy.image != null) {
          val bitmap = rotateImage(
              imageToBitmap(imageProxy.image!!),
              rotationDegrees.toFloat()
          )
          runOnUiThread {
            takenImage.setImageBitmap(bitmap)
            enableActions()
          }
        }
        super.onCaptureSuccess(imageProxy, rotationDegrees)
      }
    })
  }

  private fun getMetadata() = ImageCapture.Metadata().apply {
    isReversedHorizontal = lensFacing == CameraX.LensFacing.FRONT
  }

  private fun createPreviewUseCase(): Preview {
    val previewConfig = PreviewConfig.Builder().apply {
      setLensFacing(lensFacing)
      setTargetRotation(previewView.display.rotation)

    }.build()

    return Preview(previewConfig)
  }

  private fun updateTransform() {
    val matrix = Matrix()

    val centerX = previewView.width / 2f
    val centerY = previewView.height / 2f

    val rotationDegrees = when (previewView.display.rotation) {
      Surface.ROTATION_0 -> 0
      Surface.ROTATION_90 -> 90
      Surface.ROTATION_180 -> 180
      Surface.ROTATION_270 -> 270
      else -> return
    }
    matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

    previewView.setTransform(matrix)
  }

  private fun toggleFrontBackCamera() {
    lensFacing = if (lensFacing == CameraX.LensFacing.BACK) {
      CameraX.LensFacing.FRONT
    } else {
      CameraX.LensFacing.BACK
    }
    previewView.post { startCamera() }
  }

  private fun createCaptureUseCase(): ImageCapture {
    val imageCaptureConfig = ImageCaptureConfig.Builder()
        .apply {
          setLensFacing(lensFacing)
          setTargetRotation(previewView.display.rotation)
          setCaptureMode(ImageCapture.CaptureMode.MAX_QUALITY)
        }

    applyExtensions(imageCaptureConfig)
    return ImageCapture(imageCaptureConfig.build())
  }

  private fun applyExtensions(builder: ImageCaptureConfig.Builder) {
    when (ExtensionFeature.fromPosition(extensionFeatures.selectedItemPosition)) {
      ExtensionFeature.BOKEH -> enableExtensionFeature(BokehImageCaptureExtender.create(builder))
      ExtensionFeature.HDR -> enableExtensionFeature(HdrImageCaptureExtender.create(builder))
      ExtensionFeature.NIGHT_MODE -> enableExtensionFeature(NightImageCaptureExtender.create(builder))
      else -> {
      }
    }
  }

  private fun enableExtensionFeature(imageCaptureExtender: ImageCaptureExtender) {
    if (imageCaptureExtender.isExtensionAvailable) {
      imageCaptureExtender.enableExtension()
    } else {
      Toast.makeText(this, getString(R.string.extension_unavailable), Toast.LENGTH_SHORT).show()
      extensionFeatures.setSelection(0)
    }
  }

  private fun setClickListeners() {
    toggleCameraLens.setOnClickListener { toggleFrontBackCamera() }
    previewView.setOnClickListener { takePicture() }
    takenImage.setOnLongClickListener {
      showImagePopup()
      return@setOnLongClickListener true
    }

    extensionFeatures.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(
          parentView: AdapterView<*>,
          selectedItemView: View,
          position: Int,
          id: Long
      ) {
        if (ExtensionFeature.fromPosition(position) != ExtensionFeature.NONE) {
          previewView.post { startCamera() }
        }
      }

      override fun onNothingSelected(parentView: AdapterView<*>) {}
    }
  }

  private fun requestPermissions() {
    if (allPermissionsGranted()) {
      previewView.post { startCamera() }
    } else {
      ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }
  }

  private fun createImagePopup(
      imageDrawable: Drawable,
      backgroundClickAction: () -> Unit
  ) =
      ImagePopupView.builder(this)
          .imageDrawable(imageDrawable)
          .onBackgroundClickAction(backgroundClickAction)
          .build()

  private fun removeImagePopup() {
    imagePopupView?.let {
      it.animate()
          .alpha(ALPHA_TRANSPARENT)
          .setDuration(FADING_ANIMATION_DURATION)
          .withEndAction {
            rootView.removeView(it)
          }
          .start()
    }
  }

  private fun showImagePopup() {
    if (takenImage.drawable == null) {
      return
    }
    createImagePopup(takenImage.drawable) { removeImagePopup() }
        .let {
          imagePopupView = it
          addImagePopupViewToRoot(it)
        }
  }

  private fun addImagePopupViewToRoot(imagePopupView: ImagePopupView) {
    rootView.addView(
        imagePopupView,
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
  }

  private fun rotateImage(source: Bitmap, angle: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(angle)
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
  }

  private fun imageToBitmap(image: Image): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.capacity())
    buffer.get(bytes)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null)
  }

  private fun disableActions() {
    previewView.isClickable = false
    takenImage.isClickable = false
    toggleCameraLens.isClickable = false
    saveImageSwitch.isClickable = false
  }

  private fun enableActions() {
    previewView.isClickable = true
    takenImage.isClickable = true
    toggleCameraLens.isClickable = true
    saveImageSwitch.isClickable = true
  }

  override fun onRequestPermissionsResult(
      requestCode: Int, permissions: Array<String>, grantResults: IntArray
  ) {
    if (requestCode == REQUEST_CODE_PERMISSIONS) {
      if (allPermissionsGranted()) {
        previewView.post { startCamera() }
      } else {
        finish()
      }
    }
  }

  private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
  }
}
