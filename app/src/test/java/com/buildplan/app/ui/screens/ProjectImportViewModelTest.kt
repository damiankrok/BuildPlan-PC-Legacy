package com.buildplan.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN024-VM — the import screen's view model can actually be constructed by the
 * factory that will construct it.
 *
 * This exists because the failure it catches is invisible everywhere except on
 * a device. `viewModel()` with no factory falls back to
 * `AndroidViewModelFactory`, which reflects for a constructor taking exactly
 * one `Application`. A Kotlin constructor with a defaulted second parameter
 * compiles to `(Application, Service, int, DefaultConstructorMarker)` plus a
 * synthetic bridge — and no plain `(Application)` at all, unless
 * `@JvmOverloads` generates one. Compilation is happy, unit tests that call the
 * constructor directly are happy, and the screen throws
 * `NoSuchMethodException` the first time a person opens it.
 *
 * Reflection only: nothing here instantiates an Android class.
 */
class ProjectImportViewModelTest {

    @Test
    fun `the view model exposes the constructor AndroidViewModelFactory looks for`() {
        val constructor = ProjectImportViewModel::class.java.getConstructor(Application::class.java)
        assertTrue("the factory needs a public (Application) constructor", constructor.parameterCount == 1)
    }

    @Test
    fun `it is an AndroidViewModel, which is what makes that the right constructor`() {
        assertTrue(AndroidViewModel::class.java.isAssignableFrom(ProjectImportViewModel::class.java))
        assertTrue(ViewModel::class.java.isAssignableFrom(ProjectImportViewModel::class.java))
    }
}
