package com.ryanheise.just_audio;

import android.os.Handler;
import android.media.audiofx.LoudnessEnhancer;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.AudioComponent;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioListener;
import com.google.android.exoplayer2.source.ClippingMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.upstream.cache.CacheDataSourceFactory;
import com.google.android.exoplayer2.database.ExoDatabaseProvider;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import android.content.Context;
import android.net.Uri;
import java.util.List;
import java.util.function.LongConsumer;
import java.io.File;
import java.io.*;

class VolumeBooster implements AudioListener {
	private boolean enabled = false;
	private Context context;
	private int gain = 3000;
	private LoudnessEnhancer booster;

	public void setEnabled(boolean enabled){
		this.enabled = enabled;
		if(booster != null){
			booster.setEnabled(enabled);
	  }
   }

   public void setGain(int gain){
	   this.gain = gain;
	   if(booster != null){
			booster.setTargetGain(gain);
	  }
   }

   public void dispose(){
	   if(booster != null){
		   booster.release();
	   }
   }

	@Override 
	public void onAudioSessionId(int audioSessionId) {
       if(booster != null){
		   booster.release();
	   }
	   booster = new LoudnessEnhancer(audioSessionId);
	   booster.setTargetGain(this.gain);
	   booster.setEnabled(this.enabled);
   }	
}

public class AudioPlayer implements MethodCallHandler, Player.EventListener {
	private final Registrar registrar;
	private final Context context;
	private final MethodChannel methodChannel;
	private final EventChannel eventChannel;
	private EventSink eventSink;
	private VolumeBooster volumeBooster;

	private final String id;
	private volatile PlaybackState state;
	private long updateTime;
	private long updatePosition;
	private long bufferedPosition;
	private long duration;
	private Long start;
	private Long end;
	private float volume = 1.0f;
	private float speed = 1.0f;
	private float pitch = 1.0f;
	private boolean skipSlience = false;
	private boolean boostVolume = false;
	private int gain = 3000;
	private Long seekPos;
	private Result prepareResult;
	private Result seekResult;
	private boolean seekProcessed;
	private boolean buffering;
	private String playbackError;
	private boolean justConnected;
	private MediaSource mediaSource;

	
	private final SimpleExoPlayer player;
	private final Handler handler = new Handler();
	private final Runnable bufferWatcher = new Runnable() {
		@Override
		public void run() {
			long newBufferedPosition = Math.min(duration, player.getBufferedPosition());
			if (newBufferedPosition != bufferedPosition) {
				bufferedPosition = newBufferedPosition;
				broadcastPlaybackEvent();
			}
			if (duration > 0 && newBufferedPosition >= duration)
				return;
			if (buffering) {
				handler.postDelayed(this, 200);
			} else if (state == PlaybackState.playing) {
				handler.postDelayed(this, 500);
			} else if (state == PlaybackState.paused) {
				handler.postDelayed(this, 1000);
			} else if (justConnected) {
				handler.postDelayed(this, 1000);
			}
		}
	};

	public AudioPlayer(final Registrar registrar, final String id) {
		this.registrar = registrar;
		this.context = registrar.activeContext();
		this.id = id;
		methodChannel = new MethodChannel(registrar.messenger(), "com.ryanheise.just_audio.methods." + id);
		methodChannel.setMethodCallHandler(this);
		eventChannel = new EventChannel(registrar.messenger(), "com.ryanheise.just_audio.events." + id);
		eventChannel.setStreamHandler(new EventChannel.StreamHandler() {
			@Override
			public void onListen(final Object arguments, final EventSink eventSink) {
				AudioPlayer.this.eventSink = eventSink;
			}

			@Override
			public void onCancel(final Object arguments) {
				eventSink = null;
			}
		});
		state = PlaybackState.connecting;
		player = new SimpleExoPlayer.Builder(context).build();
		player.addListener(this);
		try { 
			volumeBooster = new VolumeBooster();
			player.addAudioListener(volumeBooster);
		}
		catch(Exception e){
			e.printStackTrace();
			volumeBooster = null;
		}
	}

	private void startWatchingBuffer() {
		handler.removeCallbacks(bufferWatcher);
		handler.post(bufferWatcher);
	}

