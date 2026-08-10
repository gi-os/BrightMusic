package com.thelightphone.sdk.ui

import com.lightphone.spotify.R as AppR

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ============================================================================
// THEME
// ============================================================================

@Immutable
data class LightColors(
    val background: Color,
    val content: Color,
    val contentSecondary: Color,
)

@Immutable
data class LightTypography(
    val title: TextStyle,
    val subtitle: TextStyle,
    val heading: TextStyle,
    val subheading: TextStyle,
    val copy: TextStyle,
    val button: TextStyle,
    val paragraph: TextStyle,
    val paragraphWide: TextStyle,
    val detail: TextStyle,
    val fine: TextStyle,
    val superfine: TextStyle,
    val micro: TextStyle,
)

enum class LightSurfaceScheme { Dark, Light }

object LightThemeColors {
    val Dark = LightColors(
        background = Color.Black,
        content = Color.White,
        contentSecondary = Color(0xFFBBBBBB),
    )
    val Light = LightColors(
        background = Color.White,
        content = Color.Black,
        contentSecondary = Color(0xFF666666),
    )
}

fun LightColors.inferredSurfaceScheme(): LightSurfaceScheme =
    if (background.luminance() > 0.5f) LightSurfaceScheme.Light else LightSurfaceScheme.Dark

// Kyocera-appropriate font sizes (480x854 screen, not 1080x1240).
private fun buildTypography(): LightTypography = LightTypography(
    title = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Light, lineHeight = 36.sp),
    subtitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    heading = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    subheading = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    copy = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    button = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp, lineHeight = 16.sp),
    paragraph = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    paragraphWide = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 17.sp),
    detail = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    fine = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.3.sp, lineHeight = 14.sp),
    superfine = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal, lineHeight = 12.sp),
    micro = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Normal, lineHeight = 10.sp),
)

private val DefaultTypography = buildTypography()

val LocalLightColors = staticCompositionLocalOf { LightThemeColors.Dark }
val LocalLightTypography = staticCompositionLocalOf { DefaultTypography }
val LocalLightSurfaceScheme = staticCompositionLocalOf { LightSurfaceScheme.Dark }

@Composable
fun rememberLightTypography(): LightTypography = DefaultTypography

object LightThemeTokens {
    val colors: LightColors @Composable get() = LocalLightColors.current
    val typography: LightTypography @Composable get() = LocalLightTypography.current
    val surfaceScheme: LightSurfaceScheme @Composable get() = LocalLightSurfaceScheme.current
}

@Composable
fun LightTheme(
    colors: LightColors = LightThemeColors.Dark,
    typography: LightTypography = DefaultTypography,
    surfaceScheme: LightSurfaceScheme = colors.inferredSurfaceScheme(),
    content: @Composable () -> Unit,
) {
    val cs = when (surfaceScheme) {
        LightSurfaceScheme.Dark -> darkColorScheme(
            background = colors.background, surface = colors.background,
            onBackground = colors.content, onSurface = colors.content,
            primary = colors.content, onPrimary = colors.background,
            secondary = colors.contentSecondary, onSecondary = colors.background,
        )
        LightSurfaceScheme.Light -> androidx.compose.material3.lightColorScheme(
            background = colors.background, surface = colors.background,
            onBackground = colors.content, onSurface = colors.content,
            primary = colors.content, onPrimary = colors.background,
            secondary = colors.contentSecondary, onSecondary = colors.background,
        )
    }
    CompositionLocalProvider(
        LocalLightColors provides colors,
        LocalLightTypography provides typography,
        LocalLightSurfaceScheme provides surfaceScheme,
    ) {
        MaterialTheme(colorScheme = cs, content = content)
    }
}

// ============================================================================
// GRID — simplified for small screen
// ============================================================================

object LightGrid {
    const val WIDTH = 27
    const val HEIGHT = 31
}

@Composable
fun Float.gridUnitsAsDp(): Dp = (this * 5f).dp  // ~5dp per grid unit on 480px screen

@Composable
fun Float.verticalGridUnitsAsDp(): Dp = (this * 6f).dp

@Composable
fun Float.designVerticalPxToSp(): TextUnit = this.sp

@Composable
fun Float.designVerticalPxToDp(): Dp = this.dp

