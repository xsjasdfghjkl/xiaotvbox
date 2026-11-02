package com.tvbox.app;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tvbox.app.adapter.TextAdapter;
import com.tvbox.core.model.SiteConfig;
import com.tvbox.core.parse.ConfigLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置页：填写/加载内容源配置，并把"被安全策略拒绝的源"明确展示出来。
 *
 * 设计原则：拦截要可见。任何被拒绝的站点都会列出原因，不做静默丢弃。
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText urlInput;
    private TextView status;
    private TextAdapter rejectedAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        urlInput = findViewById(R.id.url);
        status = findViewById(R.id.status);
        RecyclerView rejected = findViewById(R.id.rejected);
        rejected.setLayoutManager(new LinearLayoutManager(this));
        rejectedAdapter = new TextAdapter(null).vertical(true);
        rejected.setAdapter(rejectedAdapter);

        urlInput.setText(App.repo().lastUrl());

        Button load = findViewById(R.id.load);
        load.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadConfig();
            }
        });
        Button reset = findViewById(R.id.reset);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetConfig();
            }
        });

        refresh();
    }

    private void loadConfig() {
        final String url = urlInput.getText() == null ? "" : urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            status.setText("请填写配置地址");
            return;
        }
        status.setText("正在下载并校验配置…");
        Task.run(new Task.Job<ConfigLoader.Config>() {
            @Override
            public ConfigLoader.Config run() {
                return App.repo().loadFromUrl(url);
            }
        }, new Task.Callback<ConfigLoader.Config>() {
            @Override
            public void done(ConfigLoader.Config result, Throwable error) {
                if (error != null) {
                    status.setText("加载失败: " + error.getMessage());
                    return;
                }
                refresh();
            }
        });
    }

    private void resetConfig() {
        Task.run(new Task.Job<ConfigLoader.Config>() {
            @Override
            public ConfigLoader.Config run() {
                return App.repo().loadBuiltIn();
            }
        }, new Task.Callback<ConfigLoader.Config>() {
            @Override
            public void done(ConfigLoader.Config result, Throwable error) {
                if (error != null) {
                    status.setText("恢复失败: " + error.getMessage());
                    return;
                }
                refresh();
            }
        });
    }

    private void refresh() {
        List<SiteConfig> sites = App.repo().sites();
        List<String> rejected = App.repo().rejected();
        StringBuilder sb = new StringBuilder();
        sb.append("已启用内容源 ").append(sites.size()).append(" 个：\n");
        for (SiteConfig s : sites) {
            sb.append("  · ").append(s.name)
                    .append(" [").append(s.type).append("]")
                    .append(s.api.isEmpty() ? "" : "  " + s.api)
                    .append('\n');
        }
        status.setText(sb.toString());
        rejectedAdapter.setData(new ArrayList<String>(rejected));
    }
}
