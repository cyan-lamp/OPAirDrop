package com.oshare.airdrop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * iPhone 实况照片的接收适配（2026-11-26，基于真机取证：IMG_0232.JPG + IMG_0232.MOV
 * + metadata.plist 三件平级成员）。
 *
 * <p># iPhone 实际发来什么
 *
 * <p>一张实况照片在 CPIO 归档里是三个平级成员：{@code IMG_0232.JPG}（静图，JPEG，
 * 尾部干净无任何 Motion Photo 标记）、{@code IMG_0232.MOV}（配对视频，5.6 MB）、
 * {@code metadata.plist}（内容只有
 * {@code PFVideoComplementMetadataVersionKey=1}，是 Live Photo 包的标记文件）。
 * 配对关系靠「同一个 stem」成立（iPhone 侧二者共享 ContentIdentifier，我们无需改写）。
 *
 * <p># 接收侧怎么落库
 *
 * <p>Android 没有原生 Live Photo 概念，相册认的是 Google/三星的 Motion Photo：
 * JPEG 尾部嵌入视频 + XMP 里声明 {@code GCamera:MotionPhoto}。这里把 MOV 追加到
 * JPG 的 EOI 之后、注入 MotionPhoto（v2）与 MicroVideo（v1，兼容旧解析器）双标记
 * 的 XMP APP1 段，合成一张「动态照片」入库——相册长按即播，不再是两条独立条目。
 * 视频与 plist 因此不再单独落库（plist 本就是包标记，落进 Download 是垃圾）。
 *
 * <p># JPEG 的段操作
 *
 * <p>合成只动两处：在第一个段（SOI 之后的 APP0/EXIF）之后插入新 APP1 XMP 段；
 * 视频追加到文件尾（JPEG 以 EOI 结束，直接 append）。已有的 XMP 段会被替换
 * （内容重新生成为 MotionPhoto 版）。段遍历按 marker+length 走，遇到 SOS 后
 * 不再逐段（压缩数据里的 FF 有字节填充），EOI 以文件末尾两字节为准。
 */
public final class MotionPhoto {

    private MotionPhoto() {}

    /** 视频的 MIME（XMP Container:Item 里声明）。 */
    private static final String VIDEO_MIME = "video/quicktime";
    /** Adobe XMP 段的标识 URI（APP1 payload 的前缀，NUL 结尾）。 */
    private static final String XAP_URI = "http://ns.adobe.com/xap/1.0/\0";

    /**
     * 把一张 JPEG 与一段配对视频合成 Google Motion Photo。
     *
     * @param jpeg  静图（完整 JPEG 文件字节，以 FFD8 开头、FFD9 结尾）
     * @param video 配对视频（完整文件字节，直接追加到 EOI 之后）
     * @return 合成后的文件字节
     */
    /** 合成结果：Motion Photo 字节 + 视频元信息（Oplus MediaStore 扩展列要用的）。 */
    public static final class Motion {
        public final byte[] data;
        public final int durationMs;
        public final int width;
        public final int height;

        Motion(byte[] data, int durationMs, int width, int height) {
            this.data = data;
            this.durationMs = durationMs;
            this.width = width;
            this.height = height;
        }
    }

    public static Motion make(byte[] jpeg, byte[] video) throws IOException {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF
                || (jpeg[1] & 0xFF) != 0xD8) {
            throw new IOException("不是 JPEG（缺 SOI）");
        }
        // EOI 以文件末尾两字节为准：JPEG 的规范收尾，相机导出的文件都满足。
        if ((jpeg[jpeg.length - 2] & 0xFF) != 0xFF || (jpeg[jpeg.length - 1] & 0xFF) != 0xD9) {
            throw new IOException("不是完整 JPEG（结尾不是 EOI）");
        }

