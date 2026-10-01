package com.luqman.luckysaver.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Avatar from a remote picture, falling back to initials when the link has expired or is absent. */
@Composable
fun Avatar(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(url == null) }
    Box(
        modifier
            .padding(0.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Text(
                name.trimStart('@', '_', '.').take(1).uppercase(Locale.getDefault()).ifEmpty { "?" },
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontSize = (size.value * 0.4f).sp,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** Day group heading: bold label left, quiet count right, on the 16dp margin. */
@Composable
fun DayHeader(label: String, count: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shrinks slightly while held, springs back on release. */
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

object When {
    private val zone get() = ZoneId.systemDefault()

    fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun dayLabel(day: LocalDate): String {
        val today = LocalDate.now(zone)
        return when {
            day == today -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            day.isAfter(today.minusDays(7)) -> day.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
            day.year == today.year -> day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
            else -> day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
        }
    }

    /** Clock time in the phone's own 12/24-hour setting. */
    fun time(context: Context, millis: Long): String =
        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(millis))

    /** Short stamp for a tile: the time today, weekday and time this week, the date before that. */
    fun stamp(context: Context, millis: Long): String {
        val d = day(millis)
        val today = LocalDate.now(zone)
        return when {
            d == today -> time(context, millis)
            d.isAfter(today.minusDays(7)) ->
                d.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) + " " + time(context, millis)
            else -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        }
    }

    /** "Saved today at 12:35", "Saved yesterday at 22:41", "Saved 3 Oct at 09:10". */
    fun savedPhrase(context: Context, millis: Long): String {
        val d = day(millis)
        val today = LocalDate.now(zone)
        val dayPart = when (d) {
            today -> "today"
            today.minusDays(1) -> "yesterday"
            else -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        }
        return "Saved $dayPart at ${time(context, millis)}"
    }

    /** "posted 2 h earlier", for a saved story's distance from its posting time. */
    fun postedBefore(savedAt: Long, takenAt: Long): String? {
        if (takenAt <= 0 || takenAt > savedAt) return null
        val minutes = (savedAt - takenAt) / 60_000
        return when {
            minutes < 1 -> "posted moments earlier"
            minutes < 60 -> "posted $minutes min earlier"
            minutes < 48 * 60 -> "posted ${minutes / 60} h earlier"
            else -> "posted ${minutes / (24 * 60)} days earlier"
        }
    }

    fun duration(ms: Long): String {
        val s = (ms + 500) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }
}

/**
 * Shared-element plumbing: grids and the viewer read these instead of threading two scopes
 * through every screen. Null means "no transition available", and callers draw normally.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalContentScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedMedia(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val content = LocalContentScope.current ?: return this
    return with(shared) {
        this@sharedMedia.sharedElement(
            rememberSharedContentState("media-$key"),
            animatedVisibilityScope = content,
        )
    }
}
