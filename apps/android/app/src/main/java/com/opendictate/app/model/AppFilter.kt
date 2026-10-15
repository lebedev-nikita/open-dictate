package com.opendictate.app.model

enum class AppFilterMode {
    ALL_EXCEPT_SELECTED,
    ONLY_SELECTED;

    companion object {
        fun fromStored(value: String?): AppFilterMode =
            entries.firstOrNull { it.name == value } ?: ALL_EXCEPT_SELECTED
    }
}

data class AppFilter(
    val mode: AppFilterMode = AppFilterMode.ALL_EXCEPT_SELECTED,
    val excludedPackages: Set<String> = emptySet(),
    val allowedPackages: Set<String> = emptySet(),
) {
    val selectedPackages: Set<String>
        get() = when (mode) {
            AppFilterMode.ALL_EXCEPT_SELECTED -> excludedPackages
            AppFilterMode.ONLY_SELECTED -> allowedPackages
        }

    fun allows(packageName: CharSequence?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val selected = packageName.toString() in selectedPackages
        return when (mode) {
            AppFilterMode.ALL_EXCEPT_SELECTED -> !selected
            AppFilterMode.ONLY_SELECTED -> selected
        }
    }

    fun withPackageSelected(packageName: String, selected: Boolean): AppFilter {
        val packages = if (selected) selectedPackages + packageName else selectedPackages - packageName
        return when (mode) {
            AppFilterMode.ALL_EXCEPT_SELECTED -> copy(excludedPackages = packages)
            AppFilterMode.ONLY_SELECTED -> copy(allowedPackages = packages)
        }
    }
}
