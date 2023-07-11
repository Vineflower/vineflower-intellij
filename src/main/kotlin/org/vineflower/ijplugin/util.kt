package org.vineflower.ijplugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.reflect.KProperty

fun runOnBackgroundThreadWithProgress(title: String, task: () -> Unit) {
    if (ApplicationManager.getApplication().isDispatchThread) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, title, false) {
            override fun run(indicator: ProgressIndicator) {
                task()
            }
        })
    } else {
        val indicator = ProgressManager.getInstance().progressIndicator
        val oldText = indicator?.text
        indicator?.text = title
        try {
            task()
        } finally {
            indicator?.text = oldText
        }
    }
}

class ResettableLazy<T: Any>(private val initializer: () -> T) {
    private val rwLock = ReentrantReadWriteLock()
    private var theValue: T? = null

    val value: T get() {
        rwLock.readLock().lock()
        try {
            if (theValue != null) {
                return theValue!!
            }
        } finally {
            rwLock.readLock().unlock()
        }
        rwLock.writeLock().lock()
        try {
            if (theValue != null) {
                return theValue!!
            }
            return initializer().also { theValue = it }
        } finally {
            rwLock.writeLock().unlock()
        }
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>) = value

    fun isInitialized(): Boolean {
        rwLock.readLock().lock()
        try {
            return theValue != null
        } finally {
            rwLock.readLock().unlock()
        }
    }

    fun reset() {
        rwLock.writeLock().lock()
        theValue = null
        rwLock.writeLock().unlock()
    }
}
