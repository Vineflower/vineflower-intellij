package com.github.earthcomputer.quiltflowerintellijplugin.services

import com.intellij.openapi.project.Project
import com.github.earthcomputer.quiltflowerintellijplugin.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
