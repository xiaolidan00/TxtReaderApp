package com.xld.txtreader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * 基于字符串事件名的线程安全 EventEmitter
 */
class StringEventEmitter {

    // 核心存储结构：Key 为字符串事件名，Value 为对应的监听器列表
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(Any?) -> Unit>>()

    /**
     * 注册事件监听器
     * @param eventName 字符串类型的事件名称
     * @param listener 回调函数，接收可选的事件数据参数
     * @return 返回一个可取消的订阅对象，方便后续销毁
     */
    fun on(eventName: String, listener: (Any?) -> Unit): Subscription {
        listeners.computeIfAbsent(eventName) { CopyOnWriteArrayList() }.add(listener)
        return object : Subscription {
            override fun cancel() {
                off(eventName, listener)
            }
        }
    }



    /**
     * 销毁/注销指定事件的监听器
     * @param eventName 事件名称
     * @param listener 需要移除的监听器实例（必须与注册时是同一个对象引用）
     */
    fun off(eventName: String, listener: (Any?) -> Unit) {
        val list = listeners[eventName]
        if (list != null) {
            list.remove(listener)
            // 如果该事件名下已无任何监听器，清理 Map 中的 Key 以节省内存
            if (list.isEmpty()) {
                listeners.remove(eventName)
            }
        }
    }

    /**
     * 触发事件（同步执行）
     * @param eventName 事件名称
     * @param data 可选的事件携带数据
     */
    fun emit(eventName: String, data: Any? = null) {
        val list = listeners[eventName]
        if (list == null || list.isEmpty()) return
        
        // 遍历并调用监听器，加入异常隔离，防止单个监听器崩溃影响其他监听器
        for (listener in list) {
            try {
                listener(data)
            } catch (e: Exception) {
                println("Error in listener for event '$eventName': ${e.message}")
            }
        }
    }

    /**
     * 触发事件（异步执行）
     */
    fun emitAsync(eventName: String, data: Any? = null) {
        thread(start = true, isDaemon = true) {
            emit(eventName, data)
        }
    }

    /**
     * 销毁指定事件名下的所有监听器
     */
    fun removeAllListeners(eventName: String) {
        listeners.remove(eventName)
    }

    /**
     * 清空所有事件及监听器
     */
    fun clear() {
        listeners.clear()
    }
}

/**
 * 订阅接口，用于取消注册
 */
interface Subscription {
    fun cancel()
}

val eventEmitter = StringEventEmitter()

const val EVENT_TTS_PLAY = "com.xld.txtreader.tts.PLAY"
const val EVENT_TTS_PAUSE = "com.xld.txtreader.tts.PAUSE"
const val EVENT_TTS_STOP = "com.xld.txtreader.tts.STOP"
const val EVENT_TTS_TOGGLE = "com.xld.txtreader.tts.TOGGLE"
const val EVENT_TTS_NEXT_PAGE = "com.xld.txtreader.tts.NEXT_PAGE"
const val EVENT_TTS_PREV_PAGE = "com.xld.txtreader.tts.PREV_PAGE"
const val EVENT_TTS_NEXT_CHAPTER = "com.xld.txtreader.tts.NEXT_CHAPTER"
const val EVENT_TTS_PREV_CHAPTER = "com.xld.txtreader.tts.PREV_CHAPTER"
const val EVENT_TTS_SPEED = "com.xld.txtreader.tts.SPEED"
const val EVENT_TTS_DONE = "com.xld.txtreader.tts.DONE"
const val EVENT_TTS_START = "com.xld.txtreader.tts.START"
const val EVENT_TTS_READY = "com.xld.txtreader.tts.READY"
const val EVENT_TTS_STOPPED = "com.xld.txtreader.tts.STOPPED"