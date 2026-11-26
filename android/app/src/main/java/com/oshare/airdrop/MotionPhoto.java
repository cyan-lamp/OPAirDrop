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

    /**
     * OPPO 实况专用 EXIF APP1 模板（116 字节，照抄 motion-live-photo 的
     * "oppo full-support" 实现，逐字节）：0x0100 宽 / 0x0101 高 / 0x0112 方向 /
     * 0x8769 ExifIFD 指针 / 0x9286 UserComment="oplus_8388608" / 0x9208 闪光灯。
     * 宽高与方向按原图动态改（2026-11-26 实测：模板固定 Orientation=0 会让封面
     * 横竖颠倒——原图 iPhone 竖拍 Orientation=6，必须继承）。
     */
    private static final String OPPO_EXIF_HEX = "FFE100724578696600004D4D002A000000080004"
            + "0100000400000001000005A001010004000000010000043C87690004000000010000003E"
            + "011200030000000100000000000000000002928600020000000E0000005C"
            + "920800040000000100000000000000006F706C75735F3833383836303800";

    /** OPPO 实况的 XMP 模板（照抄同一实现；timestamp=0、无 GainMap 项）。 */
    private static final String OPPO_XMP = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" "
            + "x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">\n"
            + "  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
            + "    <rdf:Description rdf:about=\"\"\n"
            + "        xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"\n"
            + "        xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n"
            + "        xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\"\n"
            + "        xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n"
            + "        xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n"
            + "      hdrgm:Version=\"1.0\"\n"
            + "      GCamera:MotionPhoto=\"1\"\n"
            + "      GCamera:MotionPhotoVersion=\"1\"\n"
            + "      GCamera:MotionPhotoPresentationTimestampUs=\"0\"\n"
            + "      OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"0\"\n"
            + "      OpCamera:MotionPhotoOwner=\"oplus\"\n"
            + "      OpCamera:OLivePhotoVersion=\"2\"\n"
            + "      OpCamera:VideoLength=\"{LEN}\">\n"
            + "      <Container:Directory>\n"
            + "        <rdf:Seq>\n"
            + "          <rdf:li rdf:parseType=\"Resource\">\n"
            + "            <Container:Item\n"
            + "              Item:Mime=\"image/jpeg\"\n"
            + "              Item:Semantic=\"Primary\"\n"
            + "              Item:Length=\"0\"\n"
            + "              Item:Padding=\"0\"/>\n"
            + "          </rdf:li>\n"
            + "          <rdf:li rdf:parseType=\"Resource\">\n"
            + "            <Container:Item\n"
            + "              Item:Mime=\"video/mp4\"\n"
            + "              Item:Semantic=\"MotionPhoto\"\n"
            + "              Item:Length=\"{LEN}\"/>\n"
            + "          </rdf:li>\n"
            + "        </rdf:Seq>\n"
            + "      </Container:Directory>\n"
            + "    </rdf:Description>\n"
            + "  </rdf:RDF>\n"
            + "</x:xmpmeta>";

    public static Motion make(byte[] jpeg, byte[] video) throws IOException {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF
                || (jpeg[1] & 0xFF) != 0xD8) {
            throw new IOException("不是 JPEG（缺 SOI）");
        }
        if ((jpeg[jpeg.length - 2] & 0xFF) != 0xFF || (jpeg[jpeg.length - 1] & 0xFF) != 0xD9) {
            throw new IOException("不是完整 JPEG（结尾不是 EOI）");
        }

        // 原图的宽/高/方向（EXIF 0x0100/0x0101/0x0112）——写进 OPPO 实况 EXIF。
        int width = 0;
        int height = 0;
        int orientation = 1;
        try {
            android.media.ExifInterface exif = new android.media.ExifInterface(
                    new java.io.ByteArrayInputStream(jpeg));
            String w = exif.getAttribute(android.media.ExifInterface.TAG_IMAGE_WIDTH);
            String h = exif.getAttribute(android.media.ExifInterface.TAG_IMAGE_LENGTH);
            String o = exif.getAttribute(android.media.ExifInterface.TAG_ORIENTATION);
            if (w != null) {
                width = Integer.parseInt(w.trim());
            }
            if (h != null) {
                height = Integer.parseInt(h.trim());
            }
            if (o != null) {
                orientation = Integer.parseInt(o.trim());
            }
        } catch (Throwable ignored) {
        }
        if (width <= 0 || height <= 0) {
            width = 4032;
            height = 3024;
        }
        if (orientation <= 0) {
            orientation = 1;
        }

        byte[] exifTpl = hex(OPPO_EXIF_HEX);
        exifTpl[28] = (byte) (width >> 24);
        exifTpl[29] = (byte) (width >> 16);
        exifTpl[30] = (byte) (width >> 8);
        exifTpl[31] = (byte) width;
        exifTpl[40] = (byte) (height >> 24);
        exifTpl[41] = (byte) (height >> 16);
        exifTpl[42] = (byte) (height >> 8);
        exifTpl[43] = (byte) height;
        exifTpl[64] = (byte) (orientation >> 8);
        exifTpl[65] = (byte) orientation;

        // 跳过原 JPG 的前两个 APP1（EXIF、XMP），保留其余段（ICC/DQT/SOF/DHT/SOS…）。
        java.io.ByteArrayOutputStream rest = new java.io.ByteArrayOutputStream();
        int pos = 2;
        int skipped = 0;
        while (pos + 4 <= jpeg.length) {
            if ((jpeg[pos] & 0xFF) != 0xFF) {
                break;
            }
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xD9 || marker == 0xDA) {
                rest.write(jpeg, pos, jpeg.length - pos);
                break;
            }
            int length = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (skipped < 2 && marker == 0xE1) {
                skipped++;
            } else {
                rest.write(jpeg, pos, 2 + length);
            }
            pos += 2 + length;
        }

        String len = String.valueOf(video.length);
        byte[] xmp = OPPO_XMP.replace("{LEN}", len).getBytes(StandardCharsets.UTF_8);
        byte[] xmpSeg = new byte[2 + 2 + 29 + xmp.length];
        xmpSeg[0] = (byte) 0xFF;
        xmpSeg[1] = (byte) 0xE1;
        int xmpLen = 2 + 29 + xmp.length;
        xmpSeg[2] = (byte) ((xmpLen >> 8) & 0xFF);
        xmpSeg[3] = (byte) (xmpLen & 0xFF);
        System.arraycopy(XAP_URI.getBytes(StandardCharsets.US_ASCII), 0, xmpSeg, 4, 29);
        System.arraycopy(xmp, 0, xmpSeg, 4 + 29, xmp.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                jpeg.length + video.length + 256);
        out.write(0xFF);
        out.write(0xD8);
        out.write(exifTpl);
        out.write(xmpSeg);
        rest.writeTo(out);
        out.write(video);

        long[] info = new long[3];
        walkMoov(video, 0, video.length, info);
        return new Motion(out.toByteArray(), (int) info[0], (int) info[1], (int) info[2]);
    }

    private static byte[] hex(String value) {
        int n = value.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
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

    /**
     * AppleDouble 元数据成员（.{@code _IMG_xxx.JPG}、{@code ._IMG_xxx.pvt}）：
     * .pvt 包里 macOS/iOS 的扩展属性载体，不是内容，不落库（会变成 Download 里的
     * 垃圾文件）。
     */
    static boolean isAppleDouble(String name) {
        String file = name.toLowerCase(Locale.ROOT);
        int slash = file.lastIndexOf('/');
        String base = file.substring(slash + 1);
        return base.startsWith("._");
    }
}
