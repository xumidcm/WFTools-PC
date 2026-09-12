package com.ailife.wftools.pc.platform

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel

/** One document manager per Windows user, with authenticated loopback open requests. */
class InstanceBroker private constructor(
    private val channel:FileChannel,private val lock:FileLock,
    private val socket:ServerSocket,private val token:String,
) : AutoCloseable {
    val requests=Channel<List<Path>>(Channel.UNLIMITED)
    private val closed=AtomicBoolean(false)
    private val listener=Thread({
        while(!closed.get()) {
            runCatching {socket.accept().use {client->
                client.soTimeout=2000
                val input=DataInputStream(client.getInputStream())
                require(input.readUTF()==token){"Invalid instance token"}
                val count=input.readInt();require(count in 0..64)
                val paths=(0 until count).map{Path.of(input.readUTF())}
                requests.trySend(paths)
                DataOutputStream(client.getOutputStream()).apply{writeBoolean(true);flush()}
            }}
        }
    },"wftools-open-requests").apply{isDaemon=true;start()}
    override fun close(){if(closed.compareAndSet(false,true)){socket.close();requests.close();lock.release();channel.close()}}
    companion object {
        fun openOrForward(paths:List<Path>,directory:Path=appData().resolve("instance")):InstanceBroker? {
            require(paths.size<=64){"一次最多打开 64 个表盘。"}
            Files.createDirectories(directory)
            val channel=FileChannel.open(directory.resolve("application.lock"),CREATE,WRITE)
            val lock=try{channel.tryLock()}catch(_:OverlappingFileLockException){null}
            if(lock!=null) {
                try {
                    val socket=ServerSocket(0,16,InetAddress.getLoopbackAddress())
                    val token=UUID.randomUUID().toString()
                    Files.writeString(directory.resolve("endpoint"),"${socket.localPort}\n$token")
                    return InstanceBroker(channel,lock,socket,token).also{it.requests.trySend(paths)}
                } catch(error:Throwable){lock.release();channel.close();throw error}
            }
            channel.close()
            var failure:Throwable?=null
            repeat(30) {
                try {
                    val endpoint=Files.readAllLines(directory.resolve("endpoint"))
                    Socket(InetAddress.getLoopbackAddress(),endpoint[0].toInt()).use {socket->
                        socket.soTimeout=2000
                        DataOutputStream(socket.getOutputStream()).apply{
                            writeUTF(endpoint[1]);writeInt(paths.size);paths.forEach{writeUTF(it.toAbsolutePath().normalize().toString())};flush()
                        }
                        check(DataInputStream(socket.getInputStream()).readBoolean())
                    }
                    return null
                }catch(error:Exception){failure=error;Thread.sleep(100)}
            }
            throw IllegalStateException("已有窗口未能接收打开请求，请稍后重试。",failure)
        }
    }
}
fun appData():Path=Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),"WFTools-PC")
