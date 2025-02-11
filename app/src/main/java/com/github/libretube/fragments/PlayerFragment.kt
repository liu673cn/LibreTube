package com.github.libretube.fragments

import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.Html
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.libretube.Globals
import com.github.libretube.R
import com.github.libretube.activities.MainActivity
import com.github.libretube.adapters.ChaptersAdapter
import com.github.libretube.adapters.CommentsAdapter
import com.github.libretube.adapters.TrendingAdapter
import com.github.libretube.databinding.DoubleTapOverlayBinding
import com.github.libretube.databinding.ExoStyledPlayerControlViewBinding
import com.github.libretube.databinding.FragmentPlayerBinding
import com.github.libretube.dialogs.AddToPlaylistDialog
import com.github.libretube.dialogs.DownloadDialog
import com.github.libretube.dialogs.ShareDialog
import com.github.libretube.extensions.BaseFragment
import com.github.libretube.obj.ChapterSegment
import com.github.libretube.obj.Segment
import com.github.libretube.obj.Segments
import com.github.libretube.obj.Streams
import com.github.libretube.preferences.PreferenceHelper
import com.github.libretube.preferences.PreferenceKeys
import com.github.libretube.services.BackgroundMode
import com.github.libretube.util.AutoPlayHelper
import com.github.libretube.util.BackgroundHelper
import com.github.libretube.util.ConnectionHelper
import com.github.libretube.util.CronetHelper
import com.github.libretube.util.NowPlayingNotification
import com.github.libretube.util.OnDoubleTapEventListener
import com.github.libretube.util.PlayerHelper
import com.github.libretube.util.RetrofitInstance
import com.github.libretube.util.SubscriptionHelper
import com.github.libretube.util.formatShort
import com.github.libretube.util.hideKeyboard
import com.github.libretube.util.toID
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaItem.SubtitleConfiguration
import com.google.android.exoplayer2.MediaItem.fromUri
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.cronet.CronetDataSource
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.CaptionStyleCompat
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.ui.TimeBar
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.util.RepeatModeUtil
import com.google.android.exoplayer2.video.VideoSize
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.chromium.net.CronetEngine
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.abs

class PlayerFragment : BaseFragment() {

    private val TAG = "PlayerFragment"
    private lateinit var binding: FragmentPlayerBinding
    private lateinit var playerBinding: ExoStyledPlayerControlViewBinding
    private lateinit var doubleTapOverlayBinding: DoubleTapOverlayBinding

    /**
     * video information
     */
    private var videoId: String? = null
    private var playlistId: String? = null
    private var isSubscribed: Boolean? = false
    private var isLive = false
    private lateinit var streams: Streams

    /**
     * for the transition
     */
    private var sId: Int = 0
    private var eId: Int = 0
    private var transitioning = false

    /**
     * for the comments
     */
    private var commentsAdapter: CommentsAdapter? = null
    private var commentsLoaded: Boolean? = false
    private var nextPage: String? = null
    private var isLoading = true

    /**
     * for the player
     */
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var segmentData: Segments
    private lateinit var chapters: List<ChapterSegment>

    /**
     * for the player view
     */
    private lateinit var exoPlayerView: StyledPlayerView
    private var isPlayerLocked: Boolean = false
    private var subtitle = mutableListOf<SubtitleConfiguration>()

    /**
     * user preferences
     */
    private var token = ""
    private var relatedStreamsEnabled = true
    private var autoplayEnabled = false
    private var autoRotationEnabled = true
    private var playbackSpeed = "1F"
    private var pausePlayerOnScreenOffEnabled = false
    private var fullscreenOrientationPref = "ratio"
    private var watchHistoryEnabled = true
    private var watchPositionsEnabled = true
    private var useSystemCaptionStyle = true
    private var seekIncrement = 5L
    private var videoFormatPreference = "webm"
    private var defRes = ""
    private var bufferingGoal = 50000
    private var seekBarPreview = false
    private var defaultSubtitleCode = ""
    private var sponsorBlockEnabled = true
    private var sponsorBlockNotifications = true

    /**
     * for autoplay
     */
    private var nextStreamId: String? = null
    private lateinit var autoPlayHelper: AutoPlayHelper

    /**
     * for the player notification
     */
    private lateinit var nowPlayingNotification: NowPlayingNotification

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            videoId = it.getString("videoId").toID()
            playlistId = it.getString("playlistId")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentPlayerBinding.inflate(layoutInflater, container, false)
        exoPlayerView = binding.player
        playerBinding = binding.player.binding
        doubleTapOverlayBinding = binding.doubleTapOverlay.binding

        // Inflate the layout for this fragment
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        context?.hideKeyboard(view)

        setUserPrefs()

        if (autoplayEnabled == true) playerBinding.autoplayIV.setImageResource(R.drawable.ic_toggle_on)

        val mainActivity = activity as MainActivity
        if (autoRotationEnabled) {
            // enable auto rotation
            mainActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            onConfigurationChanged(resources.configuration)
        } else {
            // go to portrait mode
            mainActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }

