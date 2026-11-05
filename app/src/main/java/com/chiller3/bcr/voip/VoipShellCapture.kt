package com.chiller3.bcr.voip

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.system.exitProcess

/**
 * Experimental standalone AudioPolicy sink, run ONLY in a KernelSU-controlled
 * app_process with explicit user consent. Not loaded by LSPosed and not run at boot.
 *
 * Uses the Android hidden AudioPolicy interface, as demonstrated by
 * ShizuCallRecorder (GPL-3.0). This version records the VOIP PLAYBACK channel
 * only. Capturing the local microphone concurrently could interfere with calls,
 * so it is deliberately NOT enabled here.
 */
object VoipShellCapture {
    private const val TAG = "BCR/VoIPRoot"
    private const val RATE = 48_000
    private const val MAX_MS = 2L * 60L * 60L * 1000L
    private const val MAX_BYTES = 0x7ffff000L

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 3) {
            System.err.println("BCR_VOIP_ERROR: expected output, stop-file and owner PID")
            exitProcess(2)
        }
        val output = File(args[0])
        val stop = File(args[1])
        val parentPid = args[2].toIntOrNull()?.takeIf { it > 1 }
            ?: error("Invalid owner PID")
        val owner = File("/proc/$parentPid")
        try {
            if (Looper.myLooper() == null) Looper.prepare()
            output.parentFile?.mkdirs()
            val context = frameworkContext()
            val capture = AudioPolicySink(context)
            capture.use {
                it.start()
                val begin = android.os.SystemClock.elapsedRealtime()
                val pcm = ShortArray(1920)
                var bytes = 0L
                var silentReads = 0
                RandomAccessFile(output, "rw").use { wav ->
                    wav.setLength(0)
                    repeat(44) { wav.writeByte(0) }
                    println("BCR_VOIP_STARTED")
                    System.out.flush()
                    while (!stop.exists() && owner.exists() &&
                        android.os.SystemClock.elapsedRealtime() - begin < MAX_MS &&
                        bytes < MAX_BYTES) {
                        val count = it.read(pcm)
                        when {
                            count < 0 -> throw IllegalStateException("AudioRecord.read=$count")
                            count == 0 -> {
                                silentReads++
                                if (silentReads > 1000) throw IllegalStateException("Capture returned only zero-length buffers")
                            }
                            else -> {
                                silentReads = 0
                                for (i in 0 until count) {
                                    val sample = pcm[i].toInt()
                                    wav.writeByte(sample and 255)
                                    wav.writeByte((sample shr 8) and 255)
                                }
                                bytes += count * 2L
                            }
                        }
                    }
                    writeWavHeader(wav, bytes)
                    output.setReadable(true, false)
                    println("BCR_VOIP_FINISHED bytes=$bytes")
                }
            }
            exitProcess(0)
        } catch (t: Throwable) {
            output.setReadable(true, false)
            System.err.println("BCR_VOIP_ERROR: ${t.javaClass.simpleName}: ${t.message}")
            Log.e(TAG, "VoIP shell capture failed", t)
            exitProcess(1)
        }
    }

    private fun frameworkContext(): Context {
        // app_process has framework classes available; hidden API calls can fail
        // on vendor ROMs. No context spoofing and no global permission bypass.
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("systemMain").invoke(null)
        return threadClass.getMethod("getSystemContext").invoke(thread) as Context
    }

    private fun writeWavHeader(file: RandomAccessFile, payload: Long) {
        val size = payload.coerceAtMost(0xfffffff0L).toInt()
        file.seek(0)
        file.writeBytes("RIFF")
        little32(file, size + 36)
        file.writeBytes("WAVEfmt ")
        little32(file, 16)
        little16(file, 1)
        little16(file, 1)
        little32(file, RATE)
        little32(file, RATE * 2)
        little16(file, 2)
        little16(file, 16)
        file.writeBytes("data")
        little32(file, size)
    }

    private fun little16(f: RandomAccessFile, n: Int) {
        f.writeByte(n and 255)
        f.writeByte((n ushr 8) and 255)
    }

    private fun little32(f: RandomAccessFile, n: Int) {
        f.writeByte(n and 255)
        f.writeByte((n ushr 8) and 255)
        f.writeByte((n ushr 16) and 255)
        f.writeByte((n ushr 24) and 255)
    }

    private class AudioPolicySink(private val context: Context) : AutoCloseable {
        private var policy: Any? = null
        private var recorder: AudioRecord? = null

        fun start() {
            try {
                val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
                val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
                val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
                val intType = Int::class.javaPrimitiveType!!
                ruleBuilderClass.getMethod("setTargetMixRole", intType).invoke(
                    ruleBuilder,
                    ruleClass.getField("MIX_ROLE_PLAYERS").getInt(null)
                )
                val ruleType = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
                val voice = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build()
                ruleBuilderClass.getMethod("addMixRule", intType, Any::class.java)
                    .invoke(ruleBuilder, ruleType, voice)
                ruleBuilderClass.getMethod("voiceCommunicationCaptureAllowed", Boolean::class.javaPrimitiveType!!)
                    .invoke(ruleBuilder, true)
                ruleBuilderClass.getMethod("allowPrivilegedPlaybackCapture", Boolean::class.javaPrimitiveType!!)
                    .invoke(ruleBuilder, true)
                val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

                val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
                val mixBuilder = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
                    .getConstructor(ruleClass).newInstance(rule)
                val format = AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                mixBuilder.javaClass.getMethod("setFormat", AudioFormat::class.java)
                    .invoke(mixBuilder, format)
                val flags = mixClass.getField("ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null)
                mixBuilder.javaClass.getMethod("setRouteFlags", intType).invoke(mixBuilder, flags)
                val mix = mixBuilder.javaClass.getMethod("build").invoke(mixBuilder)

                val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
                val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
                val builder = policyBuilderClass.getConstructor(Context::class.java)
                    .newInstance(context)
                policyBuilderClass.getMethod("addMix", mixClass).invoke(builder, mix)
                policy = policyBuilderClass.getMethod("build").invoke(builder)
                val registered = AudioManager::class.java.getDeclaredMethod(
                    "registerAudioPolicyStatic", policyClass
                ).apply { isAccessible = true }.invoke(null, policy) as Int
                check(registered == 0) { "AudioPolicy register failed ($registered)" }
                recorder = policyClass.getMethod("createAudioRecordSink", mixClass)
                    .invoke(policy, mix) as? AudioRecord
                    ?: error("AudioPolicy returned no capture sink")
                recorder!!.startRecording()
                check(recorder!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "AudioRecord did not start"
                }
            } catch (e: Throwable) {
                close()
                throw e
            }
        }

        fun read(buffer: ShortArray): Int =
            checkNotNull(recorder).read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)

        override fun close() {
            try {
                recorder?.let {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                    it.release()
                }
            } catch (_: Throwable) {
                // Always try to unregister the AudioPolicy.
            } finally {
                recorder = null
            }
            val active = policy ?: return
            policy = null
            runCatching {
                val cls = Class.forName("android.media.audiopolicy.AudioPolicy")
                AudioManager::class.java.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", cls)
                    .apply { isAccessible = true }.invoke(null, active)
            }.onFailure { Log.w(TAG, "Failed to unregister audio policy", it) }
        }
    }
}
