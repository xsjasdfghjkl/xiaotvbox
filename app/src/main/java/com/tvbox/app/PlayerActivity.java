package com.tvbox.app;

import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.tvbox.core.model.Items;
import com.tvbox.core.model.SiteConfig;
import com.tvbox.core.parse.DeclSpider;

import java.util.HashMap;
import java.util.Map;

/**
 * 播放页。
 *
 * 注意这里的地址来源：即使是详情页传过来的地址，也要先经过 DeclSpider.playerContent()，
 * 由它做二次解析与安全守卫校验（内网地址、非 http(s) 协议都会被拒），
 * 然后再交给 ExoPlayer。播放器永远不会拿到未校验的 URI。
 */
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_SITE = "site_key";
    public static final String EXTRA_FLAG = "flag";
    public static final String EXTRA_PLAY_ID = "play_id";
    public static final String EXTRA_TITLE = "title";

    private PlayerView playerView;
    private ProgressBar loading;
    private TextView message;
    private ExoPlayer player;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.player);
        loading = findViewById(R.id.loading);
        message = findViewById(R.id.message);

        final String siteKey = getIntent().getStringExtra(EXTRA_SITE);
        final String flag = getIntent().getStringExtra(EXTRA_FLAG);
        final String playId = getIntent().getStringExtra(EXTRA_PLAY_ID);
        String t = getIntent().getStringExtra(EXTRA_TITLE);
        setTitle(t == null ? "播放" : t);

        if (siteKey == null || playId == null) {
            fail("缺少播放参数");
            return;
        }

        Task.run(new Task.Job<Items.PlayResult>() {
            @Override
            public Items.PlayResult run() {
                SiteConfig site = App.repo().find(siteKey);
                if (site == null) {
                    throw new RuntimeException("找不到内容源: " + siteKey);
                }
                return new DeclSpider(site, App.repo().http()).playerContent(flag, playId);
            }
        }, new Task.Callback<Items.PlayResult>() {
            @Override
            public void done(Items.PlayResult result, Throwable error) {
                if (error != null) {
                    fail("解析播放地址失败: " + error.getMessage());
                    return;
                }
                if (result == null || !result.ok()) {
                    fail(result == null ? "解析结果为空" : result.message);
                    return;
                }
                startPlay(result);
            }
        });
    }

    private void startPlay(Items.PlayResult pr) {
        loading.setVisibility(View.VISIBLE);
        Map<String, String> headers = new HashMap<>();
        headers.putAll(pr.headers);
        if (!headers.containsKey("User-Agent")) {
            headers.put("User-Agent", "SafeTVBox/1.0");
        }

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(15000);

        MediaItem item = MediaItem.fromUri(pr.url);
        MediaSource source;
        if ("m3u8".equals(pr.format)) {
            source = new HlsMediaSource.Factory(httpFactory).createMediaSource(item);
        } else {
            source = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(item);
        }

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                loading.setVisibility(View.GONE);
                message.setVisibility(View.VISIBLE);
                message.setText("播放出错: " + error.getMessage());
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                    loading.setVisibility(View.GONE);
                }
            }
        });
        player.setMediaSource(source);
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private void fail(String msg) {
        loading.setVisibility(View.GONE);
        message.setVisibility(View.VISIBLE);
        message.setText(msg);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.setPlayWhenReady(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
