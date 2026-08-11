package com.sysadmin.lasstore.ui.catalog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sysadmin.lasstore.R
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.data.SourceBranding
import com.sysadmin.lasstore.data.SourceNewsItem
import com.sysadmin.lasstore.data.validatedSourceBrandingUrl
import com.sysadmin.lasstore.ui.theme.Catppuccin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SourceBrandingStrip(brandings: List<CatalogSourceBranding>) {
    if (brandings.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = stringResource(R.string.source_branding_section),
            style = MaterialTheme.typography.labelSmall,
            color = Catppuccin.MauveStrong,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            brandings.forEach { branding ->
                SourceBrandingCard(branding)
            }
        }
    }
}

@Composable
private fun SourceBrandingCard(source: CatalogSourceBranding) {
    val branding = source.branding
    val feedTint = parseBrandingTint(branding.tintColor)
        ?: Catppuccin.accent(source.sourceAccent)
    val uriHandler = LocalUriHandler.current
    val news = branding.news.firstOrNull { it.title.isNotBlank() }
    Surface(
        modifier = Modifier.widthIn(min = 290.dp, max = 340.dp),
        shape = RoundedCornerShape(20.dp),
        color = feedTint.copy(alpha = 0.1f),
        border = androidx.compose.foundation.BorderStroke(1.dp, feedTint.copy(alpha = 0.48f)),
    ) {
        Column {
            if (branding.headerUrl.isNullOrBlank().not()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                ) {
                    RemoteBrandingImage(
                        url = branding.headerUrl.orEmpty(),
                        contentDescription = stringResource(R.string.source_header_image),
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Crop,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .background(feedTint.copy(alpha = 0.18f)),
                    )
                }
            }
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceBrandingImageOrFallback(
                    url = branding.iconUrl,
                    tint = feedTint,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.sourceLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = Catppuccin.TextStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        branding.featuredApps.isNotEmpty() -> Text(
                            text = stringResource(
                                R.string.source_featured_apps,
                                branding.featuredApps.take(2).joinToString(" · "),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = feedTint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        news != null -> NewsPreview(news, uriHandler::openUri, feedTint)
                    }
                }
            }
            if (news != null && branding.featuredApps.isNotEmpty()) {
                NewsPreview(
                    news = news,
                    open = uriHandler::openUri,
                    tint = feedTint,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun NewsPreview(
    news: SourceNewsItem,
    open: (String) -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val url = news.url?.takeIf { validatedSourceBrandingUrl(it) != null }
    if (url == null) {
        Text(
            text = news.caption?.takeIf { it.isNotBlank() } ?: news.title,
            style = MaterialTheme.typography.bodySmall,
            color = Catppuccin.Subtext,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    } else {
        TextButton(
            onClick = { open(url) },
            modifier = modifier,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text(
                text = news.caption?.takeIf { it.isNotBlank() } ?: news.title,
                style = MaterialTheme.typography.bodySmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SourceBrandingImageOrFallback(
    url: String?,
    tint: Color,
) {
    val shape = RoundedCornerShape(15.dp)
    if (url.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(tint.copy(alpha = 0.18f), shape)
                .border(1.dp, tint.copy(alpha = 0.55f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Hub,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }
    } else {
        RemoteBrandingImage(
            url = url,
            contentDescription = stringResource(R.string.source_icon_image),
            modifier = Modifier
                .size(48.dp)
                .clip(shape)
                .border(1.dp, tint.copy(alpha = 0.55f), shape),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun RemoteBrandingImage(
    url: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = url) {
        val bytes = ServiceLocator.sourceBranding.fetchImage(url)
        value = bytes?.let { payload ->
            withContext(Dispatchers.Default) { decodeBrandingBitmap(payload) }
        }
    }
    bitmap?.let { loaded ->
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

private fun decodeBrandingBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = maxOf(
        1,
        maxOf(bounds.outWidth / MAX_BRANDING_IMAGE_EDGE, bounds.outHeight / MAX_BRANDING_IMAGE_EDGE),
    )
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

private fun parseBrandingTint(raw: String?): Color? = runCatching {
    raw?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
        Color(android.graphics.Color.parseColor(value))
    }
}.getOrNull()

private const val MAX_BRANDING_IMAGE_EDGE = 512