// ============================================================================
// TEXT
// ============================================================================

enum class LightTextVariant {
    Title, Subtitle, Heading, Subheading, Copy, Button,
    Paragraph, ParagraphWide, Detail, Fine, Superfine, Micro,
}

@Composable
fun lightTextStyle(variant: LightTextVariant): TextStyle {
    val t = LightThemeTokens.typography
    return when (variant) {
        LightTextVariant.Title -> t.title
        LightTextVariant.Subtitle -> t.subtitle
        LightTextVariant.Heading -> t.heading
        LightTextVariant.Subheading -> t.subheading
        LightTextVariant.Copy -> t.copy
        LightTextVariant.Button -> t.button
        LightTextVariant.Paragraph -> t.paragraph
        LightTextVariant.ParagraphWide -> t.paragraphWide
        LightTextVariant.Detail -> t.detail
        LightTextVariant.Fine -> t.fine
        LightTextVariant.Superfine -> t.superfine
        LightTextVariant.Micro -> t.micro
    }
}

@Composable
fun LightText(
    text: String,
    variant: LightTextVariant,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    lighten: Boolean = false,
    underline: Boolean = false,
    monospace: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    color: Color? = null,
) {
    val colors = LightThemeTokens.colors
    val baseColor = when {
        color != null -> color
        lighten -> colors.contentSecondary
        else -> colors.content
    }
    val style = lightTextStyle(variant)
        .let { if (align != null) it.copy(textAlign = align) else it }
        .let { if (underline) it.copy(textDecoration = TextDecoration.Underline) else it }
        .let { if (monospace) it.copy(fontFamily = FontFamily.Monospace) else it }
    Text(
        text = text, modifier = modifier,
        color = baseColor.takeUnless { it == Color.Unspecified } ?: LocalContentColor.current,
        style = style, maxLines = maxLines, overflow = overflow,
    )
}

// ============================================================================
// CLICKABLE
// ============================================================================

fun Modifier.lightClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = null, indication = null,
    enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick,
)

// ============================================================================
// ICONS
// ============================================================================

sealed class LightIconConfiguration(
    val name: String,
    val darkModeResource: Int,
    val lightModeResource: Int,
)

