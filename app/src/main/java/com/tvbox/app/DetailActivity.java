package com.tvbox.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tvbox.app.adapter.TextAdapter;
import com.tvbox.core.model.Items;
import com.tvbox.core.model.SiteConfig;
import com.tvbox.core.parse.DeclSpider;

import java.util.ArrayList;
import java.util.List;

/** 详情页：简介 + 播放源 + 选集。 */
public class DetailActivity extends AppCompatActivity {

    public static final String EXTRA_SITE = "site_key";
    public static final String EXTRA_ID = "vod_id";

    private ImageView pic;
    private TextView title;
    private TextView meta;
    private TextView desc;
    private TextView status;
    private RecyclerView sources;
    private RecyclerView episodes;

    private Items.VodDetail detail;
    private String siteKey;
    private int sourceIndex = 0;

    private final List<String[]> episodeList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        pic = findViewById(R.id.pic);
        title = findViewById(R.id.title);
        meta = findViewById(R.id.meta);
        desc = findViewById(R.id.desc);
        status = findViewById(R.id.status);
        sources = findViewById(R.id.sources);
        episodes = findViewById(R.id.episodes);

        sources.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        episodes.setLayoutManager(new GridLayoutManager(this, 8));

        siteKey = getIntent().getStringExtra(EXTRA_SITE);
        final String id = getIntent().getStringExtra(EXTRA_ID);
        if (siteKey == null || id == null) {
            status.setText("缺少参数");
            return;
        }

        status.setText(R.string.loading);
        Task.run(new Task.Job<Items.VodDetail>() {
            @Override
            public Items.VodDetail run() {
                SiteConfig site = App.repo().find(siteKey);
                if (site == null) {
                    throw new RuntimeException("找不到内容源: " + siteKey);
                }
                return new DeclSpider(site, App.repo().http()).detailContent(id);
            }
        }, new Task.Callback<Items.VodDetail>() {
            @Override
            public void done(Items.VodDetail result, Throwable error) {
                if (error != null) {
                    status.setText("详情加载失败: " + error.getMessage());
                    return;
                }
                detail = result;
                render();
            }
        });
    }

    private void render() {
        if (detail == null) {
            return;
        }
        title.setText(detail.vodName);
        StringBuilder sb = new StringBuilder();
        if (!detail.typeName.isEmpty()) {
            sb.append(detail.typeName).append("  ");
        }
        if (!detail.vodYear.isEmpty()) {
            sb.append(detail.vodYear).append("  ");
        }
        if (!detail.vodArea.isEmpty()) {
            sb.append(detail.vodArea).append("  ");
        }
        if (!detail.vodRemarks.isEmpty()) {
            sb.append(detail.vodRemarks);
        }
        meta.setText(sb.toString());
        desc.setText(detail.vodContent);
        ImageLoader.load(detail.vodPic, pic);
        status.setText("");

        List<String> from = new ArrayList<>();
        for (int i = 0; i < detail.playFrom.size(); i++) {
            from.add(detail.playFrom.get(i));
        }
        TextAdapter srcAdapter = new TextAdapter(new TextAdapter.OnItemClick() {
            @Override
            public void onClick(int position, String text) {
                sourceIndex = position;
                showEpisodes();
            }
        });
        sources.setAdapter(srcAdapter);
        srcAdapter.setData(from);
        showEpisodes();
    }

    private void showEpisodes() {
        episodeList.clear();
        if (detail == null || sourceIndex >= detail.playUrl.size()) {
            return;
        }
        episodeList.addAll(parseEpisodes(detail.playUrl.get(sourceIndex)));
        final String flag = sourceIndex < detail.playFrom.size() ? detail.playFrom.get(sourceIndex) : "";

        List<String> names = new ArrayList<>();
        for (String[] e : episodeList) {
            names.add(e[0]);
        }
        TextAdapter epAdapter = new TextAdapter(new TextAdapter.OnItemClick() {
            @Override
            public void onClick(int position, String text) {
                if (position < 0 || position >= episodeList.size()) {
                    return;
                }
                Intent i = new Intent(DetailActivity.this, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_SITE, siteKey);
                i.putExtra(PlayerActivity.EXTRA_FLAG, flag);
                i.putExtra(PlayerActivity.EXTRA_PLAY_ID, episodeList.get(position)[1]);
                i.putExtra(PlayerActivity.EXTRA_TITLE, detail.vodName + " - " + episodeList.get(position)[0]);
                startActivity(i);
            }
        });
        episodes.setAdapter(epAdapter);
        epAdapter.setData(names);
    }

    /** 把 "第1集$url1#第2集$url2" 拆成 [名称, 地址] 数组。 */
    static List<String[]> parseEpisodes(String block) {
        List<String[]> out = new ArrayList<>();
        if (block == null || block.isEmpty()) {
            return out;
        }
        String[] parts = block.split("#");
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            int at = p.indexOf('$');
            if (at < 0) {
                out.add(new String[]{"播放", p});
            } else {
                out.add(new String[]{p.substring(0, at), p.substring(at + 1)});
            }
        }
        return out;
    }
}
