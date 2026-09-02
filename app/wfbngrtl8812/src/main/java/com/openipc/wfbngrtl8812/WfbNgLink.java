package com.openipc.wfbngrtl8812;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import androidx.annotation.Keep;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

@Keep
public class WfbNgLink implements WfbNGStatsChanged {

    // Set FEC thresholds for switching levels (Java wrapper and JNI)
    public static native void nativeSetFecThresholds(long nativeInstance, int lostTo5, int recTo4, int recTo3, int recTo2, int recTo1);

    public void setFecThresholds(int lostTo5, int recTo4, int recTo3, int recTo2, int recTo1) {
        nativeSetFecThresholds(nativeWfbngLink, lostTo5, recTo4, recTo3, recTo2, recTo1);
    }
    public static String TAG = "pixelpilot";

    // Load the native library on application startup.
    static {
        System.loadLibrary("WfbngRtl8812");
    }

    private final long nativeWfbngLink;
    private final Timer timer;
    private final Context context;
    Map<UsbDevice, Thread> linkThreads = new HashMap<>();
    Map<UsbDevice, UsbDeviceConnection> linkConns = new HashMap<>();
    private WfbNGStatsChanged statsChanged;

    // Native method declarations.
    public static native long nativeInitialize(Context context);
    public static native void nativeRun(long nativeInstance, Context context, int wifiChannel, int bandWidth, int fd);
    public static native void nativeStop(long nativeInstance, Context context, int fd);
    public static native void nativeRefreshKey(long nativeInstance);
    public static native <T extends WfbNGStatsChanged> void nativeCallBack(T t, long nativeInstance);
    public static native void nativeStartAdaptivelink(long nativeInstance);
    public static native void nativeSetAdaptiveLinkEnabled(long nativeInstance, boolean enabled);
    public static native void nativeSetTxPower(long nativeInstance, int power);
    public static native void nativeSetUseFec(long nativeInstance, int use);
    public static native void nativeSetUseLdpc(long nativeInstance, int use);
    public static native void nativeSetUseStbc(long nativeInstance, int use);

