package com.tvbox.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tvbox.app.R;

import java.util.ArrayList;
import java.util.List;

/** 通用文字 chip 适配器：用于内容源列表、分类 tab、播放源、选集。 */
public class TextAdapter extends RecyclerView.Adapter<TextAdapter.VH> {

    public interface OnItemClick {
        void onClick(int position, String text);
    }

    private final List<String> data = new ArrayList<>();
    private final OnItemClick listener;
    private int selected = 0;
    private boolean vertical = false;

    public TextAdapter(OnItemClick listener) {
        this.listener = listener;
    }

    public TextAdapter vertical(boolean v) {
        this.vertical = v;
        return this;
    }

    public void setData(List<String> list) {
        data.clear();
        if (list != null) {
            data.addAll(list);
        }
        selected = 0;
        notifyDataSetChanged();
    }

    public void setSelected(int pos) {
        int old = selected;
        selected = pos;
        if (old >= 0 && old < data.size()) {
            notifyItemChanged(old);
        }
        if (pos >= 0 && pos < data.size()) {
            notifyItemChanged(pos);
        }
    }

    public int selected() {
        return selected;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_text, parent, false);
        if (vertical) {
            v.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        String text = data.get(position);
        h.text.setText(text);
        h.itemView.setSelected(position == selected);
        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSelected(h.getAdapterPosition());
                if (listener != null) {
                    listener.onClick(h.getAdapterPosition(), text);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView text;

        VH(View v) {
            super(v);
            text = (TextView) v;
        }
    }
}
