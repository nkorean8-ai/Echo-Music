package echo.music.iad1tya.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import echo.music.iad1tya.MainActivity
import echo.music.iad1tya.R
import echo.music.iad1tya.ui.theme.echomusicTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "DynamicIslandOverlay"
private const val NOTIFICATION_CHANNEL_ID = "dynamic_island_overlay"
private const val NOTIFICATION_ID = 9361
private const val ACTION_OPEN_PLAYER = "echo.music.iad1tya.action.OPEN_DYNAMIC_ISLAND_PLAYER"

private data class DynamicIslandPlaybackState(
  val title: String,
  val artist: String,
  val artworkUri: Uri?,
  val isPlaying: Boolean,
  val positionMs: Long,
  val durationMs: Long,
)

class DynamicIslandOverlayService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val playbackState = mutableStateOf<DynamicIslandPlaybackState?>(null)
  private val collapseSignal = mutableIntStateOf(0)
  private lateinit var windowManager: WindowManager
  private var overlayView: ComposeView? = null
  private var controller: MediaController? = null
  private var controllerFuture: ListenableFuture<MediaController>? = null
  private var positionJob: Job? = null
  private var desiredCenterX = 0
  private var desiredTop = 0
  private var targetWindowWidth = 0
  private var screenReceiverRegistered = false

  private val screenReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
          Intent.ACTION_SCREEN_OFF -> {
            playbackState.value = null
            removeOverlay()
          }
          Intent.ACTION_USER_PRESENT -> refreshPlaybackState()
        }
      }
    }

  private val playerListener =
    object : Player.Listener {
      override fun onEvents(player: Player, events: Player.Events) {
        refreshPlaybackState()
      }

      override fun onIsPlayingChanged(isPlaying: Boolean) {
        refreshPlaybackState()
      }
    }

  override fun onCreate() {
    super.onCreate()
    windowManager = getSystemService(WindowManager::class.java)
    startForegroundOverlayNotification()
    ContextCompat.registerReceiver(
      this,
      screenReceiver,
      IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_USER_PRESENT)
      },
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    screenReceiverRegistered = true
    connectToPlaybackSession()
    positionJob =
      scope.launch {
        while (isActive) {
          if (!Settings.canDrawOverlays(this@DynamicIslandOverlayService)) {
            removeOverlay()
            stopSelf()
            break
          }
          refreshPlaybackState()
          delay(250)
        }
      }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (!Settings.canDrawOverlays(this)) {
      stopSelf(startId)
      return START_NOT_STICKY
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
      removeOverlay()
    } else {
      refreshPlaybackState()
    }
  }

  override fun onDestroy() {
    positionJob?.cancel()
    if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
    controller?.removeListener(playerListener)
    controllerFuture?.cancel(true)
    controller?.release()
    controller = null
    removeOverlay()
    scope.coroutineContext[Job]?.cancel()
    super.onDestroy()
  }

  private fun startForegroundOverlayNotification() {
    val manager = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(
        NotificationChannel(
          NOTIFICATION_CHANNEL_ID,
          getString(R.string.dynamic_island),
          NotificationManager.IMPORTANCE_MIN,
        )
      )
    }
    val notification: Notification =
      NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.echomusicnotification)
        .setContentTitle(getString(R.string.dynamic_island))
        .setContentText(getString(R.string.dynamic_island_notification))
        .setOngoing(true)
        .setSilent(true)
        .build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun connectToPlaybackSession() {
    val token = SessionToken(this, ComponentName(this, MusicService::class.java))
    val future = MediaController.Builder(this, token).buildAsync()
    controllerFuture = future
    future.addListener(
      {
        if (future.isCancelled) return@addListener
        try {
          controller = future.get()
          controller?.addListener(playerListener)
          refreshPlaybackState()
        } catch (error: java.util.concurrent.ExecutionException) {
          Log.e(TAG, "Could not connect to the existing playback session", error.cause ?: error)
          stopSelf()
        } catch (error: InterruptedException) {
          Thread.currentThread().interrupt()
          Log.e(TAG, "Interrupted while connecting to the playback session", error)
          stopSelf()
        }
      },
      ContextCompat.getMainExecutor(this),
    )
  }

  private fun refreshPlaybackState() {
    val player = controller
    val hasActiveMediaItem =
      player?.currentMediaItem != null &&
        player.playbackState != Player.STATE_IDLE &&
        player.playbackState != Player.STATE_ENDED
    val screenIsAvailable =
      getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    if (
      player == null ||
        !hasActiveMediaItem ||
        !Settings.canDrawOverlays(this) ||
        !screenIsAvailable
    ) {
      playbackState.value = null
      removeOverlay()
      return
    }
    if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
      removeOverlay()
      return
    }
    val metadata = player.mediaMetadata
    val duration = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
    playbackState.value =
      DynamicIslandPlaybackState(
        title = metadata.title?.toString()?.takeIf(String::isNotBlank) ?: getString(R.string.dynamic_island_unknown_title),
        artist = metadata.artist?.toString()?.takeIf(String::isNotBlank) ?: getString(R.string.dynamic_island_unknown_artist),
        artworkUri = metadata.artworkUri,
        isPlaying = player.isPlaying,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = duration,
      )
    showOverlay()
  }

  private fun showOverlay() {
    if (!Settings.canDrawOverlays(this)) return
    val view =
      overlayView
        ?: ComposeView(this).also { composeView ->
          overlayView = composeView
          composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
          )
          composeView.setContent {
            echomusicTheme(darkTheme = true, pureBlack = true) {
              val state = playbackState.value
              if (state != null) {
                DynamicIslandPill(
                  state = state,
                  collapseSignal = collapseSignal.intValue,
                  onOpenPlayer = ::openPlayer,
                  onPrevious = { controller?.seekToPrevious() },
                  onNext = { controller?.seekToNext() },
                  onTogglePlayback = {
                    controller?.let { activeController ->
                      if (activeController.isPlaying) activeController.pause()
                      else activeController.play()
                    }
                  },
                )
              }
            }
          }
          ViewCompat.setOnApplyWindowInsetsListener(composeView) { _, insets ->
            val cutout: Rect? = insets.displayCutout?.boundingRects?.firstOrNull()
            val displayMetrics = resources.displayMetrics
            desiredCenterX = cutout?.centerX() ?: displayMetrics.widthPixels / 2
            desiredTop =
              cutout?.top
                ?: insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            updateOverlayPosition(composeView)
            insets
          }
          composeView.addOnLayoutChangeListener { changedView, _, _, _, _, _, _, _, _ ->
            updateOverlayPosition(changedView)
          }
          composeView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
              collapseSignal.intValue += 1
            }
            false
          }
        }
    if (view.isAttachedToWindow) return
    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
          WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        android.graphics.PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = desiredCenterX
        y = desiredTop
        windowAnimations = 0
      }
    try {
      windowManager.addView(view, params)
    } catch (error: WindowManager.BadTokenException) {
      Log.e(TAG, "Overlay window could not be attached", error)
      stopSelf()
    } catch (error: SecurityException) {
      Log.e(TAG, "Overlay permission was revoked before the window was attached", error)
      stopSelf()
    }
  }

  private fun updateOverlayPosition(view: View) {
    if (!view.isAttachedToWindow || view.width == 0) return
    val params = view.layoutParams as? WindowManager.LayoutParams ?: return
    val width = view.width
    val x = (desiredCenterX - width / 2).coerceAtLeast(0)
    if (params.x == x && params.y == desiredTop && targetWindowWidth == width) return
    params.x = x
    params.y = desiredTop
    targetWindowWidth = width
    try {
      windowManager.updateViewLayout(view, params)
    } catch (error: WindowManager.BadTokenException) {
      Log.e(TAG, "Overlay window was detached while updating its position", error)
      removeOverlay()
    } catch (error: SecurityException) {
      Log.e(TAG, "Overlay permission was revoked while updating its position", error)
      removeOverlay()
      stopSelf()
    }
  }

  private fun removeOverlay() {
    val view = overlayView ?: return
    overlayView = null
    try {
      if (view.isAttachedToWindow) windowManager.removeView(view)
    } catch (error: IllegalArgumentException) {
      Log.w(TAG, "Overlay window was already removed", error)
    }
  }

  private fun openPlayer() {
    startActivity(
      Intent(this, MainActivity::class.java)
        .setAction(ACTION_OPEN_PLAYER)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    )
  }
}

