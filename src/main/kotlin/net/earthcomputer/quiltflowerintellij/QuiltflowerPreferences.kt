package net.earthcomputer.quiltflowerintellij

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.application.ApplicationManager

object QuiltflowerPreferences {
    val ignoredPreferences = setOf(
        "ban", // banner
        "bsm", // bytecode source mapping
        "nls", // newline separator
        "__unit_test_mode__",
        "log", // log level
        "urc", // use renamer class
        "thr", // threads
        "mpm", // max processing method
        "\r\n", // irrelevant constant
        "\n", // irrelevant constant
    )

    val defaultOverrides = mapOf(
        "hdc" to "0", // hide default constructor
        "dgs" to "1", // decompile generic signatures
        "rsy" to "1", // remove synthetic
        "rbr" to "1", // remove bridge
        "nls" to "1", // newline separator
        "ban" to "//\n// Source code recreated from a .class file by Quiltflower\n//\n\n", // banner
        "mpm" to 0, // max processing method
        "iib" to "1", // ignore invalid bytecode
        "vac" to "1", // verify anonymous classes
        "ind" to CodeStyle.getDefaultSettings().indentOptions.INDENT_SIZE, // indent size
        "__unit_test_mode__" to if (ApplicationManager.getApplication().isUnitTestMode) "1" else "0",
    )

    val nameOverrides = mapOf(
        "dc4" to "Decompile Class 1.4",
        "ind" to "Indent Size",
        "lit" to "Literals As-Is",
    )

    fun inferType(key: String, defaults: Map<String, *>): Type? {
        val dflt = defaults[key]?.toString() ?: return null
        if (dflt == "0" || dflt == "1") {
            return Type.BOOLEAN
        }
        if (dflt.toIntOrNull() != null) {
            return Type.INTEGER
        }
        return Type.STRING
    }

    enum class Type {
        BOOLEAN, INTEGER, STRING
    }
}
