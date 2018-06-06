package com.brentvatne.exoplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.brentvatne.react.R;
import com.brentvatne.receiver.AudioBecomingNoisyReceiver;
import com.brentvatne.receiver.BecomingNoisyListener;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.uimanager.ThemedReactContext;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.MetadataRenderer;
import com.google.android.exoplayer2.source.BehindLiveWindowException;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.LoopingMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.util.Util;

import com.google.ads.interactivemedia.v3.api.AdDisplayContainer;
import com.google.ads.interactivemedia.v3.api.AdErrorEvent;
import com.google.ads.interactivemedia.v3.api.AdErrorEvent.AdErrorListener;
import com.google.ads.interactivemedia.v3.api.AdEvent;
import com.google.ads.interactivemedia.v3.api.AdEvent.AdEventListener;
import com.google.ads.interactivemedia.v3.api.AdsLoader;
import com.google.ads.interactivemedia.v3.api.AdsLoader.AdsLoadedListener;
import com.google.ads.interactivemedia.v3.api.AdsManager;
import com.google.ads.interactivemedia.v3.api.AdsManagerLoadedEvent;
import com.google.ads.interactivemedia.v3.api.AdsRequest;
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory;
import com.google.ads.interactivemedia.v3.api.player.ContentProgressProvider;
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate;
import com.google.ads.interactivemedia.v3.api.ImaSdkSettings;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.lang.Math;

