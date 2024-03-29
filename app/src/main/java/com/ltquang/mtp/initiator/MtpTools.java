package com.ltquang.mtp.initiator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.mtp.MtpConstants;
import android.mtp.MtpDevice;
import android.mtp.MtpObjectInfo;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.support.annotation.RequiresApi;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;

@RequiresApi(api = Build.VERSION_CODES.N)
class MtpPermissionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (MtpTools.ACTION_USB_PERMISSION == intent.getAction()) {
            Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            Log.d("", "Granted: " + granted);
        }
    }
}

@RequiresApi(api = Build.VERSION_CODES.N)
final public class MtpTools {
    private static final String TAG = "MTP Connect";
    static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private MtpDevice mtpDevice;

    private MtpTools(MtpDevice mtpDevice) {
        this.mtpDevice = mtpDevice;
    }

    public static synchronized MtpTools openMTP(Context context, final UsbDevice device) {
        if (context == null || device == null) {
            return null;
        }

        MtpPermissionReceiver receiver = new MtpPermissionReceiver();
        context.registerReceiver(receiver, new IntentFilter(ACTION_USB_PERMISSION));

        final UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        if (usbManager == null) {
            Log.d(TAG, "usbmanager is null in openMTP");
            return null;
        }

        final MtpDevice mtpDevice = new MtpDevice(device);

        final UsbDeviceConnection usbDeviceConnection = usbManager.openDevice(device);
        try {
            if (!mtpDevice.open(usbDeviceConnection)) {
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception opening USB: " + e);
            return null;
        }

        return new MtpTools(mtpDevice);
    }

    public synchronized void close() {
        mtpDevice.close();
    }

    private synchronized int createDocument(final MtpObjectInfo objectInfo,
                                            final ParcelFileDescriptor source) {

        final MtpObjectInfo sendObjectInfoResult = mtpDevice.sendObjectInfo(objectInfo);
        if (sendObjectInfoResult == null) {
            Log.e(TAG, "Null sendObjectInfoResult in create document :(");
            return -1;
        }
        Log.d(TAG, "Send object info result: " + sendObjectInfoResult.getName());

        // Association is what passes for a folder within mtp
        if (objectInfo.getFormat() != MtpConstants.FORMAT_ASSOCIATION) {
            if (!mtpDevice.sendObject(sendObjectInfoResult.getObjectHandle(),
                    sendObjectInfoResult.getCompressedSize(), source)) {
                return -1;
            }
        }
        Log.d(TAG, "Success indicated with handle: " + sendObjectInfoResult.getObjectHandle());
        return sendObjectInfoResult.getObjectHandle();
    }

    public boolean deleteIfExistsInRoot(final int storageId, final String filename) {
        final int handle = existsInRoot(storageId, filename);
        if (handle != -1) {
            Log.d(TAG, "Deleting: " + filename + " at " + handle);
            return mtpDevice.deleteObject(handle);
        }
        return false;
    }

    public int existsInTopLevelFolder(final int storageId, final String folder, final String filename) {
        final HashMap<String, Integer> folders = getTopLevelFolders(storageId);
        if (folders == null || folders.get(folder) == null) {
            return -1;
        }
        return existsInFolderHandle(storageId, filename, folders.get(folder));
    }

    // -1 = not found
    public int existsInRoot(final int storageId, final String filename) {
        return existsInFolderHandle(storageId, filename, -1);
    }

    // -1 = not found
    public int existsInFolderHandle(final int storageId, final String filename, final int handle) {
        final int[] objectHandles = mtpDevice.getObjectHandles(storageId, 0, handle);
        if (objectHandles == null) {
            return -1;
        }

        if (objectHandles.length > 20 || objectHandles.length < 1) {
            Log.d(TAG, "existsInRoot() Got object handles count: " + objectHandles.length);
        }
        for (int objectHandle : objectHandles) {

            final MtpObjectInfo mtpObjectInfo = mtpDevice.getObjectInfo(objectHandle);
            if (mtpObjectInfo == null) {
                continue;
            }

            if (mtpObjectInfo.getParent() != 0) {
                continue;
            }

            if (mtpObjectInfo.getName().equalsIgnoreCase(filename)) {
                return mtpObjectInfo.getObjectHandle();
            }
        }
        return -1;
    }

    public HashMap<String, Integer> getTopLevelFolders(final int storageId) {
        final int[] objectHandles = mtpDevice.getObjectHandles(storageId, MtpConstants.FORMAT_ASSOCIATION, -1);
        if (objectHandles == null) {
            return null;
        }

        Log.d(TAG, "FoldersInRoot() Got object handles count: " + objectHandles.length);

        final HashMap<String, Integer> results = new HashMap<>();

        for (int objectHandle : objectHandles) {

            final MtpObjectInfo mtpObjectInfo = mtpDevice.getObjectInfo(objectHandle);
            if (mtpObjectInfo == null) {
                continue;
            }
            if (mtpObjectInfo.getParent() != 0) {
                continue;
            }

            if (mtpObjectInfo.getFormat() == MtpConstants.FORMAT_ASSOCIATION) {
                results.put(mtpObjectInfo.getName(), mtpObjectInfo.getObjectHandle());
            }
        }
        return results;
    }

//    public int recreateFile(final String fileName, final byte[] outputBytes, final MtpDevice mtpDevice, final int storage_id, final int parent_id) {
//        deleteIfExistsInRoot(mtpDevice, storage_id, fileName);
//        return createFile(fileName, outputBytes, mtpDevice, storage_id, parent_id);
//    }

    public int createFile(final String fileName, final byte[] outputBytes, final MtpDevice mtpDevice, final int storage_id, final int parent_id, boolean shouldOverride) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "createFile cannot work below Android 7");
            return -1;
        }

        if (shouldOverride) {
            deleteIfExistsInRoot(storage_id, fileName);
        }

        if (outputBytes != null) {
            ParcelFileDescriptor[] pipe = null;
            try {
                pipe = ParcelFileDescriptor.createReliablePipe();

                final FileOutputStream out = new FileOutputStream(pipe[1].getFileDescriptor());
                try {
                    Log.d(TAG, "Attempting to write: " + outputBytes.length + " bytes to: " + fileName);
                    out.write(outputBytes);
                    out.flush();

                } catch (IOException e) {
                    Log.e(TAG, "Got io exception in writing thread");
                } finally {
                    try {
                        out.close();
                    } catch (IOException e) {
                        Log.e(TAG, "got io exception closing in writing thread");
                    }
                }
            } catch (NullPointerException | IOException e) {
                Log.e(TAG, "IO exception or null in pipe creation: " + e);
            }

            if (pipe != null) {
                final MtpObjectInfo fileInfo = new MtpObjectInfo.Builder()
                        .setName(fileName)
                        .setFormat(MtpConstants.FORMAT_UNDEFINED)
                        .setStorageId(storage_id)
                        .setParent(parent_id)
                        .setCompressedSize(outputBytes.length).build();
                try {
                    return createDocument(fileInfo, pipe[0]);
                } finally {
                    try {
                        pipe[1].close();
                    } catch (NullPointerException | IOException e) {
                        Log.d(TAG, "Exception closing pipe 1: " + e);
                    }
                    try {
                        pipe[0].close();
                    } catch (NullPointerException | IOException e) {
                        Log.d(TAG, "Exception closing pipe 0: " + e);
                    }
                }
            }
        } else {
            Log.e(TAG, "Output bytes null");
        }
        return -1;
    }

    public int createFolder(final String fileName, final MtpDevice mtpDevice, final int storage_id) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "createFolder cannot work below Android 7");
            return -1;
        }

        ParcelFileDescriptor[] pipe = null;
        try {
            pipe = ParcelFileDescriptor.createReliablePipe();
        } catch (NullPointerException | IOException e) {
            Log.e(TAG, "IO exception or null in pipe creation: " + e);
        }

        if (pipe != null) {
            final MtpObjectInfo fileInfo = new MtpObjectInfo.Builder()
                    .setName(fileName)
                    .setFormat(MtpConstants.FORMAT_ASSOCIATION)
                    .setStorageId(storage_id)
                    .setCompressedSize(0).build();
            try {
                return createDocument(fileInfo, pipe[0]);
            } finally {
                try {
                    pipe[1].close();
                } catch (NullPointerException | IOException e) {
                    Log.e(TAG, "Exception closing pipe 1: " + e);
                }
                try {
                    pipe[0].close();
                } catch (NullPointerException | IOException e) {
                    Log.e(TAG, "Exception closing pipe 0: " + e);
                }
            }
        }

        return -1;
    }

