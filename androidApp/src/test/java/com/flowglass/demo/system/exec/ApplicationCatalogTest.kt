package com.flowglass.demo.system.exec

import android.graphics.drawable.ColorDrawable
import com.flowglass.engine.ApplicationScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApplicationCatalogTest {
    private fun app(
        packageName: String,
        label: String,
        userId: Int = 0,
        profileLabel: String = if (userId == 0) "当前用户" else "用户 $userId",
    ) = LaunchableApp(
        packageName = packageName,
        label = label,
        icon = ColorDrawable(0xff000000.toInt()),
        userId = userId,
        profileLabel = profileLabel,
    )

    private val apps = listOf(
        app("com.example.chat", "聊天", 0, "当前用户"),
        app("com.example.camera", "相机", 0, "当前用户"),
        app("com.example.chat", "聊天", 10, "工作资料"),
        app("com.example.clone", "聊天分身", 999, "Root · XSpace"),
    )

    @Test fun searchMatchesLabelPackageIdAndProfileName() {
        assertEquals(listOf("com.example.chat"), filterApplications(apps, "example.chat", ApplicationFilter.ALL)
            .map { it.packageName }.distinct())
        assertEquals(listOf("聊天分身"), filterApplications(apps, "XSpace", ApplicationFilter.ALL)
            .map { it.label })
        assertEquals(listOf("相机"), filterApplications(apps, "相机", ApplicationFilter.ALL)
            .map { it.label })
        assertEquals(listOf("com.example.clone@999"), filterApplications(apps, "clone@999", ApplicationFilter.ALL)
            .map { it.id })
    }

    @Test fun mainAndCloneFiltersKeepTheSamePackageSeparate() {
        assertEquals(setOf("com.example.chat", "com.example.camera"),
            filterApplications(apps, "", ApplicationFilter.MAIN).map { it.id }.toSet())
        assertEquals(setOf("com.example.chat@10", "com.example.clone@999"),
            filterApplications(apps, "", ApplicationFilter.CLONES).map { it.id }.toSet())
    }

    @Test fun groupingShowsCurrentWorkAndCloneProfilesSeparately() {
        val groups = groupApplications(apps)
        assertEquals(listOf("当前资料", "工作资料", "分身 · XSpace"), groups.map { it.title })
        assertEquals(2, groups.first().apps.size)
        assertEquals("com.example.chat@10", groups[1].apps.single().id)
    }

    @Test fun manualTargetKeepsLegacyBareFormAndNormalizesZero() {
        assertEquals("com.example", ApplicationCatalog.normalizeTarget("  com.example  ",
            ApplicationScope.ALL_PROFILES).getOrThrow())
        assertEquals("com.example@10", ApplicationCatalog.normalizeTarget("com.example@10",
            ApplicationScope.ALL_PROFILES).getOrThrow())
        assertEquals("com.example", ApplicationCatalog.normalizeTarget("com.example@0",
            ApplicationScope.ALL_PROFILES).getOrThrow())
    }

    @Test fun currentProfilePickerRejectsAnotherProfilesTarget() {
        assertTrue(ApplicationCatalog.normalizeTarget("com.example@10",
            ApplicationScope.CURRENT_PROFILE).isFailure)
        assertTrue(ApplicationCatalog.normalizeTarget("bad-name",
            ApplicationScope.ALL_PROFILES).isFailure)
    }

    @Test fun currentProfileScopeFiltersOutWorkAndCloneRows() {
        assertEquals(listOf("com.example.chat", "com.example.camera"),
            applicationsForScope(apps, ApplicationScope.CURRENT_PROFILE).map { it.id })
        assertEquals(4, applicationsForScope(apps, ApplicationScope.ALL_PROFILES).size)
    }

    @Test fun rootAutoScanRequiresRememberedGrantAndAllProfiles() {
        assertTrue(shouldAutoScanRoot(ApplicationScope.ALL_PROFILES, rootAvailable = true, rememberedGrant = true))
        assertTrue(!shouldAutoScanRoot(ApplicationScope.ALL_PROFILES, rootAvailable = true, rememberedGrant = false))
        assertTrue(!shouldAutoScanRoot(ApplicationScope.ALL_PROFILES, rootAvailable = false, rememberedGrant = true))
        assertTrue(!shouldAutoScanRoot(ApplicationScope.CURRENT_PROFILE, rootAvailable = true, rememberedGrant = true))
    }
}
