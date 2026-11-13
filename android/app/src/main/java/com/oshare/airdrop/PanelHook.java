package com.oshare.airdrop;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * 在一加互传的分享面板里加一行「发送到 iPhone」。
 *
 * <p># 钩在哪
 *
 * <p>{@code ShareActivity.onCreate} 之后拿到那个 Activity 和它的分享内容，
 * {@code Dialog.show} 之后确认这个对话框属于它，再往设备列表里加一行。选 {@code Dialog.show}
 * 而不是 {@code ShareActivity} 里某个私有方法，是因为面板是那个 Activity 建的一个底部弹窗：
 * 私有方法的名字随版本变，{@code show} 不变。
 *
 * <p>{@code IOSShareActivity} 不用单独钩——它把 {@code onCreate} 直接交给父类
 * {@code ShareActivity}，钩父类就同时覆盖了「分享给 iPhone」那条入口。
 *
 * <p># 为什么用包装 adapter，而不是往列表数据里塞一个假设备
 *
 * <p>那条路走不通：列表里每一项都会被 {@code DeviceAdapter} 当成它自己的设备对象去绑定、
 * 去点击，塞进去的对象要么绑不出来，要么点下去走到互传自己的发送流程里。包一层
 * {@link Row}，原 adapter 一个字节都不用改，我们只负责多出来的那一个位置。
 *
 * <p># 少了这一行会怎样
 *
 * <p>什么都不会坏：本应用自己声明了 {@code SEND}/{@code SEND_MULTIPLE} 过滤器，系统分享
 * 面板里一直有「发送到 iPhone」这一项。这一行只是把它挪到人已经在看的地方。
 */
final class PanelHook {

    private static final String TAG = "OPAirDrop";

    /** 面板的设备列表，按 id 名找——id 是资源，编译期拿不到。 */
    private static final String DEVICE_LIST = "recycler_view_device";

    private static Entry module;

    /** 当前这个面板。弱引用：面板关掉之后不该被一个钩子钉在内存里。 */
    private static volatile WeakReference<Activity> panel = new WeakReference<>(null);

    /** 这一次分享的内容。授权只在面板活着的这段时间里有效，所以复制一份引用就够。 */
    private static volatile ClipData clip;

    private PanelHook() {}

    static void install(Entry owner, ClassLoader loader) throws Exception {
        module = owner;

        Class<?> share = loader.loadClass("com.oplus.oshare.ui.ShareActivity");
        Method onCreate = share.getDeclaredMethod("onCreate", Bundle.class);
        onCreate.setAccessible(true);
        owner.hook(onCreate).intercept(chain -> {
            Object result = chain.proceed();
            capture((Activity) chain.getThisObject());
            return result;
        });

        // 重进面板时不会走 onCreate（singleTop 之类），新 Intent 走的是这个。
        try {
            Method onNewIntent = share.getDeclaredMethod("onNewIntent", Intent.class);
            onNewIntent.setAccessible(true);
            owner.hook(onNewIntent).intercept(chain -> {
                Object result = chain.proceed();
                capture((Activity) chain.getThisObject());
                return result;
            });
        } catch (NoSuchMethodException none) {
            // 没有这个方法就只靠 onCreate，不影响功能。
        }

        Method show = Dialog.class.getDeclaredMethod("show");
        show.setAccessible(true);
        owner.hook(show).intercept(chain -> {
            Object result = chain.proceed();
            try {
                inject(chain.getThisObject());
            } catch (Throwable failed) {
                module.log(android.util.Log.WARN, TAG, "share panel row not added", failed);
            }
            return result;
        });

        module.log(android.util.Log.INFO, TAG, "share panel hook installed");
    }

    /** 记下这是哪一个面板、要发的是什么。 */
    private static void capture(Activity activity) {
        if (activity == null) {
            return;
        }
        Intent intent = activity.getIntent();
        ClipData held = intent == null ? null : intent.getClipData();
        if (held == null && intent != null) {
            Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            if (single != null) {
                held = ClipData.newRawUri("", single);
            }
        }
        if (held != null && held.getItemCount() > 0) {
            clip = held;
        }
        panel = new WeakReference<>(activity);
    }

    /** 如果是那个面板的对话框，给它的设备列表包一层。 */
    private static void inject(Object shown) {
        if (!(shown instanceof Dialog)) {
            return;
        }
        Activity owner = panel.get();
        if (owner == null || clip == null) {
            return;
        }
        Dialog dialog = (Dialog) shown;
        if (dialog.getWindow() == null || dialog.getWindow().getDecorView() == null) {
            return;
        }
        ViewGroup decor = (ViewGroup) dialog.getWindow().getDecorView();
        RecyclerView list = find(decor);
        if (list == null) {
            return;
        }
        RecyclerView.Adapter<?> current = list.getAdapter();
        if (current == null || current instanceof Row) {
            return;
        }
        list.setAdapter(new Row(current, owner));
    }

