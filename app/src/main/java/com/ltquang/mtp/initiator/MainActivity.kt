package com.ltquang.mtp.initiator

import android.annotation.TargetApi
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.os.Build
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.text.method.ScrollingMovementMethod
import android.util.Log
import kotlinx.android.synthetic.main.activity_main.*
import java.io.ByteArrayInputStream

@TargetApi(Build.VERSION_CODES.N)
class MainActivity : AppCompatActivity() {
    private lateinit var manager: UsbManager
    private var usbDevice: UsbDevice? = null
    private lateinit var mtpTools: MtpTools

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
                mtpTools = MtpTools.openMTP(context, usbDevice)
                displayDeviceInformation(usbDevice, true, null, null)
                displayDeviceInformation(usbDevice, true, null, null)
                usbDevice?.let {
                    openMTPDevice(it)
                }
            }

            if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                displayDeviceInformation(usbDevice, false, null, null)
            }

            if (ACTION_USB_PERMISSION == intent.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                displayDeviceInformation(usbDevice, true, granted, null)
                usbDevice?.let {
                    openMTPDevice(it)
                }
            }
        }
    }

    private fun openMTPDevice(usbDevice: UsbDevice) {
        val mtpDevice = MtpDevice(usbDevice)

        if (manager.hasPermission(usbDevice)) {
            manager.openDevice(usbDevice)?.let {
                val openSuccess = mtpDevice.open(it)
                if (openSuccess) {
                    displayDeviceInformation(usbDevice, true, true, true)
                    getMTPDeviceInfomation(mtpDevice)
                    if (manager.hasPermission(usbDevice)) {
                        Log.d("", "")
                    } else {
                        displayDeviceInformation(usbDevice, true, false, null)
                    }
                } else {
                    displayDeviceInformation(usbDevice, true, null, null)
                }
            }
        } else {
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), 0)
            manager.requestPermission(usbDevice, permissionIntent)
        }
    }

    private fun getMTPDeviceInfomation(mtpDevice: MtpDevice) {
        //clean text
        displayStorageInfo("", false)
        mtpDevice.storageIds?.first()?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val objectHandle = mtpTools.createFile("ltquang4.txt", "This test content 4.".toByteArray(), mtpDevice, it, 0, true)
                if (objectHandle != -1) {
                    openFile(mtpDevice, objectHandle)
                }
            } else {
                TODO("VERSION.SDK_INT < N")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        manager = getSystemService(UsbManager::class.java)

        val filterAttached = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        val filterDeAttached = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        val filterPermission = IntentFilter(ACTION_USB_PERMISSION)

        registerReceiver(usbReceiver, filterAttached)
        registerReceiver(usbReceiver, filterDeAttached)
        registerReceiver(usbReceiver, filterPermission)

        btnOpenMTPConnection.isEnabled = false
        btnOpenMTPConnection.setOnClickListener {
            usbDevice?.let { openMTPDevice(it) }
        }

        tvMTPStorageInfo.movementMethod = ScrollingMovementMethod()
    }

    private fun displayDeviceInformation(
        usbDevice: UsbDevice?,
        attached: Boolean?,
        granted: Boolean?,
        mtpConnected: Boolean?
    ) {
        tvDeviceName.text = usbDevice?.productName ?: ""
        tvConnectStatus.text = if (attached != null) if (attached) "attached" else "detached" else ""
        tvPermission.text = if (granted != null) if (granted) "granted" else "denied" else ""
        tvMTPConnectionStatus.text = if (mtpConnected != null) if (mtpConnected) "connected" else "disconnected" else ""
        btnOpenMTPConnection.isEnabled = (attached ?: false) && (granted ?: false)
    }

    private fun displayStorageInfo(info: String, append: Boolean) {
        if (append) {
            tvMTPStorageInfo.append(info)
        } else {
            tvMTPStorageInfo.text = info
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"
    }

    private fun scanObjectsInStorage(
        mtpDevice: MtpDevice,
        storageId: Int,
        format: Int,
        parent: Int,
        fileName: String = "ltquang.txt"
    ) {
        val objectHandles = mtpDevice.getObjectHandles(storageId, format, parent) ?: return

        for (objectHandle in objectHandles) {
            /*
                 *　It's an abnormal case that you can't acquire MtpObjectInfo from MTP device
                 */
            val mtpObjectInfo = mtpDevice.getObjectInfo(objectHandle) ?: continue
            if (mtpObjectInfo.name.startsWith(fileName, true)) {
                val associationType =
                    "${mtpObjectInfo.name} + ${mtpObjectInfo.associationType} + ${mtpObjectInfo.objectHandle}"

                displayStorageInfo("\n$associationType", true)
                openFile(mtpDevice, objectHandle)
                break
            }
        }
    }

    private fun openFile(mtpDevice: MtpDevice, objectHandle: Int) {
        try {
            val mtpObjectInfo = mtpDevice.getObjectInfo(objectHandle)
            val fileInBytes = mtpDevice.getObject(objectHandle, mtpObjectInfo.compressedSize)
            val byteArrayIS = ByteArrayInputStream(fileInBytes)
            val inputStream = byteArrayIS.reader()
            val text = inputStream.readText()
            displayStorageInfo("\n$text", true)
            inputStream.close()
            byteArrayIS.close()
        } catch (e: Exception) {
            Log.d("abc", e.toString())
        } finally {
//            writeFile(mtpDevice)
        }
    }

//    private fun writeToFile(mtpDevice: MtpDevice, storageId: Int) {
//        try {
//            val fileName = "ltquang3.txt"
//            val pipe = ParcelFileDescriptor.createReliablePipe()
//            val textContent = "This is second text content for testing.".toByteArray()
//            val fileOutputStream = FileOutputStream(pipe[1].fileDescriptor)
//
//            fileOutputStream.write(textContent)
//
//            fileOutputStream.flush()
//            fileOutputStream.close()
//
//
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//                val objectInfo = MtpObjectInfo.Builder()
//                    .setStorageId(storageId)
//                    .setName(fileName)
//                    .setFormat(MtpConstants.FORMAT_TEXT)
//                    .setCompressedSize(textContent.size.toLong())
//                    .setParent(0)
//                    .build()
//
//                val newObjectInfo = mtpDevice.sendObjectInfo(objectInfo)
//                if (newObjectInfo != null) {
//
//                    val createFileSuccess =
//                        mtpDevice.sendObject(newObjectInfo.objectHandle, newObjectInfo.compressedSizeLong, pipe[0])
//
//                    pipe[1].close()
//                    pipe[0].close()
//
//                    if (createFileSuccess) {
//                        scanObjectsInStorage(mtpDevice, storageId, 0, 0, fileName)
//                    } else {
//                        displayStorageInfo("\nfailured created file", true)
//                    }
//                } else {
//                    displayStorageInfo("\nfailured created file", true)
//                }
//            }
//
//        } catch (e: Exception) {
//            Log.d("abc", e.toString())
//        }
//    }
}