@Composable
private fun DynamicIslandPill(
  state: DynamicIslandPlaybackState,
  collapseSignal: Int,
  onOpenPlayer: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onTogglePlayback: () -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  var interactionCount by remember { mutableIntStateOf(0) }
  var dragDistance by remember { mutableFloatStateOf(0f) }
  var stretch by remember { mutableFloatStateOf(0f) }
  val configuration = LocalConfiguration.current
  val haptic = LocalHapticFeedback.current
  val density = LocalDensity.current
  val width by
    animateDpAsState(
      targetValue =
        if (expanded) (configuration.screenWidthDp - 24).coerceAtMost(360).dp else 176.dp,
      animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
      label = "dynamicIslandWidth",
    )
  val height by
    animateDpAsState(
      targetValue = if (expanded) 80.dp else 38.dp,
      animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
      label = "dynamicIslandHeight",
    )
  val scaleX by
    animateFloatAsState(
      targetValue = 1f + kotlin.math.abs(stretch) * 0.045f,
      animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
      label = "dynamicIslandStretch",
    )
  val translationX by
    animateFloatAsState(
      targetValue = stretch * 6f,
      animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
      label = "dynamicIslandTranslation",
    )

  LaunchedEffect(expanded, interactionCount, collapseSignal) {
    if (expanded) {
      delay(4_000)
      expanded = false
    }
  }

  Box(
    modifier =
      Modifier.width(width)
        .height(height)
        .graphicsLayer(scaleX = scaleX, translationX = translationX)
        .clip(RoundedCornerShape(percent = 50))
        .background(Color.Black)
        .pointerInput(expanded) {
          detectTapGestures(
            onTap = {
              interactionCount++
              onOpenPlayer()
            },
            onDoubleTap = {
              interactionCount++
              onTogglePlayback()
            },
            onLongPress = {
              expanded = !expanded
              interactionCount++
            },
          )
        }
        .pointerInput(expanded) {
          detectHorizontalDragGestures(
            onHorizontalDrag = { _, amount ->
              dragDistance += amount
              stretch = (dragDistance / with(density) { 90.dp.toPx() }).coerceIn(-1f, 1f)
            },
            onDragEnd = {
              if (kotlin.math.abs(dragDistance) > with(density) { 48.dp.toPx() }) {
                if (dragDistance > 0f) onNext() else onPrevious()
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                interactionCount++
              }
              dragDistance = 0f
              stretch = 0f
            },
            onDragCancel = {
              dragDistance = 0f
              stretch = 0f
            },
          )
        },
    contentAlignment = Alignment.Center,
  ) {
    AnimatedVisibility(
      visible = !expanded,
      enter = androidx.compose.animation.fadeIn(tween(160)),
      exit = androidx.compose.animation.fadeOut(tween(100)),
    ) {
      Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        AlbumArtwork(uri = state.artworkUri, size = 24.dp)
        Waveform(playing = state.isPlaying, modifier = Modifier.width(34.dp).height(18.dp))
      }
    }
    AnimatedVisibility(
      visible = expanded,
      enter = androidx.compose.animation.fadeIn(tween(180)),
      exit = androidx.compose.animation.fadeOut(tween(100)),
    ) {
      Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Row(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          AlbumArtwork(uri = state.artworkUri, size = 42.dp)
          Column(
            modifier = Modifier.weight(1f).padding(horizontal = 9.dp),
            verticalArrangement = Arrangement.Center,
          ) {
            Text(
              text = state.title,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = Color.White,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = state.artist,
              style = MaterialTheme.typography.labelSmall,
              color = Color.White.copy(alpha = 0.68f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          IconButton(onClick = { onPrevious(); interactionCount++ }, modifier = Modifier.size(34.dp)) {
            Icon(
              imageVector = Icons.Filled.SkipPrevious,
              contentDescription = androidx.compose.ui.res.stringResource(R.string.dynamic_island_previous),
              tint = Color.White,
              modifier = Modifier.size(19.dp),
            )
          }
          IconButton(
            onClick = { onTogglePlayback(); interactionCount++ },
            modifier = Modifier.size(34.dp),
          ) {
            Icon(
              imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
              contentDescription =
                androidx.compose.ui.res.stringResource(
                  if (state.isPlaying) R.string.dynamic_island_pause
                  else R.string.dynamic_island_play
                ),
              tint = Color.White,
              modifier = Modifier.size(21.dp),
            )
          }
          IconButton(onClick = { onNext(); interactionCount++ }, modifier = Modifier.size(34.dp)) {
            Icon(
              imageVector = Icons.Filled.SkipNext,
              contentDescription = androidx.compose.ui.res.stringResource(R.string.dynamic_island_next),
              tint = Color.White,
              modifier = Modifier.size(19.dp),
            )
          }
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(2.dp)) {
          val fraction =
            if (state.durationMs > 0L) {
              (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            } else {
              0f
            }
          drawLine(
            color = Color.White.copy(alpha = 0.28f),
            start = androidx.compose.ui.geometry.Offset.Zero,
            end = androidx.compose.ui.geometry.Offset(size.width, 0f),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
          )
          drawLine(
            color = Color.White,
            start = androidx.compose.ui.geometry.Offset.Zero,
            end = androidx.compose.ui.geometry.Offset(size.width * fraction, 0f),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
          )
        }
      }
    }
  }
}

@Composable
private fun AlbumArtwork(uri: Uri?, size: androidx.compose.ui.unit.Dp) {
  Box(
    modifier =
      Modifier.size(size)
        .clip(RoundedCornerShape(6.dp))
        .background(Color(0xFF242424)),
    contentAlignment = Alignment.Center,
  ) {
    if (uri != null) {
      AsyncImage(
        model = uri,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Text(
        text = "♪",
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelSmall,
      )
    }
  }
}

@Composable
private fun Waveform(playing: Boolean, modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "dynamicIslandWaveform")
  val heights =
    if (playing) {
      List(5) { index ->
        transition.animateFloat(
          initialValue = 0.3f,
          targetValue = 1f,
          animationSpec =
            infiniteRepeatable(
              animation = tween(durationMillis = 360 + index * 75, delayMillis = index * 65),
              repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
          label = "dynamicIslandWave$index",
        ).value
      }
    } else {
      listOf(0.35f, 0.65f, 0.45f, 0.75f, 0.4f)
    }
  Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
    heights.forEach { level ->
      Box(
        modifier =
          Modifier.weight(1f)
            .fillMaxWidth()
            .height((15f * level).dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White)
      )
    }
  }
}
