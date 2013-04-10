package com.geeksville.andropilot.service

import java.io.File
import java.io.FilenameFilter
import android.content.Context
import com.ridemission.scandroid.AndroidLogger
import android.support.v4.app.NotificationCompat
import android.app.NotificationManager
import com.geeksville.andropilot.R
import android.content.Intent
import android.net.Uri
import android.app.PendingIntent
import com.geeksville.andropilot.AndropilotPrefs

/**
 * Scan for tlogs in the specified directory.  If found, upload them to droneshare and then either delete or
 * move to destdir
 */
class AndroidDirUpload(val context: Context, val srcDir: File, val destDir: File) extends AndroidLogger with AndropilotPrefs {

  private var srcFiles = List[File]()

  private var curUpload: Option[AndroidUpload] = None

  rescan() // Prime the pump

  def rescan() {
    srcFiles = srcDir.listFiles(new FilenameFilter { def accept(dir: File, name: String) = name.endsWith(".tlog") }).toList
    if (!isUploading)
      sendNext()
  }

  def isUploading = curUpload.isDefined
  def canUpload = !dshareUsername.isEmpty && !dsharePassword.isEmpty && dshareUpload

  private def sendNext() {
    srcFiles.headOption.foreach { n =>
      curUpload = Some(new AndroidUpload(n))
    }
  }

  /**
   * Mark current file as complete and start the next file
   */
  private def handleSuccess() {
    val src = curUpload.get.srcFile

    if (!dshareDeleteSent) {
      val newName = new File(destDir, src.getName)
      warn("Moving to " + newName)
      src.renameTo(newName)
    } else {
      warn("FIXME - not Deleting " + src)
      // src.delete()
    }

    curUpload = None
    srcFiles = srcFiles.tail

    sendNext()
  }

  /**
   * Add android specific upload behavior
   */
  class AndroidUpload(srcFile: File) extends DroneShareUpload(srcFile, dshareUsername, dsharePassword) {

    private val fileSize = srcFile.length.toInt

    private val notifyId = AndroidUpload.makeId

    private val notifyManager = context.getSystemService(Context.NOTIFICATION_SERVICE).asInstanceOf[NotificationManager]
    private val nBuilder = new NotificationCompat.Builder(context)
    nBuilder.setContentTitle("DroneShare Upload")
      .setContentText("Uploading tlog")
      .setSmallIcon(R.drawable.icon)
      .setProgress(fileSize, 0, false)

    // Generate initial notification
    updateNotification()

    debug("Started upload " + srcFile)

    private def updateNotification() { notifyManager.notify(notifyId, nBuilder.build) }

    private def removeProgress() { nBuilder.setProgress(0, 0, false) }

    override protected def handleProgress(bytesTransferred: Int) {
      debug("Upload progress " + bytesTransferred)
      nBuilder.setProgress(fileSize, bytesTransferred, false)
      updateNotification()

      super.handleProgress(bytesTransferred)
    }

    override protected def handleUploadFailed(ex: Option[Exception]) {
      error("Upload failed: " + ex)
      removeProgress()
      nBuilder.setContentText("Failed" + ex.map(": " + _.getMessage).getOrElse(""))
      updateNotification()

      super.handleUploadFailed(ex)
    }

    override protected def handleUploadCompleted() {
      debug("Upload completed")
      removeProgress()
      updateNotification()

      super.handleUploadCompleted
    }

    override protected def handleWebAppCompleted() {
      debug("Webapp upload completed")
      nBuilder.setContentText("Completed, select to view...")

      // Attach the view URL
      val intent = new Intent(Intent.ACTION_VIEW, Uri.parse(viewURL))
      val pintent = PendingIntent.getActivity(context, 0, intent, 0)
      nBuilder.setContentIntent(pintent)

      // FIXME, include action buttons for sharing

      updateNotification()

      // Send the next file
      handleSuccess()

      super.handleWebAppCompleted()
    }
  }

  object AndroidUpload {
    private var nextId = 2

    def makeId = {
      val r = nextId
      nextId += 1
      r
    }
  }
}