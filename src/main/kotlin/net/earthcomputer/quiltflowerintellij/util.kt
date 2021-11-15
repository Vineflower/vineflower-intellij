package net.earthcomputer.quiltflowerintellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task

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