object LightIcons {
    object ACCEPT : LightIconConfiguration("confirm", AppR.drawable.ic_accept_white, AppR.drawable.ic_accept_black)
    object ADD : LightIconConfiguration("Add", AppR.drawable.ic_add_white, AppR.drawable.ic_add_black)
    object AIRPLANE : LightIconConfiguration("airplane", AppR.drawable.ic_airplane_white, AppR.drawable.ic_airplane_black)
    object ALARM : LightIconConfiguration("alarm", AppR.drawable.ic_alarm_white, AppR.drawable.ic_alarm_black)
    object ARROW_DOWN : LightIconConfiguration("arrow pointing down", AppR.drawable.ic_arrow_down_white, AppR.drawable.ic_arrow_down_black)
    object ARROW_RIGHT : LightIconConfiguration("arrow right", AppR.drawable.ic_arrow_right_white, AppR.drawable.ic_arrow_right_black)
    object AUDIO_MESSAGE : LightIconConfiguration("audio message", AppR.drawable.ic_audio_message_white, AppR.drawable.ic_audio_message_black)
    object BACK : LightIconConfiguration("back", AppR.drawable.ic_back_white, AppR.drawable.ic_back_black)
    object BATTERY_ERROR : LightIconConfiguration("battery empty", AppR.drawable.ic_battery_error_white, AppR.drawable.ic_battery_error_black)
    object BATTERY_EMPTY : LightIconConfiguration("battery empty", AppR.drawable.ic_battery_empty_white, AppR.drawable.ic_battery_empty_black)
    object BATTERY_ONE_QUARTER : LightIconConfiguration("battery one quarter full", AppR.drawable.ic_battery_one_quarter_white, AppR.drawable.ic_battery_one_quarter_black)
    object BATTERY_HALF : LightIconConfiguration("battery half full", AppR.drawable.ic_battery_half_white, AppR.drawable.ic_battery_half_black)
    object BATTERY_THREE_QUARTERS : LightIconConfiguration("battery three quarters full", AppR.drawable.ic_battery_three_quarters_white, AppR.drawable.ic_battery_three_quarters_black)
    object BATTERY_FULL : LightIconConfiguration("full battery", AppR.drawable.ic_battery_full_white, AppR.drawable.ic_battery_full_black)
    object BATTERY_ALMOST_FULL : LightIconConfiguration("almost full battery", AppR.drawable.ic_battery_almost_full_white, AppR.drawable.ic_battery_almost_full_black)
    object BATTERY_CHARGING : LightIconConfiguration("battery charging", AppR.drawable.ic_battery_charging_white, AppR.drawable.ic_battery_charging_black)
    object BLUETOOTH : LightIconConfiguration("bluetooth", AppR.drawable.ic_bluetooth_white, AppR.drawable.ic_bluetooth_black)
    object CALL : LightIconConfiguration("call", AppR.drawable.ic_call_white, AppR.drawable.ic_call_black)
    object CALL_MISSED : LightIconConfiguration("mute", AppR.drawable.ic_missed_call_white, AppR.drawable.ic_missed_call_black)
    object CAMERA : LightIconConfiguration("camera", AppR.drawable.ic_camera, AppR.drawable.ic_camera)
    object CAMERA_FLASH_ON : LightIconConfiguration("camera flash on", AppR.drawable.ic_camera_flash_on, AppR.drawable.ic_camera_flash_on)
    object CAMERA_FLASH_OFF : LightIconConfiguration("camera flash off", AppR.drawable.ic_camera_flash_off, AppR.drawable.ic_camera_flash_off)
    object CAMERA_FLASH_AUTO : LightIconConfiguration("camera flash auto", AppR.drawable.ic_camera_flash_auto, AppR.drawable.ic_camera_flash_auto)
    object CE_MARK : LightIconConfiguration("ce mark", AppR.drawable.ic_ce_mark_white, AppR.drawable.ic_ce_mark_black)
    object CIRCLE : LightIconConfiguration("circle", AppR.drawable.ic_circle_white, AppR.drawable.ic_circle_black)
    object CLOSE : LightIconConfiguration("close", AppR.drawable.ic_close_white, AppR.drawable.ic_close_black)
    object COMPOSE_MESSAGE : LightIconConfiguration("compose message", AppR.drawable.ic_compose_white, AppR.drawable.ic_compose_black)
    object PENCIL : LightIconConfiguration("pencil", AppR.drawable.ic_pencil_white, AppR.drawable.ic_pencil_black)
    object DELETE : LightIconConfiguration("delete", AppR.drawable.ic_delete_white, AppR.drawable.ic_delete_black)
    object DENY : LightIconConfiguration("deny", AppR.drawable.ic_deny_white, AppR.drawable.ic_deny_black)
    object DIALPAD : LightIconConfiguration("dialpad", AppR.drawable.ic_dialpad_white, AppR.drawable.ic_dialpad_black)
    object DOWN : LightIconConfiguration("down", AppR.drawable.ic_down_white, AppR.drawable.ic_down_black)
    object EMERGENCY : LightIconConfiguration("emergency", AppR.drawable.ic_emergency_white, AppR.drawable.ic_emergency_black)
    object FAST_FORWARD : LightIconConfiguration("fast-forward", AppR.drawable.ic_fast_forward_white, AppR.drawable.ic_fast_forward_black)
    object LIST : LightIconConfiguration("list", AppR.drawable.ic_list_white, AppR.drawable.ic_list_black)
    object LOOP : LightIconConfiguration("loop", AppR.drawable.ic_loop_white, AppR.drawable.ic_loop_black)
    object MEDIA : LightIconConfiguration("media", AppR.drawable.ic_media_white, AppR.drawable.ic_media_black)
    object MICROPHONE : LightIconConfiguration("microphone", AppR.drawable.ic_microphone_white, AppR.drawable.ic_microphone_black)
    object MUTE : LightIconConfiguration("mute", AppR.drawable.ic_mute_white, AppR.drawable.ic_mute_black)
    object PAUSE : LightIconConfiguration("pause", AppR.drawable.ic_pause_white, AppR.drawable.ic_pause_black)
    object PLAY : LightIconConfiguration("play", AppR.drawable.ic_play_white, AppR.drawable.ic_play_black)
    object REWIND : LightIconConfiguration("rewind", AppR.drawable.ic_rewind_white, AppR.drawable.ic_rewind_black)
    object SEARCH : LightIconConfiguration("search", AppR.drawable.ic_search_white, AppR.drawable.ic_search_black)
    object SETTINGS : LightIconConfiguration("settings", AppR.drawable.ic_settings_white, AppR.drawable.ic_settings_black)
    object SHUFFLE : LightIconConfiguration("shuffle", AppR.drawable.ic_shuffle_white, AppR.drawable.ic_shuffle_black)
    object SPEAKER : LightIconConfiguration("speaker", AppR.drawable.ic_speaker_white, AppR.drawable.ic_speaker_black)
    object STAR : LightIconConfiguration("star", AppR.drawable.ic_star_white, AppR.drawable.ic_star_black)
    object STAR_OUTLINE : LightIconConfiguration("star outline", AppR.drawable.ic_star_outline_white, AppR.drawable.ic_star_outline_black)
    object TOGGLE_OFF : LightIconConfiguration("toggle off", AppR.drawable.ic_toggle_off_white, AppR.drawable.ic_toggle_off_black)
    object TOGGLE_ON : LightIconConfiguration("toggle on", AppR.drawable.ic_toggle_on_white, AppR.drawable.ic_toggle_on_black)
    object UP : LightIconConfiguration("up", AppR.drawable.ic_up_white, AppR.drawable.ic_up_black)
    object WIFI : LightIconConfiguration("wifi", AppR.drawable.ic_wifi_white, AppR.drawable.ic_wifi_black)
    object WIFI_NO_INTERNET : LightIconConfiguration("wifi no internet", AppR.drawable.ic_wifi_no_internet_white, AppR.drawable.ic_wifi_no_internet_black)
    object LARGE_LIST : LightIconConfiguration("large list", AppR.drawable.ic_large_list_white, AppR.drawable.ic_large_list_black)
    object DOWNLOADED_ARROW : LightIconConfiguration("downloaded arrow", AppR.drawable.ic_downloaded_arrow_white, AppR.drawable.ic_downloaded_arrow_black)
    object DOWNLOAD_ARROW : LightIconConfiguration("download arrow", AppR.drawable.ic_download_arrow_white, AppR.drawable.ic_download_arrow_black)
    object SKIP_BACKWARD_FIFTEEN : LightIconConfiguration("skip backward fifteen", AppR.drawable.ic_skip_backward_fifteen_white, AppR.drawable.ic_skip_backward_fifteen_black)
    object SKIP_FORWARD_FIFTEEN : LightIconConfiguration("skip forward fifteen", AppR.drawable.ic_skip_forward_fifteen_white, AppR.drawable.ic_skip_forward_fifteen_black)
    object REFRESH : LightIconConfiguration("refresh", AppR.drawable.ic_refresh_white, AppR.drawable.ic_refresh_black)
    object STOP : LightIconConfiguration("stop", AppR.drawable.ic_stop_white, AppR.drawable.ic_stop_black)
    object CONTACTS : LightIconConfiguration("contacts", AppR.drawable.ic_contacts_white, AppR.drawable.ic_contacts_black)
    object ELLIPSES : LightIconConfiguration("ellipses", AppR.drawable.ic_ellipse_white, AppR.drawable.ic_ellipse_black)
    object SPACER : LightIconConfiguration("spacer", AppR.drawable.ic_spacer, AppR.drawable.ic_spacer)
    object TRASH : LightIconConfiguration("trash", AppR.drawable.ic_trash, AppR.drawable.ic_trash)
    object SPEAKER_ON : LightIconConfiguration("speaker on", AppR.drawable.ic_speaker_on, AppR.drawable.ic_speaker_on)
    object SPEAKER_MUTED : LightIconConfiguration("speaker muted", AppR.drawable.ic_speaker_muted, AppR.drawable.ic_speaker_muted)
    object ROTATE : LightIconConfiguration("rotate", AppR.drawable.ic_rotate_white, AppR.drawable.ic_rotate_black)
    object LIGHT_LOGO : LightIconConfiguration("light logo", AppR.drawable.ic_light_logo_white, AppR.drawable.ic_light_logo_black)
    object SEND : LightIconConfiguration("send", AppR.drawable.ic_send_white, AppR.drawable.ic_send_black)
    object SELECT_OFF : LightIconConfiguration("select off", AppR.drawable.ic_select_off_white, AppR.drawable.ic_select_off_black)
    object SELECT_ON : LightIconConfiguration("select on", AppR.drawable.ic_select_on_white, AppR.drawable.ic_select_on_black)
    object FCC_MARK : LightIconConfiguration("fcc mark", AppR.drawable.ic_fcc_mark_white, AppR.drawable.ic_fcc_mark_black)
    object WEEE_MARK : LightIconConfiguration("weee mark", AppR.drawable.ic_weee_mark_white, AppR.drawable.ic_weee_mark_black)
    object VOICE_MAIL : LightIconConfiguration("voicemail", AppR.drawable.ic_voice_mail_white, AppR.drawable.ic_voice_mail_black)
    object VOICE_MEMO : LightIconConfiguration("voicememo", AppR.drawable.ic_voice_memo_white, AppR.drawable.ic_voice_memo_black)
}