    /** 按 id 名找设备列表，找不到就算了——那是别的对话框。 */
    private static RecyclerView find(ViewGroup root) {
        int id = root.getResources().getIdentifier(
                DEVICE_LIST, "id", root.getContext().getPackageName());
        if (id == 0) {
            return null;
        }
        android.view.View found = root.findViewById(id);
        return found instanceof RecyclerView ? (RecyclerView) found : null;
    }

    /**
     * 原来的列表，加上最后那一行。
     *
     * <p>数据变化一律 {@code notifyDataSetChanged}：原列表一次传输里只改几次，而这层如果去
     * 翻译每一个 range 通知，就要维护一套偏移量，翻译错一次的后果是列表错位——比一次全量刷新
     * 贵得多。位置 0 到最后一项之前都是原 adapter 的，多出来的那一项在最末。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class Row extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int OWN_TYPE = Integer.MIN_VALUE + 7;

        private final RecyclerView.Adapter inner;
        private final Activity host;

        private final RecyclerView.AdapterDataObserver observer = new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                notifyDataSetChanged();
            }

            @Override
            public void onItemRangeChanged(int start, int count) {
                notifyDataSetChanged();
            }

            @Override
            public void onItemRangeInserted(int start, int count) {
                notifyDataSetChanged();
            }

            @Override
            public void onItemRangeRemoved(int start, int count) {
                notifyDataSetChanged();
            }
        };

        Row(RecyclerView.Adapter inner, Activity host) {
            this.inner = inner;
            this.host = host;
            inner.registerAdapterDataObserver(observer);
        }

        @Override
        public int getItemCount() {
            return inner.getItemCount() + 1;
        }

        @Override
        public int getItemViewType(int position) {
            return position >= inner.getItemCount() ? OWN_TYPE : inner.getItemViewType(position);
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == OWN_TYPE) {
                return new RecyclerView.ViewHolder(row(parent.getContext())) { };
            }
            return inner.onCreateViewHolder(parent, viewType);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position < inner.getItemCount()) {
                inner.onBindViewHolder(holder, position);
                return;
            }
            holder.itemView.setOnClickListener(view -> send());
        }

        @Override
        public void onViewRecycled(RecyclerView.ViewHolder holder) {
            if (holder.getBindingAdapterPosition() >= 0) {
                inner.onViewRecycled(holder);
            }
        }

        @Override
        public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
            try {
                inner.unregisterAdapterDataObserver(observer);
            } catch (Throwable alreadyGone) {
                // 原 adapter 已经不认这个观察者了。
            }
        }

        /** 那一行本身：标题、一句说明，样式跟着面板所在的主题走。 */
        private LinearLayout row(Context context) {
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            int side = dp(context, 18);
            box.setPadding(side, dp(context, 14), side, dp(context, 14));
            box.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TypedValue ripple = new TypedValue();
            if (context.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, ripple, true)) {
                box.setBackgroundResource(ripple.resourceId);
            }

            TextView title = new TextView(context);
            title.setText("发送到 iPhone");
            title.setTextSize(16f);
            title.setTextColor(textColor(context));
            box.addView(title);

            TextView detail = new TextView(context);
            detail.setText("用 AirDrop 直接发，不经过互传");
            detail.setTextSize(13f);
            detail.setTextColor(dim(context));
            detail.setPadding(0, dp(context, 3), 0, 0);
            box.addView(detail);
            return box;
        }

        /** 把这一份分享交给自己的发送页。 */
        private void send() {
            ClipData data = clip;
            if (data == null || data.getItemCount() == 0) {
                return;
            }
            Intent intent = new Intent();
            intent.setClass(host, SendActivity.class);
            intent.setClipData(data);
            // 授权是给这一份 URI 的，不是给这个应用的：不带这个标志，发送页打开文件时会
            // 拿到 SecurityException，而错误发生在复制文件那一刻，看起来像文件坏了。
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (data.getItemCount() == 1) {
                intent.setAction(Intent.ACTION_SEND);
                intent.putExtra(Intent.EXTRA_STREAM, data.getItemAt(0).getUri());
            } else {
                intent.setAction(Intent.ACTION_SEND_MULTIPLE);
                ArrayList<Uri> uris = new ArrayList<>();
                for (int index = 0; index < data.getItemCount(); index++) {
                    uris.add(data.getItemAt(index).getUri());
                }
                intent.putExtra(Intent.EXTRA_STREAM, uris);
            }
            host.startActivity(intent);
        }

        private static int textColor(Context context) {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
                return value.data;
            }
            return Color.WHITE;
        }

        /** 次要文字：主文字色降一半 alpha。主题里没有单独给这个颜色，而一个更暗的常量在浅色下会看不见。 */
        private static int dim(Context context) {
            int base = textColor(context);
            return Color.argb(0x99, Color.red(base), Color.green(base), Color.blue(base));
        }

        private static int dp(Context context, float value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }
}
