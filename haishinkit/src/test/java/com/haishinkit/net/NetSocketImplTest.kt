package com.haishinkit.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NetSocketImplTest {
    @Test
    fun writesIdenticalQueuedBuffersInOrder() {
        val repeated = ByteArray(16 * 1024 * 1024) { (it % 251).toByte() }
        val different = byteArrayOf(0x01, 0x02, 0x03)
        val expected = repeated + different + repeated
        val queued = CountDownLatch(1)
        val client = NetSocketImpl()
        client.listener =
            object : NetSocket.Listener {
                override fun onInput(buffer: ByteBuffer) = Unit

                override fun onSocketError() = Unit

                override fun onConnect() {
                    client.doOutput(ByteBuffer.allocate(repeated.size).put(repeated))
                    client.doOutput(ByteBuffer.allocate(different.size).put(different))
                    client.doOutput(ByteBuffer.allocate(repeated.size).put(repeated))
                    queued.countDown()
                }

                override fun onClose(disconnected: Boolean) = Unit
            }

        ServerSocket().use { server ->
            server.receiveBufferSize = 64 * 1024
            server.soTimeout = 5000
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            try {
                client.connect("127.0.0.1", server.localPort, false)
                server.accept().use { peer ->
                    peer.soTimeout = 5000
                    assertTrue("All buffers must be queued before reading", queued.await(5, TimeUnit.SECONDS))
                    val received = ByteArray(expected.size)
                    var size = 0
                    val input = peer.getInputStream()
                    try {
                        while (size < received.size) {
                            val count = input.read(received, size, received.size - size)
                            if (count == -1) {
                                break
                            }
                            size += count
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                    assertArrayEquals(expected, received.copyOf(size))
                }
            } finally {
                client.close(false)
            }
        }
    }
}
