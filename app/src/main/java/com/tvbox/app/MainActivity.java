package com.tvbox.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tvbox.app.adapter.TextAdapter;
import com.tvbox.app.adapter.VideoAdapter;
import com.tvbox.core.model.Items;
import com.tvbox.core.model.SiteConfig;
import com.tvbox.core.parse.DeclSpider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 首页：左侧内容源、上方分类、下方内容网格。 */
public class MainActivity extends AppCompatActivity {

    private RecyclerView siteList;
    private RecyclerView tabList;
    private RecyclerView grid;
    private TextView status;

    private final List<SiteConfig> sites = new ArrayList<>();
    private final List<Items.ClassItem> classes = new ArrayList<>();
    private final Map<String, String> extendFilters = new HashMap<>();

    private TextAdapter siteAdapter;
    private TextAdapter tabAdapter;
    private VideoAdapter videoAdapter;

    private SiteConfig currentSite;
    private String currentTypeId = "";
    private int page = 1;
    private boolean loading = false;
    private static final int MAX_PAGE = 10;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        siteList = findViewById(R.id.sites);
        tabList = findViewById(R.id.tabs);
        grid = findViewById(R.id.grid);
        status = findViewById(R.id.status);

        siteList.setLayoutManager(new LinearLayoutManager(this));
        tabList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        grid.setLayoutManager(new GridLayoutManager(this, 6));

        siteAdapter = new TextAdapter(new TextAdapter.OnItemClick() {
            @Override
            public void onClick(int position, String text) {
                if (position >= 0 && position < sites.size()) {
                    selectSite(sites.get(position));
                }
            }
        }).vertical(true);
        siteList.setAdapter(siteAdapter);

        tabAdapter = new TextAdapter(new TextAdapter.OnItemClick() {
            @Override
            public void onClick(int position, String text) {
                if (position >= 0 && position < classes.size()) {
                    currentTypeId = classes.get(position).typeId;
                    page = 1;
                    loadCategory();
                }
            }
        });
        tabList.setAdapter(tabAdapter);

        videoAdapter = new VideoAdapter(new VideoAdapter.OnItemClick() {
            @Override
            public void onClick(Items.VideoItem item) {
                Intent i = new Intent(MainActivity.this, DetailActivity.class);
                i.putExtra(DetailActivity.EXTRA_SITE, item.siteKey);
                i.putExtra(DetailActivity.EXTRA_ID, item.vodId);
                startActivity(i);
            }
        });
        grid.setAdapter(videoAdapter);

        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || loading || page >= MAX_PAGE) {
                    return;
                }
                if (!rv.canScrollVertically(1)) {
                    page++;
                    loadCategory();
                }
            }
        });

        findViewById(R.id.btn_search).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SearchActivity.class));
            }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        loadConfig();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置页返回时，配置可能已更换，重新加载一次。
        // 这里必须包 try-catch：sites() 可能触发首次读 assets + 解析配置，
        // 一旦抛异常就是主线程崩溃（表现为"点开闪一下就没"），必须兜住。
        try {
            List<SiteConfig> fresh = App.repo().sites();
            if (fresh.size() != sites.size()) {
                loadConfig();
            }
        } catch (Throwable t) {
            CrashLogger.log("onResume 重新加载配置失败", t);
        }
    }

    private void loadConfig() {
        status.setText(R.string.loading);
        Task.run(new Task.Job<List<SiteConfig>>() {
            @Override
            public List<SiteConfig> run() {
                return App.repo().sites();
            }
        }, new Task.Callback<List<SiteConfig>>() {
            @Override
            public void done(List<SiteConfig> result, Throwable error) {
                if (error != null) {
                    status.setText("配置加载失败: " + error.getMessage());
                    return;
                }
                sites.clear();
                if (result != null) {
                    sites.addAll(result);
                }
                List<String> names = new ArrayList<>();
                for (SiteConfig s : sites) {
                    names.add(s.name);
                }
                siteAdapter.setData(names);
                if (sites.isEmpty()) {
                    status.setText("没有可用内容源，请在配置页添加");
                    return;
                }
                List<String> rejected = App.repo().rejected();
                status.setText(rejected.isEmpty()
                        ? "已加载 " + sites.size() + " 个内容源"
                        : "已加载 " + sites.size() + " 个内容源；" + rejected.size() + " 个被安全策略拒绝（见配置页）");
                selectSite(sites.get(0));
            }
        });
    }

    private void selectSite(SiteConfig site) {
        currentSite = site;
        page = 1;
        currentTypeId = "";
        videoAdapter.clear();
        status.setText("正在加载 " + site.name + " …");
        Task.run(new Task.Job<Items.HomeResult>() {
            @Override
            public Items.HomeResult run() {
                return new DeclSpider(currentSite, App.repo().http()).homeContent();
            }
        }, new Task.Callback<Items.HomeResult>() {
            @Override
            public void done(Items.HomeResult result, Throwable error) {
                if (error != null) {
                    status.setText("加载失败: " + error.getMessage());
                    classes.clear();
                    tabAdapter.setData(new ArrayList<String>());
                    return;
                }
                classes.clear();
                classes.addAll(result.classes);
                List<String> names = new ArrayList<>();
                for (Items.ClassItem c : classes) {
                    names.add(c.typeName);
                }
                tabAdapter.setData(names);
                videoAdapter.setData(result.list);
                if (!classes.isEmpty()) {
                    currentTypeId = classes.get(0).typeId;
                }
                status.setText(site.name + "：共 " + videoAdapter.data().size() + " 条");
            }
        });
    }

    private void loadCategory() {
        if (currentSite == null || loading) {
            return;
        }
        loading = true;
        final int wantPage = page;
        final boolean append = wantPage > 1;
        status.setText("加载中… 第 " + wantPage + " 页");
        Task.run(new Task.Job<List<Items.VideoItem>>() {
            @Override
            public List<Items.VideoItem> run() {
                return new DeclSpider(currentSite, App.repo().http())
                        .categoryContent(currentTypeId, wantPage, extendFilters);
            }
        }, new Task.Callback<List<Items.VideoItem>>() {
            @Override
            public void done(List<Items.VideoItem> result, Throwable error) {
                loading = false;
                if (error != null) {
                    status.setText("加载失败: " + error.getMessage());
                    return;
                }
                if (append) {
                    videoAdapter.append(result);
                } else {
                    videoAdapter.setData(result);
                }
                status.setText(currentSite.name + "：共 " + videoAdapter.data().size() + " 条");
            }
        });
    }
}
