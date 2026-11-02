package com.tvbox.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tvbox.app.ImageLoader;
import com.tvbox.app.R;
import com.tvbox.core.model.Items;

import java.util.ArrayList;
import java.util.List;

/** 视频卡片网格适配器。 */
public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VH> {

    public interface OnItemClick {
        void onClick(Items.VideoItem item);
    }

    private final List<Items.VideoItem> data = new ArrayList<>();
    private final OnItemClick listener;

    public VideoAdapter(OnItemClick listener) {
        this.listener = listener;
    }

    public void setData(List<Items.VideoItem> list) {
        data.clear();
        if (list != null) {
            data.addAll(list);
        }
        notifyDataSetChanged();
    }

    public void append(List<Items.VideoItem> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        int from = data.size();
        data.addAll(list);
        notifyItemRangeInserted(from, list.size());
    }

    public void clear() {
        data.clear();
        notifyDataSetChanged();
    }

    public List<Items.VideoItem> data() {
        return data;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Items.VideoItem item = data.get(position);
        h.title.setText(item.vodName);
        String sub = item.typeName;
        if (sub == null || sub.isEmpty()) {
            sub = item.siteName;
        }
        h.sub.setText(sub == null ? "" : sub);
        h.remark.setText(item.vodRemarks == null ? "" : item.vodRemarks);
        h.remark.setVisibility(item.vodRemarks == null || item.vodRemarks.isEmpty() ? View.GONE : View.VISIBLE);
        ImageLoader.load(item.vodPic, h.pic);
        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onClick(item);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView pic;
        TextView title;
        TextView sub;
        TextView remark;

        VH(View v) {
            super(v);
            pic = v.findViewById(R.id.pic);
            title = v.findViewById(R.id.title);
            sub = v.findViewById(R.id.sub);
            remark = v.findViewById(R.id.remark);
        }
    }
}