    public WfbNgLink(final Context parent) {
        this.context = parent;
        nativeWfbngLink = nativeInitialize(context);
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                nativeCallBack(WfbNgLink.this, nativeWfbngLink);
            }
        }, 0, 300);
    }

    public boolean isRunning() {
        return !linkThreads.isEmpty();
    }

    public void refreshKey() {
        nativeRefreshKey(nativeWfbngLink);
    }

    // Instance wrapper for nativeSetAdaptiveLinkEnabled.
    public void nativeSetAdaptiveLinkEnabled(boolean state) {
        nativeSetAdaptiveLinkEnabled(nativeWfbngLink, state);
    }

    // Instance wrapper for nativeSetTxPower.
    public void nativeSetTxPower(int power) {
        nativeSetTxPower(nativeWfbngLink, power);
    }

    public void nativeSetUseFec(int use) {
        nativeSetUseFec(nativeWfbngLink, use);
    }

    public void nativeSetUseLdpc(int use) {
        nativeSetUseLdpc(nativeWfbngLink, use);
    }

    public void nativeSetUseStbc(int use) {
        nativeSetUseStbc(nativeWfbngLink, use);
    }

    public synchronized boolean start(int wifiChannel, int bandWidth, UsbDevice usbDevice) {
        // linkThreads.put() below overwrites the entry for a device, which would orphan an
        // older thread so that stopAll() never joins it and the interface is never released.
        Thread existing = linkThreads.get(usbDevice);
        if (existing != null && existing.isAlive()) {
            Log.w(TAG, "wfb-ng already running on " + usbDevice.getDeviceName()
                    + ", not starting a second");
            return true;
        }
        if (existing != null) {
            // A thread that outlived its join has since finished, so the connection stop()
            // deliberately left open can go now. Otherwise the put() below would drop the
            // last reference to it and leak the fd.
            UsbDeviceConnection stale = linkConns.remove(usbDevice);
            if (stale != null) {
                Log.d(TAG, "releasing the usb connection left behind on " + usbDevice.getDeviceName());
                stale.close();
            }
            linkThreads.remove(usbDevice);
        }
        Log.d(TAG, "wfb-ng monitoring on " + usbDevice.getDeviceName() + " using wifi channel " + wifiChannel);
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        // Returns null when the permission was revoked or the device disappeared between
        // the permission check and here, which is easy to hit on a re-enumerating hub.
        UsbDeviceConnection usbDeviceConnection = usbManager.openDevice(usbDevice);
        if (usbDeviceConnection == null) {
            Log.e(TAG, "Could not open " + usbDevice.getDeviceName() + " (no permission or already gone)");
            return false;
        }
        int fd = usbDeviceConnection.getFileDescriptor();
        if (fd < 0) {
            Log.e(TAG, "Invalid file descriptor for " + usbDevice.getDeviceName());
            usbDeviceConnection.close();
            return false;
        }
        Thread t = new Thread(() -> nativeRun(nativeWfbngLink, context, wifiChannel, bandWidth, fd));
        t.setName(threadNameFor(usbDevice));
        linkThreads.put(usbDevice, t);
        linkConns.put(usbDevice, usbDeviceConnection);
        t.start();
        Log.d(TAG, "wfb-ng thread on " + usbDevice.getDeviceName() + " started.");
        return true;
    }

    private static String threadNameFor(UsbDevice usbDevice) {
        String name = usbDevice.getDeviceName();
        String[] parts = name.split("/dev/bus/usb/");
        return "wfb-" + (parts.length > 1 ? parts[1] : name);
    }

    /**
     * The RX loop is joined so the USB interface is released before anything reopens it, but
     * these calls come from Activity lifecycle callbacks on the main thread, where an
     * unbounded join is a five second ANR waiting to happen. StopRxLoop() only breaks the
     * receive loop - the thread then still has to stop the TX frame and the adaptive link,
     * power the chip down, release the interface and exit libusb - so the wait has to be
     * generous, but bounded.
     */
    private static final long JOIN_TIMEOUT_MS = 3000;

    /**
     * @return true if the thread is gone and its usb connection can be released.
     */
    private static boolean joinBounded(Thread t, String what) throws InterruptedException {
        if (t == null) {
            return true;
        }
        t.join(JOIN_TIMEOUT_MS);
        if (t.isAlive()) {
            // Nothing else may be torn down while the thread is still inside devourer.
            // libusb_wrap_sys_device() keeps the fd it is given rather than duplicating it,
            // so closing the UsbDeviceConnection now pulls the fd out from under a libusb
            // that is still polling it. The kernel cancels the URBs on close but libusb
            // never reaps them - op_handle_events() looks at POLLERR and not POLLNVAL - so
            // poll() returns immediately, forever, and the loop spins on one core waiting
            // for a transfer count that will never drop. Both map entries stay too, so
            // start() can still see the thread and refuse a second RX loop on the device.
            Log.e(TAG, "wfb-ng thread on " + what + " did not stop within " + JOIN_TIMEOUT_MS
                    + "ms, leaving it and its usb connection in place");
            return false;
        }
        Log.d(TAG, "wfb-ng thread on " + what + " done.");
        return true;
    }

    public synchronized void stopAll() throws InterruptedException {
        for (Map.Entry<UsbDevice, UsbDeviceConnection> entry : linkConns.entrySet()) {
            nativeStop(nativeWfbngLink, context, entry.getValue().getFileDescriptor());
        }
        Iterator<Map.Entry<UsbDevice, UsbDeviceConnection>> it = linkConns.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UsbDevice, UsbDeviceConnection> entry = it.next();
            UsbDevice dev = entry.getKey();
            if (!joinBounded(linkThreads.get(dev), dev.getDeviceName())) {
                continue;
            }
            // Without close() every attach/detach cycle leaks the fd openDevice() handed
            // out, until the process runs out - but only once nothing is using it.
            linkThreads.remove(dev);
            it.remove();
            entry.getValue().close();
        }
    }

    public synchronized void stop(UsbDevice dev) throws InterruptedException {
        UsbDeviceConnection conn = linkConns.get(dev);
        if (conn == null) {
            return;
        }
        int fd = conn.getFileDescriptor();
        nativeStop(nativeWfbngLink, context, fd);
        if (!joinBounded(linkThreads.get(dev), dev.getDeviceName())) {
            return;
        }
        linkThreads.remove(dev);
        linkConns.remove(dev);
        conn.close();
    }

    public void SetWfbNGStatsChanged(final WfbNGStatsChanged callback) {
        statsChanged = callback;
    }

    // Called by native code via NDK.
    @Override
    public void onWfbNgStatsChanged(WfbNGStats stats) {
        if (statsChanged != null) {
            statsChanged.onWfbNgStatsChanged(stats);
        }
    }
}
