package net.earthcomputer.quiltflowerintellij

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.text.StringUtil
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.*

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

    fun inferName(key: String, field: Field, fieldAnnotations: FieldAnnotations?): String {
        if (fieldAnnotations != null) {
            val name = field.getAnnotation(fieldAnnotations.name)
            if (name != null) {
                return fieldAnnotations.nameValue.invoke(name) as String
            }
        }

        val nameOverride = nameOverrides[key]
        if (nameOverride != null) {
            return nameOverride
        }

        return StringUtil.toTitleCase(field.name.replace("_", " ").toLowerCase(Locale.ROOT))
    }

    fun inferDescription(field: Field, fieldAnnotations: FieldAnnotations?): String? {
        if (fieldAnnotations != null) {
            val description = field.getAnnotation(fieldAnnotations.description)
            if (description != null) {
                return fieldAnnotations.descriptionValue.invoke(description) as String
            }
        }

        return null
    }

    enum class Type {
        BOOLEAN, INTEGER, STRING
    }

    class FieldAnnotations(val name: Class<out Annotation>, val description: Class<out Annotation>) {
        val nameValue: Method = name.getMethod("value")
        val descriptionValue: Method = description.getMethod("value")
    }
}
