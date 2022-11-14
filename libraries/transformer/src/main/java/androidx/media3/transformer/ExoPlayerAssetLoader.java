/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.transformer;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS;
import static androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS;
import static androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MAX_BUFFER_MS;
import static androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MIN_BUFFER_MS;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_NO_TRANSFORMATION;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_UNAVAILABLE;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
import static java.lang.Math.min;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.Clock;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

/* package */ final class ExoPlayerAssetLoader {

  public interface Listener {

    void onTrackRegistered();

    void onAllTracksRegistered();

    SamplePipeline onTrackAdded(Format format, long streamStartPositionUs, long streamOffsetUs)
        throws TransformationException;

    void onEnded();

    void onError(Exception e);
  }

  private final Context context;
  private final boolean removeAudio;
  private final boolean removeVideo;
  private final MediaSource.Factory mediaSourceFactory;
  private final Looper looper;
  private final Clock clock;

  @Nullable private ExoPlayer player;
  private @Transformer.ProgressState int progressState;

  public ExoPlayerAssetLoader(
      Context context,
      boolean removeAudio,
      boolean removeVideo,
      MediaSource.Factory mediaSourceFactory,
      Looper looper,
      Clock clock) {
    this.context = context;
    this.removeAudio = removeAudio;
    this.removeVideo = removeVideo;
    this.mediaSourceFactory = mediaSourceFactory;
    this.looper = looper;
    this.clock = clock;
    progressState = PROGRESS_STATE_NO_TRANSFORMATION;
  }

  public void start(MediaItem mediaItem, Listener listener) {
    DefaultTrackSelector trackSelector = new DefaultTrackSelector(context);
    trackSelector.setParameters(
        new DefaultTrackSelector.Parameters.Builder(context)
            .setForceHighestSupportedBitrate(true)
            .build());
    // Arbitrarily decrease buffers for playback so that samples start being sent earlier to the
    // pipelines (rebuffers are less problematic for the transformation use case).
    DefaultLoadControl loadControl =
        new DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DEFAULT_MIN_BUFFER_MS,
                DEFAULT_MAX_BUFFER_MS,
                DEFAULT_BUFFER_FOR_PLAYBACK_MS / 10,
                DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / 10)
            .build();
    ExoPlayer.Builder playerBuilder =
        new ExoPlayer.Builder(context, new RenderersFactoryImpl(removeAudio, removeVideo, listener))
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setLooper(looper);
    if (clock != Clock.DEFAULT) {
      // Transformer.Builder#setClock is also @VisibleForTesting, so if we're using a non-default
      // clock we must be in a test context.
      @SuppressWarnings("VisibleForTests")
      ExoPlayer.Builder unusedForAnnotation = playerBuilder.setClock(clock);
    }

    player = playerBuilder.build();
    player.setMediaItem(mediaItem);
    player.addListener(new PlayerListener(listener));
    player.prepare();

    progressState = PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
  }

  public @Transformer.ProgressState int getProgress(ProgressHolder progressHolder) {
    if (progressState == PROGRESS_STATE_AVAILABLE) {
      Player player = checkNotNull(this.player);
      long durationMs = player.getDuration();
      long positionMs = player.getCurrentPosition();
      progressHolder.progress = min((int) (positionMs * 100 / durationMs), 99);
    }
    return progressState;
  }

  public void release() {
    progressState = PROGRESS_STATE_NO_TRANSFORMATION;
    if (player != null) {
      player.release();
      player = null;
    }
  }

  private static final class RenderersFactoryImpl implements RenderersFactory {

    private final TransformerMediaClock mediaClock;
    private final boolean removeAudio;
    private final boolean removeVideo;
    private final ExoPlayerAssetLoader.Listener assetLoaderListener;

    public RenderersFactoryImpl(
        boolean removeAudio,
        boolean removeVideo,
        ExoPlayerAssetLoader.Listener assetLoaderListener) {
      this.removeAudio = removeAudio;
      this.removeVideo = removeVideo;
      this.assetLoaderListener = assetLoaderListener;
      mediaClock = new TransformerMediaClock();
    }

    @Override
    public Renderer[] createRenderers(
        Handler eventHandler,
        VideoRendererEventListener videoRendererEventListener,
        AudioRendererEventListener audioRendererEventListener,
        TextOutput textRendererOutput,
        MetadataOutput metadataRendererOutput) {
      int rendererCount = removeAudio || removeVideo ? 1 : 2;
      Renderer[] renderers = new Renderer[rendererCount];
      int index = 0;
      if (!removeAudio) {
        renderers[index] =
            new ExoPlayerAssetLoaderRenderer(C.TRACK_TYPE_AUDIO, mediaClock, assetLoaderListener);
        index++;
      }
      if (!removeVideo) {
        renderers[index] =
            new ExoPlayerAssetLoaderRenderer(C.TRACK_TYPE_VIDEO, mediaClock, assetLoaderListener);
        index++;
      }
      return renderers;
    }
  }

  private final class PlayerListener implements Player.Listener {

    private final Listener listener;

    public PlayerListener(Listener listener) {
      this.listener = listener;
    }

    @Override
    public void onPlaybackStateChanged(int state) {
      if (state == Player.STATE_ENDED) {
        listener.onEnded();
      }
    }

    @Override
    public void onTimelineChanged(Timeline timeline, int reason) {
      if (progressState != PROGRESS_STATE_WAITING_FOR_AVAILABILITY) {
        return;
      }
      Timeline.Window window = new Timeline.Window();
      timeline.getWindow(/* windowIndex= */ 0, window);
      if (!window.isPlaceholder) {
        long durationUs = window.durationUs;
        // Make progress permanently unavailable if the duration is unknown, so that it doesn't jump
        // to a high value at the end of the transformation if the duration is set once the media is
        // entirely loaded.
        progressState =
            durationUs <= 0 || durationUs == C.TIME_UNSET
                ? PROGRESS_STATE_UNAVAILABLE
                : PROGRESS_STATE_AVAILABLE;
        checkNotNull(player).play();
      }
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
      listener.onAllTracksRegistered();
    }

    @Override
    public void onPlayerError(PlaybackException error) {
      listener.onError(error);
    }
  }
}