        // 遍历段：定位插入点（第一个段之后）与既有 XMP 段（替换）。
        int i = 2;
        int insertAt = -1;      // 新 XMP 的插入点（默认第一个段结束处）
        int existingXmp = -1;   // 既有 XMP APP1 段的起点（marker 处）
        while (i + 4 <= jpeg.length) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                break; // 不在段边界上，停手只做追加式插入
            }
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2; // 无长度的段
                continue;
            }
            if (marker == 0xDA) {
                break; // SOS：后面是压缩数据，段遍历到此为止
            }
            int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (length < 2) {
                break;
            }
            if (marker == 0xE1 && i + 4 + XAP_URI.length() <= jpeg.length
                    && startsWith(jpeg, i + 4, XAP_URI)) {
                existingXmp = i;
            }
            if (insertAt < 0) {
                insertAt = i + 2 + length; // 第一个有长度的段结束处
            }
            i += 2 + length;
        }
        if (insertAt < 0) {
            insertAt = 2; // 兜底：SOI 之后
        }

        // iPhone 的配对视频（MOV）原样嵌入——2026-11-26 的实验矩阵（Y1/Z1/Z2）
        // 结论：四家（相册/微信/抖音/小红书）的实况识别要求视频一字不动（moov 在
        // 尾、cdsc 配对轨、ftyp qt 全保留）；rebrand 品牌、remux 重封装（mp42/qt）、
        // faststart 重排（moov 前置）都会让显示全部丢失。Y1 形态（原样）是四家显示
        // 的唯一可行解，相册可播放；第三方 App 的播放限制是 Apple Live Photo 的
        // 生态问题（微信/抖音/小红书对真实 iPhone 实况同样只能显示、不能像相册那样
        // 播放），不是本文件的缺陷。判定列（o_is_live_photo）在 Y1 形态下由
        // MediaProvider 的扩展列保证（insert 时写入）。
        // XMP 的 Length 必须与最终嵌入的字节一致——这一步在 remux 之后算（2026-11-26
        // 的 bug：先算 XMP 再 remux，声明的长度是原始 MOV 的，视频起点错位到 JPEG
        // 数据中间，判定与播放全失败）。
        String xmp = xmp(video.length);
        byte[] segment = app1(xmp);

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                jpeg.length + segment.length + video.length);
        if (existingXmp >= 0) {
            // 既有 XMP 段：跳过它、在它的位置上放新版——避免出现两个 XMP 段。
            int len = ((jpeg[existingXmp + 2] & 0xFF) << 8) | (jpeg[existingXmp + 3] & 0xFF);
            out.write(jpeg, 0, existingXmp);
            out.write(segment);
            out.write(jpeg, existingXmp + 2 + len, jpeg.length - existingXmp - 2 - len);
        } else {
            out.write(jpeg, 0, insertAt);
            out.write(segment);
            out.write(jpeg, insertAt, jpeg.length - insertAt);
        }
        out.write(video);
        // 视频的时长/分辨率：Oplus 相册的实况列（o_video_duration 等）要这些值。
        long[] info = new long[3]; // durationMs, width, height
        walkMoov(video, 0, video.length, info);
        return new Motion(out.toByteArray(), (int) info[0], (int) info[1], (int) info[2]);
    }

    /** MOV/MP4 → 标准 MP4：音视频轨 passthrough 重封装（无重编码），元数据轨丢弃。 */
    private static void remuxToMp4(File mov, File mp4) throws IOException {
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        android.media.MediaMuxer muxer = null;
        String step = "A:setDataSource";
        try {
            extractor.setDataSource(mov.getPath());
            step = "B:tracks";
            java.util.Map<Integer, Integer> trackMap = new java.util.HashMap<>();
            int maxInput = 1 << 20;
            int kept = 0;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(android.media.MediaFormat.KEY_MIME);
                boolean keep = mime != null
                        && (mime.startsWith("video/") || mime.startsWith("audio/"));
                Log.i("MotionPhoto", "remux track " + i + " " + mime + (keep ? " 保留" : " 丢弃"));
                if (!keep) {
                    continue;
                }
                extractor.selectTrack(i);
                if (format.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInput = Math.max(maxInput, format.getInteger(
                            android.media.MediaFormat.KEY_MAX_INPUT_SIZE));
                }
                muxer = muxer == null ? new android.media.MediaMuxer(mp4.getPath(),
                        android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) : muxer;
                int muxTrack = muxer.addTrack(format);
                if (muxTrack >= 0) {
                    trackMap.put(i, muxTrack);
                }
                kept++;
            }
            if (kept == 0) {
                throw new IOException("MOV 里没有可保留的音视频轨");
            }
            step = "C:start";
            muxer.start();
            step = "D:copy";
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(maxInput);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            long samples = 0;
            int lastTrack = -1;
            while (true) {
                int index = extractor.getSampleTrackIndex();
                if (index < 0) {
                    break;
                }
                lastTrack = index;
                Integer outTrack = trackMap.get(index);
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    extractor.advance();
                    continue;
                }
                if (outTrack != null) {
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = extractor.getSampleTime();
                    info.flags = extractor.getSampleFlags();
                    muxer.writeSampleData(outTrack, buffer, info);
                }
                samples++;
                extractor.advance();
            }
            Log.i("MotionPhoto", "remux copied " + samples + " samples, last track " + lastTrack);
            step = "E:stop";
            muxer.stop();
        } catch (Throwable failure) {
            throw new IOException("remux[" + step + "]：" + failure, failure);
        } finally {
            extractor.release();
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * MOV/MP4 的 box 遍历：取 mvhd 的时长（换算毫秒）与 tkhd 的宽高（16.16 定点）。
     * 只找 moov 里的两个 box，够用即止；解析不出的字段保持 0（落库为 NULL）。
     */
    private static void walkMoov(byte[] v, int start, int end, long[] out) {
        int i = start;
        while (i + 8 <= end) {
            int size = ((v[i] & 0xFF) << 24) | ((v[i + 1] & 0xFF) << 16)
                    | ((v[i + 2] & 0xFF) << 8) | (v[i + 3] & 0xFF);
            String type = new String(v, i + 4, 4, StandardCharsets.US_ASCII);
            int body = i + 8;
            int next = i + size;
            if (size < 8 || next > end) {
                return;
            }
            if (type.equals("moov")) {
                walkMoov(v, body, next, out);
            } else if (type.equals("mvhd")) {
                int version = v[body] & 0xFF;
                if (version == 0 && body + 20 <= next) {
                    long timescale = u32(v, body + 12);
                    long duration = u32(v, body + 16);
                    if (timescale > 0) {
                        out[0] = duration * 1000 / timescale;
                    }
                } else if (version == 1 && body + 32 <= next) {
                    long timescale = u32(v, body + 20);
                    long duration = u64(v, body + 24);
                    if (timescale > 0) {
                        out[0] = duration * 1000 / timescale;
                    }
                }
            } else if (type.equals("tkhd")) {
                int version = v[body] & 0xFF;
                int head = version == 0 ? 76 : version == 1 ? 84 : version == 2 ? 92 : -1;
                if (head >= 0 && body + head + 8 <= next) {
                    out[1] = u32(v, body + head) >> 16;
                    out[2] = u32(v, body + head + 4) >> 16;
                }
            }
            i = next;
        }
    }

    private static long u32(byte[] v, int at) {
        return ((v[at] & 0xFFL) << 24) | ((v[at + 1] & 0xFFL) << 16)
                | ((v[at + 2] & 0xFFL) << 8) | (v[at + 3] & 0xFFL);
    }

    private static long u64(byte[] v, int at) {
        return (u32(v, at) << 32) | u32(v, at + 4);
    }

    /**
     * XMP：模板 = Oplus 相机实况的 XMP 全段（2026-11-26 逐字对照 + 实验 X 验证：
     * 这台相册的解析对 XMP 字节格式敏感——语义相同的单行构造版不认，相机原样的
     * 缩进换行版认）。动态的只有三处视频长度（MotionPhoto 项、OpCamera:VideoLength、
     * MicroVideoOffset）。GainMap 项的长度是相机样本原值（我们没有 gain map 数据段，
     * 判定阶段不校验）。
     */
    private static String xmp(int videoLength) {
        // 相机 XMP 段的原样字节（2026-11-26 从 PJZ110 相机实况样本逐字节提取，只把
        // 三处视频长度改成动态）。相册/微信/抖音/小红书的实况识别对 XMP 的**字节
        // 格式**敏感——语义相同的单行构造版会被相册解析器拒绝（Y1 实验：相机原样
        // 段四家全显示；单行模板全军覆没）。换行、缩进、x:xmptk、xpacket 全部保留。
        String template = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core Test.SNAPSHOT\">\n  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n    <rdf:Description rdf:about=\"\"\n        xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"\n        xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n        xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\"\n        xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n        xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n      hdrgm:Version=\"1.0\"\n      GCamera:MotionPhoto=\"1\"\n      GCamera:MotionPhotoVersion=\"1\"\n      GCamera:MotionPhotoPresentationTimestampUs=\"1065000\"\n      OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"1065000\"\n      OpCamera:MotionPhotoOwner=\"oplus\"\n      OpCamera:OLivePhotoVersion=\"2\"\n      OpCamera:VideoLength=\"4671312\"\n      OpCamera:MotionPhotoEnable=\"True\"\n      OpCamera:MotionPhotoEditorFlag=\"8\">\n      <Container:Directory>\n        <rdf:Seq>\n          <rdf:li rdf:parseType=\"Resource\">\n            <Container:Item\n              Item:Mime=\"image/jpeg\"\n              Item:Semantic=\"Primary\"\n              Item:Length=\"0\"\n              Item:Padding=\"0\"/>\n          </rdf:li>\n          <rdf:li rdf:parseType=\"Resource\">\n            <Container:Item\n              Item:Mime=\"image/jpeg\"\n              Item:Semantic=\"GainMap\"\n              Item:Length=\"263537\"\n              Item:Padding=\"0\"/>\n          </rdf:li>\n          <rdf:li rdf:parseType=\"Resource\">\n            <Container:Item\n              Item:Mime=\"video/mp4\"\n              Item:Semantic=\"MotionPhoto\"\n              Item:Length=\"4672761\"\n              Item:Padding=\"0\"/>\n          </rdf:li>\n        </rdf:Seq>\n      </Container:Directory>\n    </rdf:Description>\n  </rdf:RDF>\n</x:xmpmeta>\n";
        String len = String.valueOf(videoLength);
        return template.replace("4672761", len).replace("4671312", len);
    }

    /** 把 XMP 文本包成 APP1 段（FF E1 + 长度 + XAP URI + 内容）。 */
    private static byte[] app1(String xmp) {
        byte[] uri = XAP_URI.getBytes(StandardCharsets.US_ASCII);
        byte[] body = xmp.getBytes(StandardCharsets.UTF_8);
        int length = 2 + uri.length + body.length; // 长度字段含自身两字节
        byte[] out = new byte[4 + uri.length + body.length];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xE1;
        out[2] = (byte) ((length >> 8) & 0xFF);
        out[3] = (byte) (length & 0xFF);
        System.arraycopy(uri, 0, out, 4, uri.length);
        System.arraycopy(body, 0, out, 4 + uri.length, body.length);
        return out;
    }

    private static boolean startsWith(byte[] data, int at, String prefix) {
        byte[] head = prefix.getBytes(StandardCharsets.US_ASCII);
        if (at + head.length > data.length) {
            return false;
        }
        for (int i = 0; i < head.length; i++) {
            if (data[at + i] != head[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 一批成员里找出实况照片的配对：同 stem 的一张图（jpg/jpeg/heic）+ 一段视频
     * （mov）。返回视频名集合（配对成功、将并入图片的），无配对返回空——独立的
     * 视频照常按视频落库，不误伤。
     */
    static java.util.Map<String, String> pairs(java.util.List<String> names) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> videos = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> images = new java.util.LinkedHashMap<>();
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            String file = lower.substring(lower.lastIndexOf('/') + 1);
            int dot = file.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String stem = file.substring(0, dot);
            String ext = file.substring(dot + 1);
            if (ext.equals("mov")) {
                videos.putIfAbsent(stem, name);
            } else if (ext.equals("jpg") || ext.equals("jpeg") || ext.equals("heic")
                    || ext.equals("heif")) {
                images.putIfAbsent(stem, name);
            }
        }
        for (java.util.Map.Entry<String, String> video : videos.entrySet()) {
            String image = images.get(video.getKey());
            if (image != null) {
                map.put(video.getValue(), image);
            }
        }
        return map;
    }

    /** Live Photo 包的标记文件（metadata.plist），不落库。 */
    static boolean isPlistMarker(String name) {
        String file = name.toLowerCase(Locale.ROOT);
        return file.endsWith("/metadata.plist") || file.equals("metadata.plist");
    }
}
