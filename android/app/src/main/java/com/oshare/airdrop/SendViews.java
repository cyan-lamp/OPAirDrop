package com.oshare.airdrop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.function.BooleanSupplier;

/**
 * 发送页的两颗自绘视图（评审拆分路线：SendPanel 的一部分，从 {@code SendActivity} 的
 * 内部类切出来）。
 *
 * <p>它们只读三样东西——明暗、密度、accent 色——全部在构造时给定，不再翻 Activity 的
 * 常量表。Radar 的「拖动期间不自转」用 {@link BooleanSupplier} 实时读 Activity 的拖动
 * 标志：拖动时面板是一个硬件层，任何一次 invalidate 都要把整块 1440×3168 的纹理重画一遍，
 * 停掉它，拖动的每一帧就只剩「把已有纹理挪个位置」。
 */
final class SendViews {

    private SendViews() {
    }

    /** 圆头像：手机图形 + 一圈进度。 */
    static final class Nick extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float density;
        private float progress;

        Nick(Context host, boolean light, float density, int accent) {
            super(host);
            this.density = density;
            fill.setColor(light ? Color.WHITE : 0xFF2A2D33);
            ink.setColor(light ? Color.BLACK : Color.WHITE);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(Ui.dp(density, 2.2f));
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            ring.setColor(accent);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(density, 3));
            ring.setStrokeCap(Paint.Cap.ROUND);
        }

        void progress(float value) {
            float next = Math.max(0f, Math.min(1f, value));
            if (next == progress) {
                return;
            }
            progress = next;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float radius = Math.min(w, h) / 2f - Ui.dp(density, 4);
            float cx = w / 2f;
            float cy = h / 2f;

            fill.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, radius, fill);

            // 手机图形：窄、高、圆角大，比例照参照图。
            float phoneW = radius * 0.60f;
            float phoneH = radius * 0.96f;
            box.set(cx - phoneW / 2f, cy - phoneH / 2f, cx + phoneW / 2f, cy + phoneH / 2f);
            float corner = phoneW * 0.28f;
            canvas.drawRoundRect(box, corner, corner, ink);
            canvas.drawLine(cx - phoneW * 0.16f, box.top + phoneH * 0.11f,
                    cx + phoneW * 0.16f, box.top + phoneH * 0.11f, ink);

            if (progress > 0f) {
                box.set(cx - radius, cy - radius, cx + radius, cy + radius);
                canvas.drawArc(box, -90f, 360f * progress, false, ring);
            }
        }
    }

    /** 搜索态的雷达：两圈很淡的呼吸环 + 一个蓝圆 + 手机图形。 */
    static final class Radar extends View {
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float density;
        private final BooleanSupplier draggingSource;
        private float phase;
        private boolean attached;
        /** 只保留一条心跳。多条并行就是「点一下之后越转越快」。 */
        private boolean scheduled;
        private final Runnable tick;

        Radar(Context host, int accent, float density, BooleanSupplier draggingSource) {
            super(host);
            this.density = density;
            this.draggingSource = draggingSource;
            // 必须在 draggingSource 赋值之后建：字段初始化器里前向引用实例字段编译不过。
            this.tick = () -> {
                scheduled = false;
                if (attached && !draggingSource.getAsBoolean()) {
                    invalidate();
                }
            };
            halo.setColor(accent);
            halo.setStyle(Paint.Style.STROKE);
            halo.setStrokeWidth(Ui.dp(density, 1));
            disc.setColor(accent);
            glyph.setColor(Color.WHITE);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeWidth(Ui.dp(density, 2.4f));
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            attached = true;
            // 雷达一挂上窗口就自己起第一帧：呼吸环靠 onDraw 末尾自我续命，谁要是把那一次
            // invalidate 吞了（层切换、布局重排），从这一帧就能重新接上，不必等外部 wake。
            wake();
        }

        @Override
        protected void onDetachedFromWindow() {
            // 关掉自己的帧循环。这个 View 在每次发现设备、每次换主题时都会被丢掉，留下一个
            // 还在排队的 tick，等于每次换主题都多一条永不停止的心跳。
            attached = false;
            scheduled = false;
            removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        /**
         * 把心跳重新挂上，且**保证全机只有一条**。
         *
         * <p>呼吸环靠 {@link #onDraw} 末尾排下一帧自我续命。原来那里和这里都用
         * {@code postInvalidateDelayed(32)}：那是个每次调用都排一条独立 Runnable 的方法，
         * 谁都可以再排一条而彼此不知情。于是一次点击走 layer(false) 叫两遍、attach 再叫一遍，
         * 就有三条心跳同时跑，onDraw 各自续命——用户看到的就是「点过之后越转越快」。
         * 2026-11-25 改成统一走 {@link #schedule()}：一个 {@link #scheduled} 标志去重，
         * 谁叫都只在没有心跳时才排。
         *
         * <p>没有独立定时器，所以「暂停一次」就是把这条链剪断，而不是踩一脚刹车。剪断之后
         * 没有人再排，手指一抬环就停在那一圈——所以暂停的人必须负责叫醒，见
         * {@code SendPanel.layer(boolean)}。
         */
        void wake() {
            schedule();
        }

        /** 排下一帧；已有心跳、未 attach、正在拖动时都不排。 */
        private void schedule() {
            if (scheduled || !attached || draggingSource.getAsBoolean()) {
                return;
            }
            scheduled = true;
            postDelayed(tick, 32);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float core = Math.min(w, h) * 0.30f;
            float far = Math.min(w, h) * 0.47f;

            // 两圈呼吸环，相位差半圈：读起来像「它在找人」。
            phase = (phase + 0.012f) % 1f;
            for (int index = 0; index < 2; index++) {
                float grown = (phase + index * 0.5f) % 1f;
                halo.setAlpha((int) (70 * (1f - grown)));
                canvas.drawCircle(cx, cy, core + (far - core) * grown, halo);
            }

            canvas.drawCircle(cx, cy, core, disc);
            float phoneW = core * 0.52f;
            float phoneH = core * 0.82f;
            box.set(cx - phoneW / 2f, cy - phoneH / 2f, cx + phoneW / 2f, cy + phoneH / 2f);
            float corner = phoneW * 0.3f;
            canvas.drawRoundRect(box, corner, corner, glyph);
            canvas.drawLine(cx - phoneW * 0.14f, box.top + phoneH * 0.1f,
                    cx + phoneW * 0.14f, box.top + phoneH * 0.1f, glyph);

            // 拖动期间不自转（理由见类注释）。统一走 schedule() 去重，绝不能在这里
            // 直接 postInvalidateDelayed——那会让每一次 wake 都多长出一条心跳。
            schedule();
        }
    }
}