//    public class MtpDeviceHelper {
//
//        final MtpDevice device;
//
//        int[] storageVolumeIds;
//
//        public MtpDeviceHelper(final UsbDevice usbDevice) {
//            this.device = openMTP(usbDevice);
//            if (this.device != null) {
//                try {
//                    this.storageVolumeIds = this.device.getStorageIds();
//                } catch (Exception e) {
//                    Log.e(TAG, "Got exception in MtpDeviceHelper constructor: " + e);
//                }
//            } else {
//                Log.e(TAG, "Mtp device null in MtpDeviceHelper constructor");
//            }
//        }
//
//        public int getFirstStorageId() {
//            try {
//                return storageVolumeIds[0];
//            } catch (Exception e) {
//                return -1;
//            }
//        }
//
//        public int numberOfStorageIds() {
//            try {
//                return storageVolumeIds.length;
//            } catch (Exception e) {
//                return -1;
//            }
//        }
//
//        public boolean ok() {
//            return device != null;
//        }
//
//        public String name() {
//            try {
//                return device.getDeviceInfo().getModel();
//            } catch (Exception e) {
//                return "<unknown>";
//            }
//        }
//
//        public String manufacturer() {
//            try {
//                return device.getDeviceInfo().getManufacturer();
//            } catch (Exception e) {
//                return "<unknown>";
//            }
//        }
//
//        public String hash() {
////            return CipherUtils.getSHA256(manufacturer());
//            return "";
//        }
//
////        public int recreateRootFile(final String filename, final byte[] data) {
////            return recreateFile(filename, data, device, getFirstStorageId(), 0);
////        }
//
//        public boolean existsInRoot(final String filename) {
//            return existsInRoot(device, getFirstStorageId(), filename) != -1;
//        }
//
//        public boolean existsInFolder(final String folder, final String filename) {
//            return existsInTopLevelFolder(device, getFirstStorageId(), folder, filename) != -1;
//        }
//
//        public void close() {
//            device.close();
//        }
//    }

}
