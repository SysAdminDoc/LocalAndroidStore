package com.sysadmin.lasstore.domain

import com.sysadmin.lasstore.data.GhAsset

data class AppInfo(
    val owner: String,
    val repo: String,
    val sourceKey: String,
    val sourceLabel: String,
    val displayName: String,
    val description: String?,
    val stars: Int,
    val htmlUrl: String,
    val tagName: String,
    val versionName: String?,
    val versionCode: Long?,
    val applicationId: String?,
    val asset: GhAsset,
    val publishedAt: String?,
    val prerelease: Boolean,
) {
    val handle: String get() = "$owner/$repo"
}

enum class CardStatus { NotInstalled, Installed, UpdateAvailable, Working, Error, SignatureMismatch }