	@Override
	public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
		switch (playbackState) {
			case Player.STATE_READY:
				if (prepareResult != null) {
					duration = player.getDuration();
					justConnected = true;
					prepareResult.success(duration);
					prepareResult = null;
					transition(PlaybackState.stopped);
				}
				if (seekProcessed) {
					completeSeek();
				}
				break;
			case Player.STATE_ENDED:
				if (state != PlaybackState.completed) {
					transition(PlaybackState.completed);
				}
				break;

			case Player.STATE_IDLE:
				if (player.getPlaybackError() != null) {
					playbackError = player.getPlaybackError().getLocalizedMessage();
					transition(PlaybackState.paused);
				}
				break;
		}
		final boolean buffering = playbackState == Player.STATE_BUFFERING;
		// don't notify buffering if (buffering && state == stopped)
		final boolean notifyBuffering = !buffering || state != PlaybackState.stopped;
		if (notifyBuffering && (buffering != this.buffering)) {
			this.buffering = buffering;
			broadcastPlaybackEvent();
			if (buffering) {
				startWatchingBuffer();
			}
		}
	}

	@Override
	public void onSeekProcessed() {
		if (seekResult != null) {
			seekProcessed = true;
			if (player.getPlaybackState() == Player.STATE_READY) {
				completeSeek();
			}
		}
	}

	private void completeSeek() {
		seekProcessed = false;
		seekPos = null;
		seekResult.success(null);
		seekResult = null;
	}

	@Override
	public void onMethodCall(final MethodCall call, final Result result) {
		final List<?> args = (List<?>) call.arguments;
		try {
			switch (call.method) {
				case "setUrl":
					Object cacheMax = args.get(1);
					if (cacheMax != null && cacheMax instanceof Integer) {
						cacheMax = new Long((Integer) cacheMax);
					}
					setUrl((String) args.get(0), (Long) cacheMax, result);
					break;
				case "setClip":
					Object start = args.get(0);
					if (start != null && start instanceof Integer) {
						start = new Long((Integer) start);
					}
					Object end = args.get(1);
					if (end != null && end instanceof Integer) {
						end = new Long((Integer) end);
					}
					setClip((Long) start, (Long) end, result);
					break;
				case "play":
					play();
					result.success(null);
					break;
				case "pause":
					pause();
					result.success(null);
					break;
				case "stop":
					stop(result);
					break;
				case "setVolume":
					setVolume((float) ((double) ((Double) args.get(0))));
					result.success(null);
					break;
				case "setSpeed":
					setSpeed((float) ((double) ((Double) args.get(0))));
					result.success(null);
					break;
				case "setPitch":
					setPitch((float) ((double) ((Double) args.get(0))));
					result.success(null);
					break;
				case "setSkipSilence":
					setSkipSilence((boolean) ((Boolean) args.get(0)));
					result.success(null);
					break;
				case "setBoostVolume":
					try{setBoostVolume((boolean) ((Boolean) args.get(0)), (int) ((Integer) args.get(1)));
					result.success(null);}
					catch(RuntimeException e){
						result.notImplemented();
					}
					break;
				case "seek":
					Object position = args.get(0);
					if (position instanceof Integer) {
						seek((Integer) position, result);
					} else {
						seek((Long) position, result);
					}
					break;
				case "dispose":
					dispose();
					result.success(null);
					break;
				default:
					result.notImplemented();
					break;
			}
		} catch (IllegalStateException e) {
			e.printStackTrace();
			result.error("Illegal state: " + e.getMessage(), null, null);
		} catch (Exception e) {
			e.printStackTrace();
			result.error("Error: " + e, null, null);
		}
	}

	private void broadcastPlaybackEvent() {
		final ArrayList<Object> event = new ArrayList<Object>();
		event.add(state.ordinal());
		event.add(buffering);
		event.add(updatePosition = getCurrentPosition());
		event.add(updateTime = System.currentTimeMillis());
		event.add(Math.max(updatePosition, bufferedPosition));
		event.add(playbackError);
		if(eventSink != null)
			eventSink.success(event);
	}

	private long getCurrentPosition() {
		if (state == PlaybackState.none || state == PlaybackState.connecting) {
			return 0;
		} else if (seekPos != null) {
			return seekPos;
		} else {
			return player.getCurrentPosition();
		}
	}

	private void transition(final PlaybackState newState) {
		final PlaybackState oldState = state;
		state = newState;
		broadcastPlaybackEvent();
	}

	public void setUrl(final String url, final long cacheMax, final Result result) throws IOException {
		justConnected = false;
		playbackError = null;
		abortExistingConnection();
		prepareResult = result;
		transition(PlaybackState.connecting);
		String userAgent = Util.getUserAgent(context, "just_audio");
		DataSource.Factory httpDataSourceFactory = new DefaultHttpDataSourceFactory(userAgent,
				DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS, DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS,
				true);
		DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(context, httpDataSourceFactory);
		DataSource.Factory cacheDataSourceFactory = new CacheDataSourceFactory(AudioCache.getInstance(context, cacheMax), dataSourceFactory);
		Uri uri = Uri.parse(url);
		if (uri.getPath().toLowerCase().endsWith(".mpd")) {
			mediaSource = new DashMediaSource.Factory(cacheDataSourceFactory).createMediaSource(uri);
		} else if (uri.getPath().toLowerCase().endsWith(".m3u8")) {
			mediaSource = new HlsMediaSource.Factory(cacheDataSourceFactory).createMediaSource(uri);
		} else {
			mediaSource = new ProgressiveMediaSource.Factory(cacheDataSourceFactory).createMediaSource(uri);
		}
		player.prepare(mediaSource);
	}

	public void setClip(final Long start, final Long end, final Result result) {
		if (state == PlaybackState.none) {
			throw new IllegalStateException("Cannot call setClip from none state");
		}
		abortExistingConnection();
		this.start = start;
		this.end = end;
		prepareResult = result;
		if (start != null || end != null) {
			player.prepare(new ClippingMediaSource(mediaSource, (start != null ? start : 0) * 1000L,
					(end != null ? end : C.TIME_END_OF_SOURCE) * 1000L));
		} else {
			player.prepare(mediaSource);
		}
	}

	public void play() {
		switch (state) {
			case playing:
				break;
			case stopped:
			case completed:
			case paused:
				if (playbackError != null) {
					player.retry();
					playbackError = null;
				}
				;
				justConnected = false;
				transition(PlaybackState.playing);
				startWatchingBuffer();
				player.setPlayWhenReady(true);
				break;
			default:
				throw new IllegalStateException("Cannot call play from connecting/none states (" + state + ")");
		}
	}

	public void pause() {
		switch (state) {
			case paused:
				break;
			case playing:
				player.setPlayWhenReady(false);
				transition(PlaybackState.paused);
				break;
			default:
				throw new IllegalStateException(
						"Can call pause only from playing and buffering states (" + state + ")");
		}
	}

	public void stop(final Result result) {
		switch (state) {
			case stopped:
				result.success(null);
				break;
			case connecting:
				abortExistingConnection();
				buffering = false;
				transition(PlaybackState.stopped);
				result.success(null);
				break;
			case completed:
			case playing:
			case paused:
				if (playbackError != null) {
					abortExistingConnection();
					buffering = false;
				}
				abortSeek();
				player.setPlayWhenReady(false);
				transition(PlaybackState.stopped);
				player.seekTo(0L);
				result.success(null);
				break;
			default:
				throw new IllegalStateException("Cannot call stop from none state");
		}
	}

	public void setVolume(final float volume) {
		this.volume = volume;
		player.setVolume(volume);
	}

	public void setSpeed(final float speed) {
		this.speed = speed;
		player.setPlaybackParameters(new PlaybackParameters(speed, pitch, skipSlience));
		broadcastPlaybackEvent();
	}

	public void setSkipSilence(final boolean skipSlience){
		this.skipSlience = skipSlience;
		player.setPlaybackParameters(new PlaybackParameters(speed, pitch, skipSlience));
		broadcastPlaybackEvent();
	}

	public void setPitch(final float pitch) {
		this.pitch = pitch;
		player.setPlaybackParameters(new PlaybackParameters(speed, pitch, skipSlience));
		broadcastPlaybackEvent();
	}

	public void setBoostVolume(final boolean enabled, final int gain){
		this.boostVolume = enabled;
		this.gain = gain;
		if(volumeBooster != null)
		{volumeBooster.setEnabled(enabled);
		volumeBooster.setGain(gain);}
	}


	public void seek(final long position, final Result result) {
		if (state == PlaybackState.none || state == PlaybackState.connecting) {
			throw new IllegalStateException("Cannot call seek from none none/connecting states");
		}
		abortSeek();
		seekPos = position;
		seekResult = result;
		seekProcessed = false;
		player.seekTo(position);
	}

	public void dispose() {
		player.release();
		if(volumeBooster!=null)
		{volumeBooster.dispose();}
		buffering = false;
		transition(PlaybackState.none);
	}

	private void abortSeek() {
		if (seekResult != null) {
			seekResult.success(null);
			seekResult = null;
			seekPos = null;
			seekProcessed = false;
		}
	}

	private void abortExistingConnection() {
		if (prepareResult != null) {
			prepareResult.success(null);
			prepareResult = null;
		}
	}

	enum PlaybackState {
		none, stopped, paused, playing, connecting, completed
	}
}
