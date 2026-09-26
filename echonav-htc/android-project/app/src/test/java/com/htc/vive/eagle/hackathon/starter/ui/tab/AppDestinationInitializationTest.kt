package com.htc.vive.eagle.hackathon.starter.ui.tab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class AppDestinationInitializationTest {
    @Test
    fun everyDestinationCanBeInitializedBeforeTheNavigationLists() {
        listOf("EchoNav", "EchoTest", "Glasses", "Chat", "Audio", "Camera").forEach { first ->
            verifyFreshInitialization("$DESTINATION_CLASS\$$first")
        }
    }

    @Test
    fun theCompanionCanAlsoBeInitializedBeforeAnyDestination() {
        verifyFreshInitialization(DESTINATION_CLASS)
    }

    private fun verifyFreshInitialization(firstClass: String) {
        val loader = FreshDestinations(checkNotNull(javaClass.classLoader))
        // Each scenario gets real, uninitialized copies of all destination classes;
        // test order and any other tests loading AppDestination cannot hide the cycle.
        Class.forName(firstClass, true, loader)
        val base = Class.forName(DESTINATION_CLASS, true, loader)
        val companion = base.getField("Companion").get(null)
        fun routes(getter: String): List<String> {
            val items = companion.javaClass.getMethod(getter).invoke(companion) as List<*>
            return items.map { item ->
                assertNotNull("$firstClass produced a null entry in $getter", item)
                assertSame(loader, item!!.javaClass.classLoader)
                base.getMethod("getRoute").invoke(item) as String
            }
        }
        assertEquals(listOf("echonav", "echotest"), routes("getEchoItems"))
        assertEquals(listOf("glasses", "chat", "audio", "camera"), routes("getDiagnosticItems"))
    }

    private class FreshDestinations(private val sourceLoader: ClassLoader) : ClassLoader(sourceLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name != DESTINATION_CLASS && !name.startsWith("$DESTINATION_CLASS\$")) {
                return super.loadClass(name, resolve)
            }
            return synchronized(this) {
                val loaded = findLoadedClass(name) ?: run {
                    val path = name.replace('.', '/') + ".class"
                    val bytes = checkNotNull(sourceLoader.getResourceAsStream(path)) { "Missing $path" }
                        .use { it.readBytes() }
                    defineClass(name, bytes, 0, bytes.size)
                }
                if (resolve) resolveClass(loaded)
                loaded
            }
        }
    }

    companion object {
        private const val DESTINATION_CLASS = "com.htc.vive.eagle.hackathon.starter.ui.tab.AppDestination"
    }
}