        createExoPlayer()
        initializeTransitionLayout()
        initializeOnClickActions()
        playVideo()
    }

    private fun setUserPrefs() {
        token = PreferenceHelper.getToken()

        // save whether auto rotation is enabled
        autoRotationEnabled = PreferenceHelper.getBoolean(
            PreferenceKeys.AUTO_FULLSCREEN,
            false
        )

        // save whether related streams and autoplay are enabled
        autoplayEnabled = PreferenceHelper.getBoolean(
            PreferenceKeys.AUTO_PLAY,
            false
        )
        relatedStreamsEnabled = PreferenceHelper.getBoolean(
            PreferenceKeys.RELATED_STREAMS,
            true
        )

        playbackSpeed = PreferenceHelper.getString(
            PreferenceKeys.PLAYBACK_SPEED,
            "1"
        ).replace("F", "") // due to old way to handle it (with float)

        fullscreenOrientationPref = PreferenceHelper.getString(
            PreferenceKeys.FULLSCREEN_ORIENTATION,
            "ratio"
        )

        pausePlayerOnScreenOffEnabled = PreferenceHelper.getBoolean(
            PreferenceKeys.PAUSE_ON_SCREEN_OFF,
            false
        )

        watchPositionsEnabled = PreferenceHelper.getBoolean(
            PreferenceKeys.WATCH_POSITION_TOGGLE,
            true
        )

        watchHistoryEnabled = PreferenceHelper.getBoolean(
            PreferenceKeys.WATCH_HISTORY_TOGGLE,
            true
        )

        useSystemCaptionStyle = PreferenceHelper.getBoolean(
            PreferenceKeys.SYSTEM_CAPTION_STYLE,
            true
        )

        seekIncrement = PreferenceHelper.getString(
            PreferenceKeys.SEEK_INCREMENT,
            "5"
        ).toLong() * 1000

        videoFormatPreference = PreferenceHelper.getString(
            PreferenceKeys.PLAYER_VIDEO_FORMAT,
            "webm"
        )

        defRes = PreferenceHelper.getString(
            PreferenceKeys.DEFAULT_RESOLUTION,
            ""
        )

        bufferingGoal = PreferenceHelper.getString(
            PreferenceKeys.BUFFERING_GOAL,
            "50"
        ).toInt() * 1000

        seekBarPreview = PreferenceHelper.getBoolean(
            PreferenceKeys.SEEKBAR_PREVIEW,
            false
        )

        sponsorBlockEnabled = PreferenceHelper.getBoolean(
            "sb_enabled_key",
            true
        )

        sponsorBlockNotifications = PreferenceHelper.getBoolean(
            "sb_notifications_key",
            true
        )

        defaultSubtitleCode = PreferenceHelper.getString(
            PreferenceKeys.DEFAULT_SUBTITLE,
            ""
        )

        if (defaultSubtitleCode.contains("-")) {
            defaultSubtitleCode = defaultSubtitleCode.split("-")[0]
        }
    }

    private fun initializeTransitionLayout() {
        val mainActivity = activity as MainActivity
        mainActivity.binding.container.visibility = View.VISIBLE

        binding.playerMotionLayout.addTransitionListener(object : MotionLayout.TransitionListener {
            override fun onTransitionStarted(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int
            ) {
            }

            override fun onTransitionChange(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int,
                progress: Float
            ) {
                val mainActivity = activity as MainActivity
                val mainMotionLayout =
                    mainActivity.binding.mainMotionLayout
                mainMotionLayout.progress = abs(progress)
                exoPlayerView.hideController()
                eId = endId
                sId = startId
            }

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                println(currentId)
                val mainActivity = activity as MainActivity
                val mainMotionLayout =
                    mainActivity.binding.mainMotionLayout
                if (currentId == eId) {
                    Globals.MINI_PLAYER_VISIBLE = true
                    exoPlayerView.useController = false
                    mainMotionLayout.progress = 1F
                } else if (currentId == sId) {
                    Globals.MINI_PLAYER_VISIBLE = false
                    exoPlayerView.useController = true
                    mainMotionLayout.progress = 0F
                }
            }

            override fun onTransitionTrigger(
                MotionLayout: MotionLayout?,
                triggerId: Int,
                positive: Boolean,
                progress: Float
            ) {
            }
        })

        binding.playerMotionLayout.progress = 1.toFloat()
        binding.playerMotionLayout.transitionToStart()

        // quitting miniPlayer on single click
        binding.titleTextView.setOnTouchListener { view, motionEvent ->
            view.onTouchEvent(motionEvent)
            if (motionEvent.action == MotionEvent.ACTION_UP) view.performClick()
            binding.root.onTouchEvent(motionEvent)
        }
        binding.titleTextView.setOnClickListener {
            binding.playerMotionLayout.setTransitionDuration(300)
            binding.playerMotionLayout.transitionToStart()
        }
    }

    // actions that don't depend on video information
    private fun initializeOnClickActions() {
        binding.closeImageView.setOnClickListener {
            Globals.MINI_PLAYER_VISIBLE = false
            binding.playerMotionLayout.transitionToEnd()
            val mainActivity = activity as MainActivity
            mainActivity.supportFragmentManager.beginTransaction()
                .remove(this)
                .commit()
        }
        playerBinding.closeImageButton.setOnClickListener {
            Globals.MINI_PLAYER_VISIBLE = false
            binding.playerMotionLayout.transitionToEnd()
            val mainActivity = activity as MainActivity
            mainActivity.supportFragmentManager.beginTransaction()
                .remove(this)
                .commit()
        }
        // show the advanced player options
        playerBinding.toggleOptions.setOnClickListener {
            if (playerBinding.advancedOptions.isVisible) {
                playerBinding.toggleOptions.animate().rotation(0F).setDuration(250).start()
                playerBinding.advancedOptions.visibility = View.GONE
            } else {
                playerBinding.toggleOptions.animate().rotation(180F).setDuration(250).start()
                playerBinding.advancedOptions.visibility = View.VISIBLE
            }
        }
        // autoplay toggle button
        playerBinding.autoplayLL.setOnClickListener {
            autoplayEnabled = if (autoplayEnabled) {
                playerBinding.autoplayIV.setImageResource(R.drawable.ic_toggle_off)
                false
            } else {
                playerBinding.autoplayIV.setImageResource(R.drawable.ic_toggle_on)
                true
            }
        }
        binding.playImageView.setOnClickListener {
            if (!exoPlayer.isPlaying) {
                // start or go on playing
                binding.playImageView.setImageResource(R.drawable.ic_pause)
                exoPlayer.play()
            } else {
                // pause the video
                binding.playImageView.setImageResource(R.drawable.ic_play)
                exoPlayer.pause()
            }
        }

        // video description and chapters toggle
        binding.playerTitleLayout.setOnClickListener {
            toggleDescription()
        }

        binding.commentsToggle.setOnClickListener {
            toggleComments()
        }

        // FullScreen button trigger
        // hide fullscreen button if auto rotation enabled
        playerBinding.fullscreen.visibility = if (autoRotationEnabled) View.GONE else View.VISIBLE
        playerBinding.fullscreen.setOnClickListener {
            // hide player controller
            exoPlayerView.hideController()
            if (!Globals.IS_FULL_SCREEN) {
                // go to fullscreen mode
                setFullscreen()
            } else {
                // exit fullscreen mode
                unsetFullscreen()
            }
        }

        // switching between original aspect ratio (black bars) and zoomed to fill device screen
        val aspectRatioModes = arrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
        playerBinding.aspectRatioButton.setOnClickListener {
            val index = aspectRatioModes.indexOf(exoPlayerView.resizeMode)
            val newAspectRatioMode =
                if (index + 1 < aspectRatioModes.size) aspectRatioModes[index + 1]
                else aspectRatioModes[0]
            exoPlayerView.resizeMode = newAspectRatioMode
        }

        // lock and unlock the player
        playerBinding.lockPlayer.setOnClickListener {
            // change the locked/unlocked icon
            if (!isPlayerLocked) {
                playerBinding.lockPlayer.setImageResource(R.drawable.ic_locked)
            } else {
                playerBinding.lockPlayer.setImageResource(R.drawable.ic_unlocked)
            }

            // show/hide all the controls
            lockPlayer(isPlayerLocked)

            // change locked status
            isPlayerLocked = !isPlayerLocked
        }

        // set default playback speed
        val playbackSpeeds = context?.resources?.getStringArray(R.array.playbackSpeed)!!
        val playbackSpeedValues =
            context?.resources?.getStringArray(R.array.playbackSpeedValues)!!
        exoPlayer.setPlaybackSpeed(playbackSpeed.toFloat())
        val speedIndex = playbackSpeedValues.indexOf(playbackSpeed)
        playerBinding.speedText.text = playbackSpeeds[speedIndex]

        // change playback speed button
        playerBinding.speedText.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.change_playback_speed)
                .setItems(playbackSpeeds) { _, index ->
                    // set the new playback speed
                    val newPlaybackSpeed = playbackSpeedValues[index].toFloat()
                    exoPlayer.setPlaybackSpeed(newPlaybackSpeed)
                    playerBinding.speedText.text = playbackSpeeds[index]
                }
                .show()
        }

        // repeat toggle button
        playerBinding.repeatToggle.setOnClickListener {
            if (exoPlayer.repeatMode == RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL) {
                // turn off repeat mode
                exoPlayer.repeatMode = RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE
                playerBinding.repeatToggle.setColorFilter(Color.GRAY)
            } else {
                exoPlayer.repeatMode = RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL
                playerBinding.repeatToggle.setColorFilter(Color.WHITE)
            }
        }

        // share button
        binding.relPlayerShare.setOnClickListener {
            val shareDialog = ShareDialog(videoId!!, false, exoPlayer.currentPosition)
            shareDialog.show(childFragmentManager, ShareDialog::class.java.name)
        }

        binding.relPlayerBackground.setOnClickListener {
            // pause the current player
            exoPlayer.pause()

            // start the background mode
            BackgroundHelper.playOnBackground(
                requireContext(),
                videoId!!,
                exoPlayer.currentPosition
            )
        }

        binding.playerScrollView.viewTreeObserver
            .addOnScrollChangedListener {
                if (binding.playerScrollView.getChildAt(0).bottom
                    == (binding.playerScrollView.height + binding.playerScrollView.scrollY) &&
                    nextPage != null
                ) {
                    fetchNextComments()
                }
            }

        binding.commentsRecView.layoutManager = LinearLayoutManager(view?.context)
        binding.commentsRecView.setItemViewCacheSize(20)

        binding.relatedRecView.layoutManager =
            GridLayoutManager(view?.context, resources.getInteger(R.integer.grid_items))
    }

    private fun setFullscreen() {
        with(binding.playerMotionLayout) {
            getConstraintSet(R.id.start).constrainHeight(R.id.player, -1)
            enableTransition(R.id.yt_transition, false)
        }

        binding.mainContainer.isClickable = true
        binding.linLayout.visibility = View.GONE
        playerBinding.fullscreen.setImageResource(R.drawable.ic_fullscreen_exit)
        playerBinding.exoTitle.visibility = View.VISIBLE

        scaleControls(1.3F)

        val mainActivity = activity as MainActivity
        if (!autoRotationEnabled) {
            // different orientations of the video are only available when auto rotation is disabled
            val orientation = when (fullscreenOrientationPref) {
                "ratio" -> {
                    val videoSize = exoPlayer.videoSize
                    // probably a youtube shorts video
                    if (videoSize.height > videoSize.width) ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                    // a video with normal aspect ratio
                    else ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                }
                "auto" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            }
            mainActivity.requestedOrientation = orientation
        }

        Globals.IS_FULL_SCREEN = true
    }

    private fun unsetFullscreen() {
        // leave fullscreen mode
        with(binding.playerMotionLayout) {
            getConstraintSet(R.id.start).constrainHeight(R.id.player, 0)
            enableTransition(R.id.yt_transition, true)
        }

        binding.mainContainer.isClickable = false
        binding.linLayout.visibility = View.VISIBLE
        playerBinding.fullscreen.setImageResource(R.drawable.ic_fullscreen)
        playerBinding.exoTitle.visibility = View.INVISIBLE

        scaleControls(1F)

        if (!autoRotationEnabled) {
            // switch back to portrait mode if auto rotation disabled
            val mainActivity = activity as MainActivity
            mainActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }

        Globals.IS_FULL_SCREEN = false
    }

    private fun scaleControls(scaleFactor: Float) {
        playerBinding.exoPlayPause.scaleX = scaleFactor
        playerBinding.exoPlayPause.scaleY = scaleFactor
    }

    private fun toggleDescription() {
        if (binding.descLinLayout.isVisible) {
            // hide the description and chapters
            binding.playerDescriptionArrow.animate().rotation(0F).setDuration(250).start()
            binding.descLinLayout.visibility = View.GONE
        } else {
            // show the description and chapters
            binding.playerDescriptionArrow.animate().rotation(180F).setDuration(250).start()
            binding.descLinLayout.visibility = View.VISIBLE
        }
        if (this::chapters.isInitialized && chapters.isNotEmpty()) {
            val chapterIndex = getCurrentChapterIndex()
            // scroll to the current chapter in the chapterRecView in the description
            val layoutManager = binding.chaptersRecView.layoutManager as LinearLayoutManager
            layoutManager.scrollToPositionWithOffset(chapterIndex, 0)
            // set selected
            val chaptersAdapter = binding.chaptersRecView.adapter as ChaptersAdapter
            chaptersAdapter.updateSelectedPosition(chapterIndex)
        }
    }

    private fun toggleComments() {
        binding.commentsRecView.visibility =
            if (binding.commentsRecView.isVisible) View.GONE else View.VISIBLE
        binding.relatedRecView.visibility =
            if (binding.relatedRecView.isVisible) View.GONE else View.VISIBLE
        if (!commentsLoaded!!) fetchComments()
    }

    override fun onPause() {
        // pauses the player if the screen is turned off

        // check whether the screen is on
        val pm = context?.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOn = pm.isInteractive

        // pause player if screen off and setting enabled
        if (
            this::exoPlayer.isInitialized && !isScreenOn && pausePlayerOnScreenOffEnabled
        ) {
            exoPlayer.pause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            saveWatchPosition()
            nowPlayingNotification.destroy()
            activity?.requestedOrientation =
                if ((activity as MainActivity).autoRotationEnabled) ActivityInfo.SCREEN_ORIENTATION_USER
                else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        } catch (e: Exception) {
        }
    }

    // save the watch position if video isn't finished and option enabled
    private fun saveWatchPosition() {
        if (watchPositionsEnabled && exoPlayer.currentPosition != exoPlayer.duration) {
            PreferenceHelper.saveWatchPosition(
                videoId!!,
                exoPlayer.currentPosition
            )
        } else if (watchPositionsEnabled) {
            // delete watch position if video has ended
            PreferenceHelper.removeWatchPosition(videoId!!)
        }
    }

    private fun checkForSegments() {
        if (!exoPlayer.isPlaying || !sponsorBlockEnabled) return

        Handler(Looper.getMainLooper()).postDelayed(this::checkForSegments, 100)

        if (!::segmentData.isInitialized || segmentData.segments.isEmpty()) {
            return
        }

        segmentData.segments.forEach { segment: Segment ->
            val segmentStart = (segment.segment!![0] * 1000f).toLong()
            val segmentEnd = (segment.segment[1] * 1000f).toLong()
            val currentPosition = exoPlayer.currentPosition
            if (currentPosition in segmentStart until segmentEnd) {
                if (sponsorBlockNotifications) {
                    Toast.makeText(context, R.string.segment_skipped, Toast.LENGTH_SHORT).show()
                }
                exoPlayer.seekTo(segmentEnd)
            }
        }
    }

    private fun playVideo() {
        fun run() {
            lifecycleScope.launchWhenCreated {
                streams = try {
                    RetrofitInstance.api.getStreams(videoId!!)
                } catch (e: IOException) {
                    println(e)
                    Log.e(TAG, "IOException, you might not have internet connection")
                    Toast.makeText(context, R.string.unknown_error, Toast.LENGTH_SHORT).show()
                    return@launchWhenCreated
                } catch (e: HttpException) {
                    Log.e(TAG, "HttpException, unexpected response")
                    Toast.makeText(context, R.string.server_error, Toast.LENGTH_SHORT).show()
                    return@launchWhenCreated
                }

                runOnUiThread {
                    // set media sources for the player
                    setResolutionAndSubtitles(streams)
                    prepareExoPlayerView()
                    initializePlayerView(streams)
                    if (!isLive) seekToWatchPosition()
                    exoPlayer.prepare()
                    exoPlayer.play()
                    exoPlayerView.useController = true
                    initializePlayerNotification()
                    if (sponsorBlockEnabled) fetchSponsorBlockSegments()
                    // show comments if related streams disabled
                    if (!relatedStreamsEnabled) toggleComments()
                    // prepare for autoplay
                    if (autoplayEnabled) setNextStream()
                    if (watchHistoryEnabled) {
                        PreferenceHelper.addToWatchHistory(videoId!!, streams)
                    }
                }
            }
        }
        run()
    }

    /**
     * set the videoId of the next stream for autoplay
     */
    private fun setNextStream() {
        nextStreamId = streams.relatedStreams!![0].url.toID()
        if (playlistId == null) return
        if (!this::autoPlayHelper.isInitialized) autoPlayHelper = AutoPlayHelper(playlistId!!)
        // search for the next videoId in the playlist
        lifecycleScope.launchWhenCreated {
            val nextId = autoPlayHelper.getNextPlaylistVideoId(videoId!!)
            if (nextId != null) nextStreamId = nextId
        }
    }

    /**
     * fetch the segments for SponsorBlock
     */
    private fun fetchSponsorBlockSegments() {
        CoroutineScope(Dispatchers.IO).launch {
            kotlin.runCatching {
                val categories = PlayerHelper.getSponsorBlockCategories()
                if (categories.size > 0) {
                    segmentData =
                        RetrofitInstance.api.getSegments(
                            videoId!!,
                            ObjectMapper().writeValueAsString(categories)
                        )
                }
            }
        }
    }

    private fun refreshLiveStatus() {
        // switch back to normal speed when on the end of live stream
        if (exoPlayer.duration - exoPlayer.currentPosition < 7000) {
            exoPlayer.setPlaybackSpeed(1F)
            playerBinding.speedText.text = "1x"
            playerBinding.liveSeparator.visibility = View.GONE
            playerBinding.liveDiff.text = ""
        } else {
            Log.e(TAG, "changing the time")
            // live stream but not watching at the end/live position
            playerBinding.liveSeparator.visibility = View.VISIBLE
            val diffText = DateUtils.formatElapsedTime(
                (exoPlayer.duration - exoPlayer.currentPosition) / 1000
            )
            playerBinding.liveDiff.text = "-$diffText"
        }
        // call it again
        Handler(Looper.getMainLooper())
            .postDelayed(this@PlayerFragment::refreshLiveStatus, 100)
    }

    private fun seekToWatchPosition() {
        // seek to saved watch position if available
        val watchPositions = PreferenceHelper.getWatchPositions()
        var position: Long? = null
        watchPositions.forEach {
            if (it.videoId == videoId &&
                // don't seek to the position if it's the end, autoplay would skip it immediately
                streams.duration!! - it.position / 1000 > 2
            ) position = it.position
        }
        // support for time stamped links
        val timeStamp: Long? = arguments?.getLong("timeStamp")
        if (timeStamp != null && timeStamp != 0L) {
            position = timeStamp * 1000
        }
        if (position != null) exoPlayer.seekTo(position!!)
    }

    // used for autoplay and skipping to next video
    private fun playNextVideo() {
        // check whether there is a new video in the queue
        // by making sure that the next and the current video aren't the same
        saveWatchPosition()
        // forces the comments to reload for the new video
        commentsLoaded = false
        binding.commentsRecView.adapter = null
        if (videoId != nextStreamId) {
            // save the id of the next stream as videoId and load the next video
            videoId = nextStreamId
            playVideo()
        }
    }

    private fun prepareExoPlayerView() {
        exoPlayerView.apply {
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            // controllerShowTimeoutMs = 1500
            controllerHideOnTouch = true
            useController = false
            player = exoPlayer
        }

        if (useSystemCaptionStyle) {
            // set the subtitle style
            val captionStyle = PlayerHelper.getCaptionStyle(requireContext())
            exoPlayerView.subtitleView?.setApplyEmbeddedStyles(captionStyle == CaptionStyleCompat.DEFAULT)
            exoPlayerView.subtitleView?.setStyle(captionStyle)
        }
    }

    private fun handleLiveVideo() {
        playerBinding.exoTime.visibility = View.GONE
        playerBinding.liveLL.visibility = View.VISIBLE
        playerBinding.liveIndicator.setOnClickListener {
            exoPlayer.seekTo(exoPlayer.duration - 1000)
        }
        refreshLiveStatus()
    }

    private fun initializePlayerView(response: Streams) {
        binding.apply {
            playerViewsInfo.text =
                context?.getString(R.string.views, response.views.formatShort()) +
                if (!isLive) " • " + response.uploadDate else ""

            textLike.text = response.likes.formatShort()
            textDislike.text = response.dislikes.formatShort()
            ConnectionHelper.loadImage(response.uploaderAvatar, binding.playerChannelImage)
            playerChannelName.text = response.uploader

            titleTextView.text = response.title

            playerTitle.text = response.title
            playerDescription.text = response.description
        }

        // duration that's not greater than 0 indicates that the video is live
        if (!(response.duration!! > 0)) {
            isLive = true
            handleLiveVideo()
        }

        playerBinding.exoTitle.text = response.title

        if (seekBarPreview) enableSeekbarPreview()
        enableDoubleTapToSeek()

        // init the chapters recyclerview
        if (response.chapters != null) {
            chapters = response.chapters
            initializeChapters()
        }

        // Listener for play and pause icon change
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && sponsorBlockEnabled) {
                    Handler(Looper.getMainLooper()).postDelayed(
                        this@PlayerFragment::checkForSegments,
                        100
                    )
                }
            }

            override fun onVideoSizeChanged(
                videoSize: VideoSize
            ) {
                // show the resolution in the video resolution text view
                if (playerBinding.qualityText.text == context?.getString(R.string.hls)) {
                    playerBinding.qualityText.text =
                        "${context?.getString(R.string.hls)} (${videoSize.height}p)"
                }

                // Set new width/height of view
                // height or width must be cast to float as int/int will give 0

                // Redraw the player container with the new layout height
                val params = binding.player.layoutParams
                params.height = videoSize.height / videoSize.width * params.width
                binding.player.layoutParams = params
                binding.player.requestLayout()
                (binding.mainContainer.layoutParams as ConstraintLayout.LayoutParams).apply {
                    matchConstraintPercentHeight = (videoSize.height / videoSize.width).toFloat()
                }
            }

            @Deprecated(message = "Deprecated", level = DeprecationLevel.HIDDEN)
            override fun onPlayerStateChanged(
                playWhenReady: Boolean,
                playbackState: Int
            ) {
                exoPlayerView.keepScreenOn = !(
                    playbackState == Player.STATE_IDLE ||
                        playbackState == Player.STATE_ENDED ||
                        !playWhenReady
                    )

                // check if video has ended, next video is available and autoplay is enabled.
                if (
                    playbackState == Player.STATE_ENDED &&
                    nextStreamId != null &&
                    !transitioning &&
                    autoplayEnabled
                ) {
                    transitioning = true
                    // check whether autoplay is enabled
                    if (autoplayEnabled) playNextVideo()
                }

                if (playWhenReady && playbackState == Player.STATE_READY) {
                    // media actually playing
                    transitioning = false
                    binding.playImageView.setImageResource(R.drawable.ic_pause)
                } else if (playWhenReady) {
                    // might be idle (plays after prepare()),
                    // buffering (plays when data available)
                    // or ended (plays when seek away from end)
                    binding.playImageView.setImageResource(R.drawable.ic_play)
                } else {
                    // player paused in any state
                    binding.playImageView.setImageResource(R.drawable.ic_play)
                }
            }
        })

        // check if livestream
        if (response.duration > 0) {
            // download clicked
            binding.relPlayerDownload.setOnClickListener {
                if (!Globals.IS_DOWNLOAD_RUNNING) {
                    val newFragment = DownloadDialog()
                    val bundle = Bundle()
                    bundle.putString("video_id", videoId)
                    newFragment.arguments = bundle
                    newFragment.show(childFragmentManager, DownloadDialog::class.java.name)
                } else {
                    Toast.makeText(context, R.string.dlisinprogress, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        } else {
            Toast.makeText(context, R.string.cannotDownload, Toast.LENGTH_SHORT).show()
        }

        if (response.hls != null) {
            binding.relPlayerOpen.setOnClickListener {
                // start an intent with video as mimetype using the hls stream
                val uri: Uri = Uri.parse(response.hls)
                val intent = Intent()

                intent.action = Intent.ACTION_VIEW
                intent.setDataAndType(uri, "video/*")
                intent.putExtra(Intent.EXTRA_TITLE, streams.title)
                intent.putExtra("title", streams.title)
                intent.putExtra("artist", streams.uploader)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, R.string.no_player_found, Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (relatedStreamsEnabled) {
            // only show related streams if enabled
            binding.relatedRecView.adapter = TrendingAdapter(
                response.relatedStreams!!,
                childFragmentManager
            )
        }
        // set video description
        val description = response.description!!
        binding.playerDescription.text =
            // detect whether the description is html formatted
            if (description.contains("<") && description.contains(">")) {
                if (SDK_INT >= Build.VERSION_CODES.N) {
                    Html.fromHtml(description, Html.FROM_HTML_MODE_COMPACT)
                        .trim()
                } else {
                    Html.fromHtml(description).trim()
                }
            } else {
                description
            }

        binding.playerChannel.setOnClickListener {
            val activity = view?.context as MainActivity
            val bundle = bundleOf("channel_id" to response.uploaderUrl)
            activity.navController.navigate(R.id.channelFragment, bundle)
            activity.binding.mainMotionLayout.transitionToEnd()
            binding.playerMotionLayout.transitionToEnd()
        }
        if (token != "") {
            isSubscribed()
            binding.relPlayerSave.setOnClickListener {
                val newFragment = AddToPlaylistDialog()
                val bundle = Bundle()
                bundle.putString("videoId", videoId)
                newFragment.arguments = bundle
                newFragment.show(childFragmentManager, AddToPlaylistDialog::class.java.name)
            }
        } else {
            binding.relPlayerSave.setOnClickListener {
                Toast.makeText(context, R.string.login_first, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enableDoubleTapToSeek() {
        // set seek increment text
        val seekIncrementText = (seekIncrement / 1000).toString()
        doubleTapOverlayBinding.rewindTV.text = seekIncrementText
        doubleTapOverlayBinding.forwardTV.text = seekIncrementText
        binding.player.setOnDoubleTapListener(
            object : OnDoubleTapEventListener {
                override fun onEvent(x: Float) {
                    val width = exoPlayerView.width
                    when {
                        width * 0.5 > x -> rewind()
                        width * 0.5 < x -> forward()
                    }
                }
            }
        )
    }

    private fun rewind() {
        exoPlayer.seekTo(exoPlayer.currentPosition - seekIncrement)

        // show the rewind button
        doubleTapOverlayBinding.rewindBTN.apply {
            visibility = View.VISIBLE
            // clear previous animation
            animate().rotation(0F).setDuration(0).start()
            // start new animation
            animate()
                .rotation(-30F)
                .setDuration(100)
                .withEndAction {
                    // reset the animation when finished
                    animate().rotation(0F).setDuration(100).start()
                }
                .start()

            removeCallbacks(hideRewindButtonRunnable)
            // start callback to hide the button
            postDelayed(hideRewindButtonRunnable, 700)
        }
    }

    private fun forward() {
        exoPlayer.seekTo(exoPlayer.currentPosition + seekIncrement)

        // show the forward button
        doubleTapOverlayBinding.forwardBTN.apply {
            visibility = View.VISIBLE
            // clear previous animation
            animate().rotation(0F).setDuration(0).start()
            // start new animation
            animate()
                .rotation(30F)
                .setDuration(100)
                .withEndAction {
                    // reset the animation when finished
                    animate().rotation(0F).setDuration(100).start()
                }
                .start()

            // start callback to hide the button
            removeCallbacks(hideForwardButtonRunnable)
            postDelayed(hideForwardButtonRunnable, 700)
        }
    }

    private val hideForwardButtonRunnable = Runnable {
        doubleTapOverlayBinding.forwardBTN.apply {
            visibility = View.GONE
        }
    }
    private val hideRewindButtonRunnable = Runnable {
        doubleTapOverlayBinding.rewindBTN.apply {
            visibility = View.GONE
        }
    }

    // enable seek bar preview
    private fun enableSeekbarPreview() {
        playerBinding.exoProgress.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                exoPlayer.pause()
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                val minTimeDiff = 10 * 1000 // 10s
                // get the difference between the new and the old position
                val diff = abs(exoPlayer.currentPosition - position)
                // seek only when the difference is greater than 10 seconds
                if (diff >= minTimeDiff) exoPlayer.seekTo(position)
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                exoPlayer.seekTo(position)
                exoPlayer.play()
                Handler(Looper.getMainLooper()).postDelayed({
                    exoPlayerView.hideController()
                }, 200)
            }
        })
    }

    private fun initializeChapters() {
        if (chapters.isNotEmpty()) {
            // enable chapters in the video description
            binding.chaptersRecView.layoutManager =
                LinearLayoutManager(
                    context,
                    LinearLayoutManager.HORIZONTAL,
                    false
                )
            binding.chaptersRecView.adapter = ChaptersAdapter(chapters, exoPlayer)
            binding.chaptersRecView.visibility = View.VISIBLE

            // enable the chapters dialog in the player
            val titles = mutableListOf<String>()
            chapters.forEach {
                titles += it.title!!
            }
            playerBinding.chapterLL.visibility = View.VISIBLE
            playerBinding.chapterLL.setOnClickListener {
                if (Globals.IS_FULL_SCREEN) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.chapters)
                        .setItems(titles.toTypedArray()) { _, index ->
                            val position = chapters[index].start!! * 1000
                            exoPlayer.seekTo(position)
                        }
                        .show()
                } else {
                    toggleDescription()
                }
            }
            setCurrentChapterName()
        }
    }

    // set the name of the video chapter in the exoPlayerView
    private fun setCurrentChapterName() {
        // call the function again in 100ms
        exoPlayerView.postDelayed(this::setCurrentChapterName, 100)

        val chapterIndex = getCurrentChapterIndex()
        val chapterName = chapters[chapterIndex].title

        // change the chapter name textView text to the chapterName
        if (chapterName != playerBinding.chapterName.text) {
            playerBinding.chapterName.text = chapterName
            // update the selected item
            val chaptersAdapter = binding.chaptersRecView.adapter as ChaptersAdapter
            chaptersAdapter.updateSelectedPosition(chapterIndex)
        }
    }

    // get the name of the currently played chapter
    private fun getCurrentChapterIndex(): Int {
        val currentPosition = exoPlayer.currentPosition
        var chapterIndex = 0

        chapters.forEachIndexed { index, chapter ->
            // check whether the chapter start is greater than the current player position
            if (currentPosition >= chapter.start!! * 1000) {
                // save chapter title if found
                chapterIndex = index
            }
        }
        return chapterIndex
    }

    private fun setMediaSource(
        videoUri: Uri,
        audioUrl: String
    ) {
        val dataSourceFactory: DataSource.Factory =
            DefaultHttpDataSource.Factory()
        val videoItem: MediaItem = MediaItem.Builder()
            .setUri(videoUri)
            .setSubtitleConfigurations(subtitle)
            .build()
        val videoSource: MediaSource =
            DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(videoItem)
        val audioSource: MediaSource =
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(fromUri(audioUrl))
        val mergeSource: MediaSource =
            MergingMediaSource(videoSource, audioSource)
        exoPlayer.setMediaSource(mergeSource)
    }

    private fun setResolutionAndSubtitles(response: Streams) {
        var videosNameArray: Array<CharSequence> = arrayOf()
        var videosUrlArray: Array<Uri> = arrayOf()

        // append hls to list if available
        if (response.hls != null) {
            videosNameArray += getString(R.string.hls)
            videosUrlArray += response.hls.toUri()
        }

        for (vid in response.videoStreams!!) {
            // append quality to list if it has the preferred format (e.g. MPEG)
            val preferredMimeType = "video/$videoFormatPreference"
            if (vid.url != null && vid.mimeType == preferredMimeType) { // preferred format
                videosNameArray += vid.quality.toString()
                videosUrlArray += vid.url!!.toUri()
            } else if (vid.quality.equals("LBRY") && vid.format.equals("MP4")) { // LBRY MP4 format
                videosNameArray += "LBRY MP4"
                videosUrlArray += vid.url!!.toUri()
            }
        }
        // create a list of subtitles
        subtitle = mutableListOf()
        val subtitlesNamesList = mutableListOf(context?.getString(R.string.none)!!)
        val subtitleCodesList = mutableListOf("")
        response.subtitles!!.forEach {
            subtitle.add(
                SubtitleConfiguration.Builder(it.url!!.toUri())
                    .setMimeType(it.mimeType!!) // The correct MIME type (required).
                    .setLanguage(it.code) // The subtitle language (optional).
                    .build()
            )
            subtitlesNamesList += it.name!!
            subtitleCodesList += it.code!!
        }

        // set the default subtitle if available
        if (defaultSubtitleCode != "" && subtitleCodesList.contains(defaultSubtitleCode)) {
            val newParams = trackSelector.buildUponParameters()
                .setPreferredTextLanguage(defaultSubtitleCode)
                .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION)
            trackSelector.setParameters(newParams)
            playerBinding.captions.setImageResource(R.drawable.ic_caption)
        }

        // captions selection dialog
        // hide caption selection view if no subtitles available
        if (response.subtitles.isEmpty()) playerBinding.captions.visibility = View.GONE
        playerBinding.captions.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.captions)
                .setItems(subtitlesNamesList.toTypedArray()) { _, index ->
                    val newParams = if (index != 0) {
                        // caption selected

                        // get the caption name and language
                        val captionLanguage = subtitlesNamesList[index]
                        val captionLanguageCode = subtitleCodesList[index]

                        // update the icon of the captions button
                        playerBinding.captions.setImageResource(R.drawable.ic_caption)

                        // select the new caption preference
                        trackSelector.buildUponParameters()
                            .setPreferredTextLanguages(
                                captionLanguage,
                                captionLanguageCode
                            )
                            .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION)
                    } else {
                        // none selected
                        playerBinding.captions.setImageResource(R.drawable.ic_caption_outlined)

                        // disable captions
                        trackSelector.buildUponParameters()
                            .setPreferredTextLanguage("")
                    }

                    // set the new caption language
                    trackSelector.setParameters(newParams)
                }
                .show()
        }

        // set media source and resolution in the beginning
        setStreamSource(
            response,
            videosNameArray,
            videosUrlArray
        )

        playerBinding.qualityText.setOnClickListener {
            // Dialog for quality selection
            val builder: MaterialAlertDialogBuilder? = activity?.let {
                MaterialAlertDialogBuilder(it)
            }
            val lastPosition = exoPlayer.currentPosition
            builder!!.setTitle(R.string.choose_quality_dialog)
                .setItems(
                    videosNameArray
                ) { _, which ->
                    if (
                        videosNameArray[which] == getString(R.string.hls) ||
                        videosNameArray[which] == "LBRY HLS"
                    ) {
                        // no need to merge sources if using hls
                        val mediaItem: MediaItem = MediaItem.Builder()
                            .setUri(videosUrlArray[which])
                            .setSubtitleConfigurations(subtitle)
                            .build()
                        exoPlayer.setMediaItem(mediaItem)
                    } else {
                        val videoUri = videosUrlArray[which]
                        val audioUrl = PlayerHelper.getAudioSource(response.audioStreams!!)
                        setMediaSource(videoUri, audioUrl)
                    }
                    exoPlayer.seekTo(lastPosition)
                    playerBinding.qualityText.text = videosNameArray[which]
                }
            val dialog = builder.create()
            dialog.show()
        }
    }

    private fun setStreamSource(
        streams: Streams,
        videosNameArray: Array<CharSequence>,
        videosUrlArray: Array<Uri>
    ) {
        if (defRes != "") {
            videosNameArray.forEachIndexed { index, pipedStream ->
                // search for quality preference in the available stream sources
                if (pipedStream.contains(defRes)) {
                    val videoUri = videosUrlArray[index]
                    val audioUrl = PlayerHelper.getAudioSource(streams.audioStreams!!)
                    setMediaSource(videoUri, audioUrl)
                    playerBinding.qualityText.text = videosNameArray[index]
                    return
                }
            }
        }

        // if default resolution isn't set or available, use hls if available
        if (streams.hls != null) {
            val mediaItem: MediaItem = MediaItem.Builder()
                .setUri(streams.hls)
                .setSubtitleConfigurations(subtitle)
                .build()
            exoPlayer.setMediaItem(mediaItem)
            playerBinding.qualityText.text = context?.getString(R.string.hls)
            return
        }

        // if nothing found, use the first list entry
        if (videosUrlArray.isNotEmpty()) {
            val videoUri = videosUrlArray[0]
            val audioUrl = PlayerHelper.getAudioSource(streams.audioStreams!!)
            setMediaSource(videoUri, audioUrl)
            playerBinding.qualityText.text = videosNameArray[0]
        }
    }

    private fun createExoPlayer() {
        val cronetEngine: CronetEngine = CronetHelper.getCronetEngine()
        val cronetDataSourceFactory: CronetDataSource.Factory =
            CronetDataSource.Factory(cronetEngine, Executors.newCachedThreadPool())

        val dataSourceFactory = DefaultDataSource.Factory(
            requireContext(),
            cronetDataSourceFactory
        )

        // handles the audio focus
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_MOVIE)
            .build()

        // handles the duration of media to retain in the buffer prior to the current playback position (for fast backward seeking)
        val loadControl = DefaultLoadControl.Builder()
            // cache the last three minutes
            .setBackBuffer(1000 * 60 * 3, true)
            .setBufferDurationsMs(
                1000 * 10, // exo default is 50s
                bufferingGoal,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()

        // control for the track sources like subtitles and audio source
        trackSelector = DefaultTrackSelector(requireContext())

        exoPlayer = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build()

        exoPlayer.setAudioAttributes(audioAttributes, true)
    }

    /**
     * show the [NowPlayingNotification] for the current video
     */
    private fun initializePlayerNotification() {
        if (!this::nowPlayingNotification.isInitialized) {
            nowPlayingNotification = NowPlayingNotification(requireContext(), exoPlayer)
        }
        nowPlayingNotification.updatePlayerNotification(streams)
    }

    // lock the player
    private fun lockPlayer(isLocked: Boolean) {
        // isLocked is the current (old) state of the player lock
        val visibility = if (isLocked) View.VISIBLE else View.GONE

        playerBinding.exoTopBarRight.visibility = visibility
        playerBinding.exoPlayPause.visibility = visibility
        playerBinding.exoBottomBar.visibility = visibility
        playerBinding.closeImageButton.visibility = visibility
        playerBinding.exoTitle.visibility =
            if (isLocked &&
                Globals.IS_FULL_SCREEN
            ) View.VISIBLE else View.INVISIBLE

        // disable double tap to seek when the player is locked
        if (isLocked) {
            // enable fast forward and rewind by double tapping
            enableDoubleTapToSeek()
        } else {
            // disable fast forward and rewind by double tapping
            binding.player.setOnDoubleTapListener(null)
        }
    }

    private fun isSubscribed() {
        fun run() {
            val channelId = streams.uploaderUrl.toID()
            lifecycleScope.launchWhenCreated {
                isSubscribed = SubscriptionHelper.isSubscribed(channelId)

                if (isSubscribed == null) return@launchWhenCreated

                runOnUiThread {
                    if (isSubscribed == true) {
                        binding.playerSubscribe.text = getString(R.string.unsubscribe)
                    }
                    binding.playerSubscribe.setOnClickListener {
                        if (isSubscribed == true) {
                            SubscriptionHelper.unsubscribe(channelId)
                            binding.playerSubscribe.text = getString(R.string.subscribe)
                            isSubscribed = false
                        } else {
                            SubscriptionHelper.subscribe(channelId)
                            binding.playerSubscribe.text = getString(R.string.unsubscribe)
                            isSubscribed = true
                        }
                    }
                }
            }
        }
        run()
    }

    private fun fetchComments() {
        lifecycleScope.launchWhenCreated {
            val commentsResponse = try {
                RetrofitInstance.api.getComments(videoId!!)
            } catch (e: IOException) {
                println(e)
                Log.e(TAG, "IOException, you might not have internet connection")
                Toast.makeText(context, R.string.unknown_error, Toast.LENGTH_SHORT).show()
                return@launchWhenCreated
            } catch (e: HttpException) {
                Log.e(TAG, "HttpException, unexpected response")
                return@launchWhenCreated
            }
            commentsAdapter = CommentsAdapter(videoId!!, commentsResponse.comments)
            binding.commentsRecView.adapter = commentsAdapter
            nextPage = commentsResponse.nextpage
            commentsLoaded = true
            isLoading = false
        }
    }

    private fun fetchNextComments() {
        lifecycleScope.launchWhenCreated {
            if (!isLoading) {
                isLoading = true
                val response = try {
                    RetrofitInstance.api.getCommentsNextPage(videoId!!, nextPage!!)
                } catch (e: IOException) {
                    println(e)
                    Log.e(TAG, "IOException, you might not have internet connection")
                    return@launchWhenCreated
                } catch (e: HttpException) {
                    Log.e(TAG, "HttpException, unexpected response," + e.response())
                    return@launchWhenCreated
                }
                nextPage = response.nextpage
                commentsAdapter?.updateItems(response.comments)
                isLoading = false
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            // hide and disable exoPlayer controls
            exoPlayerView.hideController()
            exoPlayerView.useController = false

            // set portrait mode
            unsetFullscreen()

            with(binding.playerMotionLayout) {
                getConstraintSet(R.id.start).constrainHeight(R.id.player, -1)
                enableTransition(R.id.yt_transition, false)
            }
            binding.linLayout.visibility = View.GONE

            Globals.IS_FULL_SCREEN = false
        } else {
            // enable exoPlayer controls again
            exoPlayerView.useController = true

            with(binding.playerMotionLayout) {
                getConstraintSet(R.id.start).constrainHeight(R.id.player, 0)
                enableTransition(R.id.yt_transition, true)
            }
            binding.linLayout.visibility = View.VISIBLE
        }
    }

    fun onUserLeaveHint() {
        if (SDK_INT >= Build.VERSION_CODES.O && shouldStartPiP()) {
            activity?.enterPictureInPictureMode(updatePipParams())
        }
    }

    private fun shouldStartPiP(): Boolean {
        val bounds = Rect()
        binding.playerScrollView.getHitRect(bounds)

        val backgroundModeRunning = isServiceRunning(requireContext(), BackgroundMode::class.java)

        return (binding.playerScrollView.getLocalVisibleRect(bounds) || Globals.IS_FULL_SCREEN) &&
            (exoPlayer.isPlaying || !backgroundModeRunning)
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun updatePipParams() = PictureInPictureParams.Builder()
        .setActions(emptyList())
        .build()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (autoRotationEnabled) {
            val orientation = newConfig.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                // go to fullscreen mode
                setFullscreen()
            } else {
                // exit fullscreen if not landscape
                unsetFullscreen()
            }
        }
    }
}