@Composable
fun LightIcon(
    icon: LightIconConfiguration,
    modifier: Modifier = Modifier,
    width: Float = 2f,
    height: Float = 2f,
    size: Float? = null,
    tint: Color? = null,
    contentDescription: String? = icon.name,
) {
    val resolvedSize = size ?: width
    val contentColor = tint ?: LightThemeTokens.colors.content
    val drawableId = when (LightThemeTokens.surfaceScheme) {
        LightSurfaceScheme.Dark -> icon.darkModeResource
        LightSurfaceScheme.Light -> icon.lightModeResource
    }
    Icon(
        painter = painterResource(drawableId),
        contentDescription = contentDescription,
        tint = contentColor,
        modifier = modifier.size(resolvedSize.gridUnitsAsDp(), (size ?: height).gridUnitsAsDp()),
    )
}

// ============================================================================
// BAR BUTTONS
// ============================================================================

sealed interface LightBarButton {
    val onClick: (() -> Unit)?
    val contentDescription: String?

    data class Text(
        val text: String,
        override val contentDescription: String? = null,
        override val onClick: (() -> Unit)?,
    ) : LightBarButton

    data class Icon(
        val painter: androidx.compose.ui.graphics.painter.Painter,
        override val onClick: (() -> Unit)?,
        override val contentDescription: String? = null,
        val sizeUnits: Float = LightBarButtonDefaults.ICON_SIZE_UNITS,
        val tint: Color? = null,
    ) : LightBarButton