@SuppressLint("ViewConstructor")
class ReactExoplayerView extends FrameLayout
        implements LifecycleEventListener, ExoPlayer.EventListener, BecomingNoisyListener,
        AudioManager.OnAudioFocusChangeListener, MetadataRenderer.Output, AdEventListener, AdErrorListener {

    private static final String TAG = "ReactNative";

    private static final DefaultBandwidthMeter BANDWIDTH_METER = new DefaultBandwidthMeter();
    private static final CookieManager DEFAULT_COOKIE_MANAGER;
    private static final int SHOW_PROGRESS = 1;

    static {
        DEFAULT_COOKIE_MANAGER = new CookieManager();
        DEFAULT_COOKIE_MANAGER.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    }

    private final VideoEventEmitter eventEmitter;

    private Handler mainHandler;
    private ExoPlayerView exoPlayerView;

    private DataSource.Factory mediaDataSourceFactory;
    private SimpleExoPlayer player;
    private MappingTrackSelector trackSelector;
    private boolean playerNeedsSource;

    private int resumeWindow;
    private long resumePosition;
    private boolean loadVideoStarted;
    private boolean isFullscreen;
    private boolean isPaused = true;
    private boolean isBuffering;
    private float rate = 1f;

    // Props from React
    private Uri srcUri;
    private String extension;
    private boolean repeat;
    private boolean disableFocus;
    private float mProgressUpdateInterval = 250.0f;
    private boolean playInBackground = false;
    // \ End props

    // React
    private final ThemedReactContext themedReactContext;
    private final AudioManager audioManager;
    private final AudioBecomingNoisyReceiver audioBecomingNoisyReceiver;

    private final Handler progressHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case SHOW_PROGRESS:
                if (player != null && player.getPlaybackState() == ExoPlayer.STATE_READY && player.getPlayWhenReady()) {
                    long pos = player.getCurrentPosition();
                    long bufferedDuration = player.getBufferedPercentage() * player.getDuration();
                    eventEmitter.progressChanged(pos, bufferedDuration, player.getDuration());
                    msg = obtainMessage(SHOW_PROGRESS);
                    sendMessageDelayed(msg, Math.round(mProgressUpdateInterval));
                }
                break;
            }
        }
    };

    // this object keeps track of instreamAdInfo
    // if this object is null then we have no instream ad
    private InstreamAdInfo instreamAdInfo = null;

    /* GoogleIMA defenitions */

    // these objects are related to GoogleIMA SDK
    private ImaSdkFactory mSdkFactory;
    private AdsLoader mAdsLoader;
    private AdsManager mAdsManager;

    // Whether an ad is displayed.
    private boolean mIsAdDisplayed = false;

    // show if we requested instreamAd or not
    private boolean isInstreamAdRequested = false;

    // at first these variables will be filled instead of main uri and extention
    private Uri initialUri = null;
    private String initialExtension = null;
    private boolean isInitialSrcSet = false;
    private boolean isSrcRaw = false;

    // show if we found that we have no instreamAd or not
    private boolean doNothaveAd = false;

    // show that if some ad is paused or not
    private boolean isAdWaitingForResume = isPaused;

    // show if preroll ad has shown or not
    private boolean isPrerollAdShown = false;

    /* end of GoogleIMA defenitions */

    public ReactExoplayerView(ThemedReactContext context) {
        super(context);
        createViews();
        this.eventEmitter = new VideoEventEmitter(context);
        this.themedReactContext = context;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        themedReactContext.addLifecycleEventListener(this);
        audioBecomingNoisyReceiver = new AudioBecomingNoisyReceiver(themedReactContext);

        initializePlayer();

        // initialize an ImaSdkFactory object (related to GoogleIMA SDK)
        this.mSdkFactory = ImaSdkFactory.getInstance();
    }

    @Override
    public void setId(int id) {
        super.setId(id);
        eventEmitter.setViewId(id);
    }

    private void createViews() {
        clearResumePosition();
        mediaDataSourceFactory = buildDataSourceFactory(true);
        mainHandler = new Handler();
        if (CookieHandler.getDefault() != DEFAULT_COOKIE_MANAGER) {
            CookieHandler.setDefault(DEFAULT_COOKIE_MANAGER);
        }

        LayoutParams layoutParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        exoPlayerView = new ExoPlayerView(getContext());
        exoPlayerView.setLayoutParams(layoutParams);

        addView(exoPlayerView, 0, layoutParams);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        initializePlayer();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPlayback();
    }

    // LifecycleEventListener implementation

    @Override
    public void onHostResume() {
        if (playInBackground) {
            return;
        }
        setPlayWhenReady(!isPaused);

        // related to GoogleIMA SDK
        if (instreamAdInfo != null && mAdsManager != null && mIsAdDisplayed) {
            mAdsManager.resume();
        }
    }

    @Override
    public void onHostPause() {
        if (playInBackground) {
            return;
        }
        setPlayWhenReady(false);

        // related to GoogleIMA SDK
        if (instreamAdInfo != null && mAdsManager != null && mIsAdDisplayed) {
            mAdsManager.pause();
        }
    }

    @Override
    public void onHostDestroy() {
        stopPlayback();
    }

    public void cleanUpResources() {
        stopPlayback();
    }

    // Internal methods

    private void initializePlayer() {
        if (player == null) {
            TrackSelection.Factory videoTrackSelectionFactory = new AdaptiveTrackSelection.Factory(BANDWIDTH_METER);
            trackSelector = new DefaultTrackSelector(videoTrackSelectionFactory);
            player = ExoPlayerFactory.newSimpleInstance(getContext(), trackSelector, new DefaultLoadControl());
            player.addListener(this);
            player.setMetadataOutput(this);
            exoPlayerView.setPlayer(player);
            audioBecomingNoisyReceiver.setListener(this);
            setPlayWhenReady(!isPaused);
            playerNeedsSource = true;

            PlaybackParameters params = new PlaybackParameters(rate, 1f);
            player.setPlaybackParameters(params);
        }
        if (playerNeedsSource && srcUri != null) {
            MediaSource mediaSource = buildMediaSource(srcUri, extension);
            mediaSource = repeat ? new LoopingMediaSource(mediaSource) : mediaSource;
            boolean haveResumePosition = resumeWindow != C.INDEX_UNSET;
            if (haveResumePosition) {
                player.seekTo(resumeWindow, resumePosition);
            }
            player.prepare(mediaSource, !haveResumePosition, false);
            playerNeedsSource = false;

            eventEmitter.loadStart();
            loadVideoStarted = true;
        }
    }

    private MediaSource buildMediaSource(Uri uri, String overrideExtension) {
        int type = Util.inferContentType(
                !TextUtils.isEmpty(overrideExtension) ? "." + overrideExtension : uri.getLastPathSegment());
        switch (type) {
        case C.TYPE_SS:
            return new SsMediaSource(uri, buildDataSourceFactory(false),
                    new DefaultSsChunkSource.Factory(mediaDataSourceFactory), mainHandler, null);
        case C.TYPE_DASH:
            return new DashMediaSource(uri, buildDataSourceFactory(false),
                    new DefaultDashChunkSource.Factory(mediaDataSourceFactory), mainHandler, null);
        case C.TYPE_HLS:
            return new HlsMediaSource(uri, mediaDataSourceFactory, mainHandler, null);
        case C.TYPE_OTHER:
            return new ExtractorMediaSource(uri, mediaDataSourceFactory, new DefaultExtractorsFactory(), mainHandler,
                    null);
        default: {
            throw new IllegalStateException("Unsupported type: " + type);
        }
        }
    }

    private void releasePlayer() {
        if (player != null) {
            isPaused = player.getPlayWhenReady();
            updateResumePosition();
            player.release();
            player.setMetadataOutput(null);
            player = null;
            trackSelector = null;
        }
        progressHandler.removeMessages(SHOW_PROGRESS);
        themedReactContext.removeLifecycleEventListener(this);
        audioBecomingNoisyReceiver.removeListener();
    }

    private boolean requestAudioFocus() {
        if (disableFocus) {
            return true;
        }
        int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void setPlayWhenReady(boolean playWhenReady) {
        if (player == null) {
            return;
        }

        if (playWhenReady) {
            boolean hasAudioFocus = requestAudioFocus();
            if (hasAudioFocus) {
                player.setPlayWhenReady(true);
            }
        } else {
            player.setPlayWhenReady(false);
        }
    }

    private void startPlayback() {
        if (player != null) {
            switch (player.getPlaybackState()) {
            case ExoPlayer.STATE_IDLE:
            case ExoPlayer.STATE_ENDED:
                initializePlayer();
                break;
            case ExoPlayer.STATE_BUFFERING:
            case ExoPlayer.STATE_READY:
                if (!player.getPlayWhenReady()) {
                    setPlayWhenReady(true);
                }
                break;
            default:
                break;
            }

        } else {
            initializePlayer();
        }
        if (!disableFocus) {
            setKeepScreenOn(true);
        }
    }

    private void pausePlayback() {
        if (player != null) {
            if (player.getPlayWhenReady()) {
                setPlayWhenReady(false);
            }
        }
        setKeepScreenOn(false);
    }

    private void stopPlayback() {
        onStopPlayback();
        releasePlayer();
    }

    private void onStopPlayback() {
        if (isFullscreen) {
            setFullscreen(false);
        }
        setKeepScreenOn(false);
        audioManager.abandonAudioFocus(this);
    }

    private void updateResumePosition() {
        resumeWindow = player.getCurrentWindowIndex();
        resumePosition = player.isCurrentWindowSeekable() ? Math.max(0, player.getCurrentPosition()) : C.TIME_UNSET;
    }

    private void clearResumePosition() {
        resumeWindow = C.INDEX_UNSET;
        resumePosition = C.TIME_UNSET;
    }

    /**
     * Returns a new DataSource factory.
     *
     * @param useBandwidthMeter Whether to set {@link #BANDWIDTH_METER} as a
     *                          listener to the new DataSource factory.
     * @return A new DataSource factory.
     */
    private DataSource.Factory buildDataSourceFactory(boolean useBandwidthMeter) {
        return DataSourceUtil.getDefaultDataSourceFactory(getContext(), useBandwidthMeter ? BANDWIDTH_METER : null);
    }

    // AudioManager.OnAudioFocusChangeListener implementation

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
        case AudioManager.AUDIOFOCUS_LOSS:
            eventEmitter.audioFocusChanged(false);
            break;
        case AudioManager.AUDIOFOCUS_GAIN:
            eventEmitter.audioFocusChanged(true);
            break;
        default:
            break;
        }

        if (player != null) {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                // Lower the volume
                player.setVolume(0.8f);
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                // Raise it back to normal
                player.setVolume(1);
            }
        }
    }

    // AudioBecomingNoisyListener implementation

    @Override
    public void onAudioBecomingNoisy() {
        eventEmitter.audioBecomingNoisy();
    }

    // ExoPlayer.EventListener implementation

    @Override
    public void onLoadingChanged(boolean isLoading) {
        // Do nothing.
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        String text = "onStateChanged: playWhenReady=" + playWhenReady + ", playbackState=";
        switch (playbackState) {
        case ExoPlayer.STATE_IDLE:
            text += "idle";
            eventEmitter.idle();
            break;
        case ExoPlayer.STATE_BUFFERING:
            text += "buffering";
            onBuffering(true);
            break;
        case ExoPlayer.STATE_READY:
            text += "ready";
            eventEmitter.ready();
            onBuffering(false);
            startProgressHandler();
            videoLoaded();
            break;
        case ExoPlayer.STATE_ENDED:
            text += "ended";
            eventEmitter.end();
            onStopPlayback();

            // this method is related to GoogleIMA SDK
            onPlayerCompleteVideo();

            break;
        default:
            text += "unknown";
            break;
        }
        Log.d(TAG, text);
    }

    private void startProgressHandler() {
        progressHandler.sendEmptyMessage(SHOW_PROGRESS);
    }

    private void videoLoaded() {
        if (loadVideoStarted) {
            loadVideoStarted = false;
            Format videoFormat = player.getVideoFormat();
            int width = videoFormat != null ? videoFormat.width : 0;
            int height = videoFormat != null ? videoFormat.height : 0;
            eventEmitter.load(player.getDuration(), player.getCurrentPosition(), width, height);
        }
    }

    private void onBuffering(boolean buffering) {
        if (isBuffering == buffering) {
            return;
        }

        isBuffering = buffering;
        if (buffering) {
            eventEmitter.buffering(true);
        } else {
            eventEmitter.buffering(false);
        }
    }

    @Override
    public void onPositionDiscontinuity() {
        if (playerNeedsSource) {
            // This will only occur if the user has performed a seek whilst in the error
            // state. Update the
            // resume position so that if the user then retries, playback will resume from
            // the position to
            // which they seeked.
            updateResumePosition();
        }
    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest) {
        // Do nothing.
    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        // Do Nothing.
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters params) {
        eventEmitter.playbackRateChange(params.speed);
    }

    @Override
    public void onPlayerError(ExoPlaybackException e) {
        String errorString = null;
        Exception ex = e;
        if (e.type == ExoPlaybackException.TYPE_RENDERER) {
            Exception cause = e.getRendererException();
            if (cause instanceof MediaCodecRenderer.DecoderInitializationException) {
                // Special case for decoder initialization failures.
                MediaCodecRenderer.DecoderInitializationException decoderInitializationException = (MediaCodecRenderer.DecoderInitializationException) cause;
                if (decoderInitializationException.decoderName == null) {
                    if (decoderInitializationException.getCause() instanceof MediaCodecUtil.DecoderQueryException) {
                        errorString = getResources().getString(R.string.error_querying_decoders);
                    } else if (decoderInitializationException.secureDecoderRequired) {
                        errorString = getResources().getString(R.string.error_no_secure_decoder,
                                decoderInitializationException.mimeType);
                    } else {
                        errorString = getResources().getString(R.string.error_no_decoder,
                                decoderInitializationException.mimeType);
                    }
                } else {
                    errorString = getResources().getString(R.string.error_instantiating_decoder,
                            decoderInitializationException.decoderName);
                }
            }
        } else if (e.type == ExoPlaybackException.TYPE_SOURCE) {
            ex = e.getSourceException();
            errorString = getResources().getString(R.string.unrecognized_media_format);
        }
        if (errorString != null) {
            eventEmitter.error(errorString, ex);
        }
        playerNeedsSource = true;
        if (isBehindLiveWindow(e)) {
            clearResumePosition();
            initializePlayer();
        } else {
            updateResumePosition();
        }
    }

    private static boolean isBehindLiveWindow(ExoPlaybackException e) {
        if (e.type != ExoPlaybackException.TYPE_SOURCE) {
            return false;
        }
        Throwable cause = e.getSourceException();
        while (cause != null) {
            if (cause instanceof BehindLiveWindowException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Override
    public void onMetadata(Metadata metadata) {
        eventEmitter.timedMetadata(metadata);
    }

    private void setPlayerSrc(final Uri uri, final String extension) {
        if (uri != null) {
            boolean isOriginalSourceNull = srcUri == null;
            boolean isSourceEqual = uri.equals(srcUri);

            this.srcUri = uri;
            this.extension = extension;
            this.mediaDataSourceFactory = DataSourceUtil.getDefaultDataSourceFactory(getContext(), BANDWIDTH_METER);

            if (isOriginalSourceNull || !isSourceEqual) {
                reloadSource();
            }
        }
    }

    private void setPlayerRawSrc(final Uri uri, final String extension) {
        if (uri != null) {
            boolean isOriginalSourceNull = srcUri == null;
            boolean isSourceEqual = uri.equals(srcUri);

            this.srcUri = uri;
            this.extension = extension;
            this.mediaDataSourceFactory = DataSourceUtil.getRawDataSourceFactory(getContext());

            if (isOriginalSourceNull || !isSourceEqual) {
                reloadSource();
            }
        }
    }

    // ReactExoplayerViewManager public api

    public void setProgressUpdateInterval(final float progressUpdateInterval) {
        mProgressUpdateInterval = progressUpdateInterval;
    }

    private void reloadSource() {
        playerNeedsSource = true;
        initializePlayer();
    }

    public void setResizeModeModifier(@ResizeMode.Mode int resizeMode) {
        exoPlayerView.setResizeMode(resizeMode);
    }

    public void setRepeatModifier(boolean repeat) {
        this.repeat = repeat;
    }

    public void setPausedModifier(boolean paused) {
        // related to GoogleIMA SDK (only if blew)
        if (instreamAdInfo != null && mAdsManager != null && isPaused && isAdWaitingForResume && paused == false) {
            mAdsManager.start();
            isAdWaitingForResume = false;
        }

        isPaused = paused;
        if (player != null) {
            if (!paused) {
                startPlayback();
            } else {
                pausePlayback();
            }
        }
    }

    public void setMutedModifier(boolean muted) {
        if (player != null) {
            player.setVolume(muted ? 0 : 1);
        }
    }

    public void setVolumeModifier(float volume) {
        if (player != null) {
            player.setVolume(volume);
        }
    }

    public void seekTo(long positionMs) {
        if (player != null) {
            eventEmitter.seek(player.getCurrentPosition(), positionMs);
            player.seekTo(positionMs);
        }
    }

    public void setRateModifier(float newRate) {
        rate = newRate;

        if (player != null) {
            PlaybackParameters params = new PlaybackParameters(rate, 1f);
            player.setPlaybackParameters(params);
        }
    }

    public void setPlayInBackground(boolean playInBackground) {
        this.playInBackground = playInBackground;
    }

    public void setDisableFocus(boolean disableFocus) {
        this.disableFocus = disableFocus;
    }

    public void setFullscreen(boolean fullscreen) {
        if (fullscreen == isFullscreen) {
            return; // Avoid generating events when nothing is changing
        }
        isFullscreen = fullscreen;

        Activity activity = themedReactContext.getCurrentActivity();
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        View decorView = window.getDecorView();
        int uiOptions;
        if (isFullscreen) {
            if (Util.SDK_INT >= 19) { // 4.4+
                uiOptions = SYSTEM_UI_FLAG_HIDE_NAVIGATION | SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | SYSTEM_UI_FLAG_FULLSCREEN;
            } else {
                uiOptions = SYSTEM_UI_FLAG_HIDE_NAVIGATION | SYSTEM_UI_FLAG_FULLSCREEN;
            }
            eventEmitter.fullscreenWillPresent();
            decorView.setSystemUiVisibility(uiOptions);
            eventEmitter.fullscreenDidPresent();
        } else {
            uiOptions = View.SYSTEM_UI_FLAG_VISIBLE;
            eventEmitter.fullscreenWillDismiss();
            decorView.setSystemUiVisibility(uiOptions);
            eventEmitter.fullscreenDidDismiss();
        }
    }

    /* GoogleIMA related methods */

    // instreamAdInfo setter
    // if instreamAdInfo is not null => we have instream ad
    public void setInstreamAdInfo(InstreamAdInfo newInstreamAdInfo) {
        // set instreamAdInfo(called by JS)
        this.instreamAdInfo = newInstreamAdInfo;

        // create an ImaSdkSettings object in order to pass options to GoogleIMA SDK
        // such az adLang, ...
        ImaSdkSettings mImaSdkSettings = ImaSdkFactory.getInstance().createImaSdkSettings();
        mImaSdkSettings.setLanguage(this.instreamAdInfo.getAdLang());

        // Create an AdsLoader.
        this.mAdsLoader = mSdkFactory.createAdsLoader(this.getContext(), mImaSdkSettings);
        // Add listeners for when ads are loaded and for errors.
        mAdsLoader.addAdErrorListener(this);
        this.mAdsLoader.addAdsLoadedListener(new AdsLoadedListener() {
            @Override
            public void onAdsManagerLoaded(AdsManagerLoadedEvent adsManagerLoadedEvent) {
                // Ads were successfully loaded, so get the AdsManager instance. AdsManager has
                // events for ad playback and errors.
                mAdsManager = adsManagerLoadedEvent.getAdsManager();

                // Attach event and error event listeners.
                mAdsManager.addAdErrorListener(ReactExoplayerView.this);
                mAdsManager.addAdEventListener(ReactExoplayerView.this);
                mAdsManager.init();
            }
        });

        requestAds(instreamAdInfo.getAdTagUrl());
    }

    // this method is called when we found out we have no instreamAd
    public void doNotHaveInstreamAd() {
        if (this.isInitialSrcSet) {
            if (!this.isSrcRaw)
                this.setPlayerSrc(this.initialUri, this.initialExtension);
            else
                this.setPlayerRawSrc(this.initialUri, this.initialExtension);
        }

        this.doNothaveAd = true;
        eventEmitter.videoWillBeStarted();
    }

    public void setSrc(final Uri uri, final String extension) {
        if (doNothaveAd || isPrerollAdShown) {
            this.setPlayerSrc(uri, extension);
        } else {
            this.initialUri = uri;
            this.initialExtension = extension;
            this.isInitialSrcSet = true;
            this.isSrcRaw = false;
        }
    }

    public void setRawSrc(final Uri uri, final String extension) {
        if (doNothaveAd || isPrerollAdShown) {
            this.setPlayerRawSrc(uri, extension);
        } else {
            this.initialUri = uri;
            this.initialExtension = extension;
            this.isInitialSrcSet = true;
            this.isSrcRaw = true;
        }
    }

    // Add listener for when the content video finishes.
    private void onPlayerCompleteVideo() {
        // if player should play ad
        if (this.instreamAdInfo != null) {
            // Handle completed event for playing post-rolls.
            if (mAdsLoader != null) {
                mAdsLoader.contentComplete();
            }
        }
    }

    // Request video ads from the given VAST ad tag.
    private void requestAds(String adTagUrl) {
        if (isInstreamAdRequested)
            return;

        isInstreamAdRequested = true;

        // tell GoogleIMA SDK where to play instream ad
        AdDisplayContainer adDisplayContainer = mSdkFactory.createAdDisplayContainer();
        adDisplayContainer.setAdContainer((ViewGroup) this);

        // Create the ads request.
        AdsRequest request = mSdkFactory.createAdsRequest();
        request.setAdTagUrl(adTagUrl);
        request.setAdDisplayContainer(adDisplayContainer);
        request.setContentProgressProvider(new ContentProgressProvider() {
            @Override
            public VideoProgressUpdate getContentProgress() {
                if (mIsAdDisplayed || player == null || player.getDuration() <= 0) {
                    return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
                }
                return new VideoProgressUpdate(player.getCurrentPosition(), player.getDuration());
            }
        });

        // Request the ad. After the ad is loaded, onAdsManagerLoaded() will be
        mAdsLoader.requestAds(request);
    }

    @Override
    public void onAdEvent(AdEvent adEvent) {
        Log.i(TAG, "Event: " + adEvent.getType());

        // These are the suggested event types to handle. For full list of all ad event
        // types, see the documentation for AdEvent.AdEventType.
        switch (adEvent.getType()) {
        case LOADED:
            // AdEventType.LOADED will be fired when ads are ready to be played.
            // AdsManager.start() begins ad playback. This method is ignored for VMAP or
            // ad rules playlists, as the SDK will automatically start executing the
            // playlist.

            if (!isPaused)
                mAdsManager.start();
            else
                isAdWaitingForResume = true;

            break;
        case CONTENT_PAUSE_REQUESTED:
            // AdEventType.CONTENT_PAUSE_REQUESTED is fired immediately before a video
            // ad is played.
            mIsAdDisplayed = true;
            setPausedModifier(true);

            invalidateView(this, 0, 32);

            break;
        case CONTENT_RESUME_REQUESTED:
            // AdEventType.CONTENT_RESUME_REQUESTED is fired when the ad is completed
            // and you should start playing your content.
            mIsAdDisplayed = false;
            setPausedModifier(false);
            setPlayWhenReady(true);
            setFinalSrcInPlayer();
            break;
        case ALL_ADS_COMPLETED:
            if (mAdsManager != null) {
                mAdsManager.destroy();
                mAdsManager = null;
            }
            isInstreamAdRequested = false;
            break;
        case STARTED:
            setPlayWhenReady(false);
            setFinalSrcInPlayer();
            break;
        case SKIPPED:
        case COMPLETED:
            eventEmitter.videoWillBeStarted();
            this.isPrerollAdShown = true;
            break;
        default:
            break;
        }
    }

    private void setFinalSrcInPlayer() {
        if (this.isInitialSrcSet) {
            if (!this.isSrcRaw)
                this.setPlayerSrc(this.initialUri, this.initialExtension);
            else
                this.setPlayerRawSrc(this.initialUri, this.initialExtension);
        }
    }

    // if we have some problem in showing ad => we play main video
    @Override
    public void onAdError(AdErrorEvent adErrorEvent) {
        Log.e(TAG, "Ad Error: " + adErrorEvent.getError().getMessage());

        setPlayWhenReady(true);

        if (!isPaused)
            setPausedModifier(false);
        else
            setPausedModifier(true);

        eventEmitter.videoWillBeStarted();
        setFinalSrcInPlayer();
    }

    // this method tries to fix some issue on displaying ads
    private void invalidateView(final View view, final int round, final int total) {
        if (round == total)
            return;

        view.measure(View.MeasureSpec.makeMeasureSpec(view.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(view.getHeight(), View.MeasureSpec.EXACTLY));
        view.layout(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());

        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                invalidateView(view, round + 1, total);
            }
        }, 250);
    }

    /* end of GoogleIMA related methods */
}
