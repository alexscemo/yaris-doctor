package com.yarisdoctor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * ELM327 over Bluetooth Low Energy, for dongles with no SPP (or the BLE side of a dual-mode one, such as the Vgate iCar
 * Pro 2S's "IOS-Vlink"). It offers the same two byte streams as an SPP socket, so [Elm] can't tell them apart. Connected
 * by address from the bonded list, so it needs BLUETOOTH_CONNECT only, never a scan. Throws IOException when the dongle
 * isn't there or has no serial-like GATT service.
 */
@SuppressLint("MissingPermission") // DpfService.start() only runs with BLUETOOTH_CONNECT granted
class BleLink(context: Context, device: BluetoothDevice) : Closeable {
    private val rx = LinkedBlockingQueue<Int>()
    private val ready = CountDownLatch(1)
    private val written = Semaphore(0)
    @Volatile private var tx: BluetoothGattCharacteristic? = null
    @Volatile private var dead = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                drop()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val (notify, write) = pick(g.services) ?: return ready.countDown()
            g.setCharacteristicNotification(notify, true)
            tx = write
            // A few dongles notify without a CCCD; the rest need it written before they send anything
            val cccd = notify.getDescriptor(CCCD) ?: return ready.countDown()
            val on = if (notify.properties and PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            val sent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, on) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = on
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!sent) ready.countDown()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = ready.countDown()

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = written.release()

        // API 33+ calls only this one
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = received(value)

        @Deprecated("API 31-32 only")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) = received(c.value)
    }

    private val gatt: BluetoothGatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        ?: throw IOException("connectGatt refused")

    init {
        if (!ready.await(CONNECT_MS, MILLISECONDS) || tx == null || dead) {
            close()
            throw IOException("no BLE serial service on ${device.address}")
        }
    }

    val inputStream: InputStream = object : InputStream() {
        override fun read() = rx.take()
    }

    /** Buffers until flush(), then writes in chunks that fit the default 23-byte MTU, each one acknowledged. */
    val outputStream: OutputStream = object : OutputStream() {
        private val buf = ByteArrayOutputStream()
        override fun write(b: Int) = buf.write(b)
        override fun flush() {
            val bytes = buf.toByteArray()
            buf.reset()
            for (i in bytes.indices step CHUNK) send(bytes.copyOfRange(i, minOf(i + CHUNK, bytes.size)))
        }
    }

    private fun send(chunk: ByteArray) {
        val c = tx ?: throw IOException("BLE link closed")
        val type = if (c.properties and PROPERTY_WRITE_NO_RESPONSE != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        written.drainPermits()
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(c, chunk, type) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type
            @Suppress("DEPRECATION")
            c.value = chunk
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(c)
        }
        if (!ok || !written.tryAcquire(WRITE_MS, MILLISECONDS) || dead) throw IOException("BLE write failed")
    }

    private fun received(value: ByteArray?) {
        value?.forEach { rx.put(it.toInt() and 0xFF) }
    }

    private fun drop() {
        dead = true
        rx.put(-1)
        ready.countDown()
        written.release()
    }

    override fun close() {
        drop()
        gatt.disconnect()
        gatt.close()
    }

    companion object {
        private const val CONNECT_MS = 15_000L
        private const val WRITE_MS = 2_000L
        private const val CHUNK = 20
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Serial services of known BLE ELM327s (FFF0, FFE0, 18F0, Vgate's own), tried before any other. */
        private val KNOWN = listOf("fff0", "ffe0", "18f0").map { UUID.fromString("0000$it-0000-1000-8000-00805f9b34fb") } +
            UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")

        /** The first service with a characteristic that notifies and one that takes writes (often the same one). */
        fun pick(services: List<BluetoothGattService>): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
            for (s in services.sortedBy { it.uuid !in KNOWN }) {
                val notify = s.characteristics.firstOrNull { it.properties and (PROPERTY_NOTIFY or PROPERTY_INDICATE) != 0 } ?: continue
                val write = s.characteristics.firstOrNull { it.properties and (PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE) != 0 } ?: continue
                return notify to write
            }
            return null
        }
    }
}
