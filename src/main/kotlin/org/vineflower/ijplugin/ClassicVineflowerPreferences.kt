package org.vineflower.ijplugin

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.*
import javax.swing.event.DocumentEvent

object ClassicVineflowerPreferences : VineflowerPreferences() {
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
        "ban" to "//\n// Source code recreated from a .class file by Vineflower\n//\n\n", // banner
        "mpm" to 0, // max processing method
        "iib" to "1", // ignore invalid bytecode
        "vac" to "1", // verify anonymous classes
        "ind" to CodeStyle.getDefaultSettings().indentOptions.INDENT_SIZE, // indent size
        "__unit_test_mode__" to if (ApplicationManager.getApplication().isUnitTestMode) "1" else "0",
    )

    override fun setupSettings(entries: MutableList<SettingsEntry>, settingsMap: MutableMap<String, String>) {
        val classLoader = VineflowerState.getInstance().getVineflowerClassLoader().getNow(null) ?: return
        val preferencesClass = classLoader.loadClass("org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences")

        @Suppress("UNCHECKED_CAST")
        val defaults = (preferencesClass.getField("DEFAULTS").get(null) as Map<String, *>).toMutableMap()
        defaults.putAll(defaultOverrides)


        val fieldAnnotations = FieldAnnotations(classLoader)

        for (field in preferencesClass.fields) {
            if (!Modifier.isStatic(field.modifiers) || field.type != String::class.java) {
                continue
            }
            val key = inferShortKey(field, fieldAnnotations) ?: continue
            if (key in ignoredPreferences) {
                continue
            }
            val longKey = inferLongKey(field) ?: continue

            val type = inferType(key, defaults, field, fieldAnnotations) ?: continue
            val name = inferName(key, field, fieldAnnotations)
            val description = inferDescription(field, fieldAnnotations)
            val currentValue = settingsMap[key] ?: (defaults[key] ?: defaults[longKey]!!).toString()
            val component = when (type) {
                Type.BOOLEAN -> JBCheckBox().also { checkBox ->
                    checkBox.isSelected = currentValue == "1"
                    checkBox.addActionListener {
                        val newValue = if (checkBox.isSelected) "1" else "0"
                        if (newValue != defaults[key]) {
                            settingsMap[key] = newValue
                        } else {
                            settingsMap.remove(key)
                        }
                    }
                }
                Type.STRING -> JBTextField(currentValue).also { textField ->
                    textField.columns = 20
                    textField.document.addDocumentListener(object : DocumentAdapter() {
                        override fun textChanged(e: DocumentEvent) {
                            val newValue = textField.text
                            if (newValue != defaults[key]) {
                                settingsMap[key] = newValue
                            } else {
                                settingsMap.remove(key)
                            }
                        }
                    })
                }
                Type.INTEGER -> JBIntSpinner(currentValue.toInt(), 0, Int.MAX_VALUE).also { spinner ->
                    spinner.addChangeListener {
                        val newValue = spinner.value.toString()
                        if (newValue != defaults[key]) {
                            settingsMap[key] = newValue
                        } else {
                            settingsMap.remove(key)
                        }
                    }
                }
            }
            entries += SettingsEntry(name, component, description)
        }
    }

    private val nameOverrides = mapOf(
        "dc4" to "Decompile Class 1.4",
        "ind" to "Indent Size",
        "lit" to "Literals As-Is",
    )

    private fun inferLongKey(field: Field): String? {
        return field.get(null) as String?
    }

    private fun inferShortKey(field: Field, fieldAnnotations: FieldAnnotations): String? {
        if (fieldAnnotations.shortName != null) {
            val shortName = field.getAnnotation(fieldAnnotations.shortName)?.value
            if (shortName != null) {
                return shortName
            }
        }

        return field.get(null) as String?
    }

    private fun inferType(key: String, defaults: Map<String, *>, field: Field, fieldAnnotations: FieldAnnotations): Type? {
        if (fieldAnnotations.type != null) {
            when (field.getAnnotation(fieldAnnotations.type)?.value) {
                "bool" -> return Type.BOOLEAN
                "int" -> return Type.INTEGER
                "string" -> return Type.STRING
            }
        }

        val dflt = defaults[key]?.toString() ?: return null
        if (dflt == "0" || dflt == "1") {
            return Type.BOOLEAN
        }
        if (dflt.toIntOrNull() != null) {
            return Type.INTEGER
        }
        return Type.STRING
    }

    private fun inferName(key: String, field: Field, fieldAnnotations: FieldAnnotations): String {
        if (fieldAnnotations.name != null) {
            val name = field.getAnnotation(fieldAnnotations.name)?.value
            if (name != null) {
                return name
            }
        }

        val nameOverride = nameOverrides[key]
        if (nameOverride != null) {
            return nameOverride
        }

        return StringUtil.toTitleCase(field.name.replace("_", " ").lowercase(Locale.ROOT))
    }

    private fun inferDescription(field: Field, fieldAnnotations: FieldAnnotations): String? {
        if (fieldAnnotations.description != null) {
            val description = field.getAnnotation(fieldAnnotations.description)?.value
            if (description != null) {
                return description
            }
        }

        return null
    }

    private val Annotation.value get() = javaClass.getMethod("value").invoke(this) as? String

    class FieldAnnotations(classLoader: ClassLoader) {
        val name = classLoader.tryLoadAnnotation("org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences\$Name")
        val description = classLoader.tryLoadAnnotation("org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences\$Description")
        val shortName = classLoader.tryLoadAnnotation("org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences\$ShortName")
        val type = classLoader.tryLoadAnnotation("org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences\$Type")

        companion object {
            private fun ClassLoader.tryLoadAnnotation(name: String): Class<out Annotation>? {
                return try {
                    loadClass(name).asSubclass(Annotation::class.java)
                } catch (e: ClassNotFoundException) {
                    null
                } catch (e: ClassCastException) {
                    null
                }
            }
        }
    }
}
