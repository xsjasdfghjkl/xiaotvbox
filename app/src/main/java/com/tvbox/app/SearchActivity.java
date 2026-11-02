package com.tvbox.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tvbox.app.adapter.VideoAdapter;
import com.tvbox.core.model.Items;
import com.tvbox.core.model.SiteConfig;
import com.tvbox.core.parse.DeclSpider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 搜索页：并发查询所有支持搜索的内容源，结果汇总展示。 */
public class SearchActivity extends AppCompatActivity {

    private EditText input;
    private TextView status;
    private VideoAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        input = findViewById(R.id.input);
        status = findViewById(R.id.status);
        RecyclerView grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, 6));
        adapter = new VideoAdapter(new VideoAdapter.OnItemClick() {
            @Override
            public void onClick(Items.VideoItem item) {
                Intent i = new Intent(SearchActivity.this, DetailActivity.class);
                i.putExtra(DetailActivity.EXTRA_SITE, item.siteKey);
                i.putExtra(DetailActivity.EXTRA_ID, item.vodId);
                startActivity(i);
            }
        });
        grid.setAdapter(adapter);

        Button go = findViewById(R.id.go);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch();
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    doSearch();
                    return true;
                }
                return false;
            }
        });
    }

    private void doSearch() {
        final String kw = input.getText() == null ? "" : input.getText().toString().trim();
        if (kw.isEmpty()) {
            status.setText("请输入关键词");
            return;
        }
        status.setText("搜索中…");
        adapter.clear();
        Task.run(new Task.Job<List<Items.VideoItem>>() {
            @Override
            public List<Items.VideoItem> run() throws Exception {
                List<SiteConfig> sites = App.repo().sites();
                final List<Items.VideoItem> all = Collections.synchronizedList(new ArrayList<Items.VideoItem>());
                final CountDownLatch latch = new CountDownLatch(sites.size());
                for (final SiteConfig s : sites) {
                    App.IO.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (!s.searchable) {
                                    return;
                                }
                                List<Items.VideoItem> r = new DeclSpider(s, App.repo().http())
                                        .searchContent(kw, false);
                                all.addAll(r);
                            } catch (RuntimeException ignored) {
                                // 单个源失败不影响其它源
                            } finally {
                                latch.countDown();
                            }
                        }
                    });
                }
                latch.await(20, TimeUnit.SECONDS);
                return new ArrayList<Items.VideoItem>(all);
            }
        }, new Task.Callback<List<Items.VideoItem>>() {
            @Override
            public void done(List<Items.VideoItem> result, Throwable error) {
                if (error != null) {
                    status.setText("搜索失败: " + error.getMessage());
                    return;
                }
                adapter.setData(result);
                status.setText("找到 " + adapter.data().size() + " 条结果");
            }
        });
    }
}
