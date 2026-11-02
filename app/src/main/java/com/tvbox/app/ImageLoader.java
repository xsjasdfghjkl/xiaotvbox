package com.tvbox.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.LruCache;
import android.widget.ImageView;

import com.tvbox.core.net.Http;
import com.tvbox.core.net.SecurityGuard;

/**
 * 极简图片加载：内存 LruCache + 异步下载。
 *
 * 之所以不引入 Glide / Picasso：
 *  - 少一个第三方依赖就少一处供应链风险；
 *  - 封面图加载同样走 SecurityGuard，图片地址与外部请求共用一套安全策略。
 */
public final class ImageLoader {

    private static final int CACHE_KB = 8 * 1024;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_KB) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };

    private static final Http HTTP = new Http(SecurityGuard.strictDefault())
            .timeout(6000, 10000)
            .maxBytes(2 * 1024 * 1024);

    private ImageLoader() {
    }

    public static void load(String url, ImageView view) {
        if (view == null) {
            return;
        }
        if (TextUtils.isEmpty(url)) {
            view.setImageBitmap(null);
            return;
        }
        // 用 tag 防止列表复用导致图片错位
        view.setTag(url);
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            if (url.equals(view.getTag())) {
                view.setImageBitmap(hit);
            }
            return;
        }
        view.setImageBitmap(null);
        App.IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Http.Response r = HTTP.get(url);
                    if (!r.ok()) {
                        return;
                    }
                    byte[] data = r.bytes();
                    Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bmp == null) {
                        return;
                    }
                    CACHE.put(url, bmp);
                    final Bitmap finalBmp = bmp;
                    final String finalUrl = url;
                    final ImageView finalView = view;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (finalUrl.equals(finalView.getTag())) {
                                finalView.setImageBitmap(finalBmp);
                            }
                        }
                    });
                } catch (RuntimeException ignored) {
                    // 封面加载失败无关紧要，不影响主流程
                }
            }
        });
    }
}