    data class LightIcon(
        val icon: LightIconConfiguration,
        override val onClick: (() -> Unit)?,
        override val contentDescription: String? = icon.name,
        val sizeUnits: Float = LightBarButtonDefaults.ICON_SIZE_UNITS,
        val tint: Color? = null,
    ) : LightBarButton
}

object LightBarButtonDefaults {
    const val ICON_SIZE_UNITS = 2f
}

typealias LightTopBarButton = LightBarButton
typealias LightBottomBarItem = LightBarButton

internal fun LightBarButton.withoutOnClick(): LightBarButton = when (this) {
    is LightBarButton.Text -> copy(onClick = null)
    is LightBarButton.Icon -> copy(onClick = null)
    is LightBarButton.LightIcon -> copy(onClick = null)
}

@Composable
internal fun LightBarButtonView(
    button: LightBarButton?,
    heightUnits: Float,
    iconSizeUnits: Float = LightBarButtonDefaults.ICON_SIZE_UNITS,
    textVariant: LightTextVariant,
    useSpacerWhenNull: Boolean,
) {
    if (button == null) {
        if (useSpacerWhenNull) LightIcon(icon = LightIcons.SPACER, size = iconSizeUnits, contentDescription = null)
        return
    }
    val barHeight = heightUnits.gridUnitsAsDp()
    when (button) {
        is LightBarButton.Text -> Box(
            modifier = Modifier.height(barHeight).widthIn(min = barHeight)
                .then(if (button.onClick != null) Modifier.lightClickable(onClick = button.onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = button.text, variant = textVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        is LightBarButton.Icon -> {
            val iconSize = button.sizeUnits.gridUnitsAsDp()
            Box(
                modifier = Modifier.size(width = maxOf(iconSize, barHeight), height = barHeight)
                    .then(if (button.onClick != null) Modifier.lightClickable(onClick = button.onClick) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    painter = button.painter, contentDescription = button.contentDescription,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(button.tint ?: LightThemeTokens.colors.content),
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        is LightBarButton.LightIcon -> {
            val iconSize = button.sizeUnits.gridUnitsAsDp()
            Box(
                modifier = Modifier.size(width = maxOf(iconSize, barHeight), height = barHeight)
                    .then(if (button.onClick != null) Modifier.lightClickable(onClick = button.onClick) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                LightIcon(icon = button.icon, size = button.sizeUnits, tint = button.tint, contentDescription = button.contentDescription)
            }
        }
    }
}

// ============================================================================
// TOP BAR
// ============================================================================

sealed interface LightTopBarCenter {
    val onClick: (() -> Unit)?
    data class Text(val text: String, override val onClick: (() -> Unit)? = null) : LightTopBarCenter
    data class TwoLineDetail(val line1: String, val line2: String, override val onClick: (() -> Unit)? = null) : LightTopBarCenter
}

@Composable
fun LightTopBar(
    leftButton: LightTopBarButton? = null,
    center: LightTopBarCenter? = null,
    rightButton: LightTopBarButton? = null,
    modifier: Modifier = Modifier,
) {
    val barHeight = 36.dp
    Box(modifier = modifier.fillMaxWidth().height(barHeight).padding(horizontal = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(barHeight), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.height(barHeight), contentAlignment = Alignment.CenterStart) {
                LightBarButtonView(button = leftButton, heightUnits = 3f, textVariant = LightTextVariant.Fine, useSpacerWhenNull = true)
            }
            Box(modifier = Modifier.weight(1f))
            Box(modifier = Modifier.height(barHeight), contentAlignment = Alignment.CenterEnd) {
                LightBarButtonView(button = rightButton, heightUnits = 3f, textVariant = LightTextVariant.Fine, useSpacerWhenNull = true)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(barHeight), contentAlignment = Alignment.Center) {
            if (center != null) {
                val mod = Modifier.widthIn(max = 120.dp)
                    .let { m -> if (center.onClick != null) m.lightClickable { center.onClick?.invoke() } else m }
                when (center) {
                    is LightTopBarCenter.Text -> LightText(text = center.text, variant = LightTextVariant.Fine, align = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = mod)
                    is LightTopBarCenter.TwoLineDetail -> Column(modifier = mod, horizontalAlignment = Alignment.CenterHorizontally) {
                        LightText(text = center.line1, variant = LightTextVariant.Detail, align = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LightText(text = center.line2, variant = LightTextVariant.Detail, align = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ============================================================================
// BOTTOM BAR
// ============================================================================

@Composable
fun LightBottomBar(items: List<LightBottomBarItem?>, modifier: Modifier = Modifier) {
    val barHeight = 40.dp
    Row(modifier = modifier.fillMaxWidth().padding(top = 2.dp).height(barHeight), verticalAlignment = Alignment.CenterVertically) {
        when (items.size) {
            0 -> Unit
            1 -> { val onClick = items[0]?.onClick; Box(Modifier.fillMaxWidth().fillMaxHeight().then(if (onClick != null) Modifier.lightClickable(onClick = onClick) else Modifier), Alignment.Center) { LightBarButtonView(items[0]?.withoutOnClick(), 3.5f, textVariant = LightTextVariant.Fine, useSpacerWhenNull = true) } }
            2 -> { LightBottomBarSlot(Alignment.CenterStart, items[0]); LightBottomBarSlot(Alignment.CenterEnd, items[1]) }
            3 -> { LightBottomBarSlot(Alignment.CenterStart, items[0]); LightBottomBarSlot(Alignment.Center, items[1]); LightBottomBarSlot(Alignment.CenterEnd, items[2]) }
            else -> items.forEach { LightBottomBarSlot(Alignment.Center, it) }
        }
    }
}

@Composable
private fun RowScope.LightBottomBarSlot(align: Alignment, item: LightBottomBarItem?) {
    val onClick = item?.onClick
    Box(Modifier.weight(1f).fillMaxHeight().then(if (onClick != null) Modifier.lightClickable(onClick = onClick) else Modifier), align) {
        LightBarButtonView(item?.withoutOnClick(), 3.5f, textVariant = LightTextVariant.Fine, useSpacerWhenNull = true)
    }
}

// ============================================================================
// SCROLL VIEWS — no custom scrollbar, just standard scroll
// ============================================================================

enum class LightScrollBarPosition { Outside, Inside }

@Composable
fun LightScrollView(
    modifier: Modifier = Modifier,
    scrollBarPosition: LightScrollBarPosition = LightScrollBarPosition.Outside,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(scrollState), content = content)
}

@Composable
fun LightLazyScrollView(
    modifier: Modifier = Modifier,
    scrollBarPosition: LightScrollBarPosition = LightScrollBarPosition.Outside,
    listState: LazyListState = rememberLazyListState(),
    uniformItemHeightGridUnits: Float,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(state = listState, modifier = modifier.fillMaxSize(), content = content)
}

// ============================================================================
// TEXT FIELD
// ============================================================================

@Composable
fun LightTextField(
    label: String,
    value: String,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    underlineWidthFraction: Float = 0.8f,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LightText(text = label, variant = LightTextVariant.Detail, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        Column(modifier = Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(top = 2.dp)) {
            LightText(text = if (value.isBlank()) placeholder else value, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(2.dp))
            Spacer(modifier = Modifier.fillMaxWidth(underlineWidthFraction).height(2.dp).background(LightThemeTokens.colors.content))
        }
    }
}

@Composable
fun LightTextInputEditor(
    title: String,
    state: TextFieldState,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    submitIcon: LightIconConfiguration = LightIcons.ACCEPT,
    submitLabel: String = "DONE",
    imeAction: ImeAction = ImeAction.Done,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    belowField: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    val typography = LightThemeTokens.typography
    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        LightText(text = title, variant = LightTextVariant.Heading, modifier = Modifier.padding(bottom = 4.dp))
        BasicTextField(
            state = state,
            textStyle = typography.copy.copy(color = colors.content),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.content),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = imeAction,
                capitalization = capitalization,
            ),
            modifier = Modifier.fillMaxWidth(),
            decorator = { innerTextField ->
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth()) {
                        if (state.text.isEmpty()) {
                            LightText(
                                text = title,
                                variant = LightTextVariant.Copy,
                                color = colors.contentSecondary,
                            )
                        }
                        innerTextField()
                    }
                    Spacer(Modifier.fillMaxWidth(0.8f).height(2.dp).background(colors.content))
                }
            },
        )
        belowField?.invoke()
        Spacer(Modifier.weight(1f))
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
                LightBarButton.LightIcon(icon = submitIcon, onClick = { onSubmit(state.text) }),
            ),
        )
    }
}

// ============================================================================
// FULLSCREEN MODAL
// ============================================================================

@Composable
fun LightFullscreenModal(message: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), Alignment.Center) {
            LightText(text = message, variant = LightTextVariant.Copy, align = TextAlign.Center)
        }
        LightBottomBar(items = listOf(LightBarButton.LightIcon(icon = LightIcons.CLOSE, onClick = onClose)))
    }
}

// ============================================================================
// FONT
// ============================================================================

object LightFont

@Composable
fun lightFontFamily(context: android.content.Context): FontFamily = FontFamily.Default

// ============================================================================
// THEME CONTROLLER — no-op on Kyocera, but exposes the same API as the real one
// ============================================================================

object LightThemeController {
    private val _colors = MutableStateFlow(LightThemeColors.Dark)
    val colors: StateFlow<LightColors> = _colors.asStateFlow()

    fun setDarkTheme() {
        _colors.value = LightThemeColors.Dark
    }

    fun setLightTheme() {
        _colors.value = LightThemeColors.Light
    }
}

// ============================================================================
// R — dummy resource reference
// ============================================================================

object R {
    object drawable {
        // All drawable IDs are resolved at compile time from the app's resources.
        // The LightIcons object references R.drawable.ic_* which are now in app/src/main/res/drawable/.
    }
}
