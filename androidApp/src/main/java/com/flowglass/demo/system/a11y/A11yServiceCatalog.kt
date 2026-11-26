package com.flowglass.demo.system.a11y

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/** One accessibility service, keyed by its flattened component. */
data class A11yServiceEntry(
    val component: String,
    val packageName: String,
    val className: String,
    val serviceLabel: String,
    val appLabel: String,
    val enabled: Boolean,
    val isSelf: Boolean,
)

/** Test seam: what [AccessibilityManager] would report, without touching the framework. */
internal data class RawA11yService(
    val packageName: String,
    val className: String,
    val serviceLabel: String,
    val appLabel: String,
)

object A11yServiceCatalog {
    private val COMPONENT = Regex("""^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+$""")

    fun masterEnabled(context: Context): Boolean =
        readSecure(context, Settings.Secure.ACCESSIBILITY_ENABLED) == "1"

    fun enabledComponents(context: Context): Set<String> = parseEnabledComponents(
        readSecure(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
    )

    /**
     * Reads the provider directly instead of going through [Settings.Secure]'s per-process cache.
     * On ROMs that freeze background apps the cache can lag behind a change the user just made in
     * system settings, which showed up as an enabled service still listing as disabled.
     */
    private fun readSecure(context: Context, key: String): String? {
        val fromProvider = runCatching {
            context.contentResolver.query(
                Settings.Secure.CONTENT_URI,
                arrayOf(Settings.Secure.VALUE),
                "${Settings.Secure.NAME} = ?",
                arrayOf(key),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return fromProvider ?: Settings.Secure.getString(context.contentResolver, key)
    }

    /** `com.a/.A` is normalised to `com.a/com.a.A`; malformed entries are dropped, never thrown. */
    internal fun parseEnabledComponents(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(':')
            .mapNotNull(::canonicalComponent)
            .filter { COMPONENT.matches(it) }
            .toSet()
    }

    /**
     * The same service reaches us in two spellings: the framework persists the short form
     * (`pkg/.Class`) while a declared component name may already be fully qualified. Comparing
     * the raw strings therefore reports an enabled service as disabled.
     */
    internal fun canonicalComponent(component: String): String? {
        val parsed = ComponentName.unflattenFromString(component) ?: return null
        val className = if (parsed.className.startsWith(".")) {
            parsed.packageName + parsed.className
        } else {
            parsed.className
        }
        return ComponentName(parsed.packageName, className).flattenToString()
    }

    fun load(context: Context, selfPackage: String = context.packageName): List<A11yServiceEntry> = merge(
        installed = installedServices(context),
        enabled = enabledComponents(context),
        selfPackage = selfPackage,
    )

    internal fun merge(
        installed: List<RawA11yService>,
        enabled: Set<String>,
        selfPackage: String,
    ): List<A11yServiceEntry> {
        val byComponent = LinkedHashMap<String, A11yServiceEntry>()
        for (raw in installed) {
            val component = canonicalComponent(
                ComponentName(raw.packageName, raw.className).flattenToString(),
            ) ?: continue
            byComponent[component] = A11yServiceEntry(
                component = component,
                packageName = raw.packageName,
                className = raw.className,
                serviceLabel = raw.serviceLabel,
                appLabel = raw.appLabel,
                enabled = component in enabled,
                isSelf = raw.packageName == selfPackage,
            )
        }
        // A component can stay in the enabled setting after its package is uninstalled. Show it so
        // the user can see why nothing happens, instead of silently hiding it.
        for (component in enabled) {
            if (component in byComponent) continue
            val parsed = ComponentName.unflattenFromString(component) ?: continue
            val canonical = canonicalComponent(component) ?: continue
            byComponent[canonical] = A11yServiceEntry(
                component = canonical,
                packageName = parsed.packageName,
                className = parsed.className,
                serviceLabel = parsed.className.substringAfterLast('.'),
                appLabel = parsed.packageName,
                enabled = true,
                isSelf = parsed.packageName == selfPackage,
            )
        }
        return byComponent.values.sortedWith(
            compareByDescending<A11yServiceEntry> { it.isSelf }
                .thenByDescending { it.enabled }
                .thenBy { it.appLabel.lowercase() }
                .thenBy { it.serviceLabel.lowercase() },
        )
    }

    private fun installedServices(context: Context): List<RawA11yService> {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return emptyList()
        val pm = context.packageManager
        return manager.getInstalledAccessibilityServiceList().mapNotNull { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo ?: return@mapNotNull null
            val appLabel = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(serviceInfo.packageName, 0)).toString()
            }.getOrNull().orEmpty().ifBlank { serviceInfo.packageName }
            val serviceLabel = runCatching { info.resolveInfo.loadLabel(pm).toString() }
                .getOrNull()?.takeIf { it.isNotBlank() }
                ?: serviceInfo.name.substringAfterLast('.')
            RawA11yService(
                packageName = serviceInfo.packageName,
                className = serviceInfo.name,
                serviceLabel = serviceLabel,
                appLabel = appLabel,
            )
        }
    }
}
