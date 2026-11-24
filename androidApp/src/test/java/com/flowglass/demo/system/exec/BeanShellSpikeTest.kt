package com.flowglass.demo.system.exec

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BeanShellSpikeTest {
    @Test
    fun evaluatesJavaAndSeesAndroidClasses() {
        val interpreter = bsh.Interpreter()
        interpreter.setClassLoader(BeanShellSpikeTest::class.java.classLoader)
        assertEquals(3, interpreter.eval("return 1 + 2;"))
        assertEquals("robolectric", interpreter.eval("import android.os.Build; return Build.MODEL;"))
    }
}
