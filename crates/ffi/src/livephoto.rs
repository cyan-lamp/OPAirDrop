//! 发送侧的实况照片：从一张 Motion Photo 里拆出图片与视频，并读出 Apple 的配对标识。
//!
//! # 为什么发送要拆
//!
//! 我们收到 iPhone 的实况时合成的是 **Android Motion Photo**（JPEG 尾部嵌视频，XMP 里
//! 带 `MotionPhoto=1`）。那是给 Android 相册看的格式，而 iPhone 认的是**两个文件**：
//! 一张图片 + 一个同名的 MOV，两者带**相同的 `ContentIdentifier`**（UUID）。所以把合成
//! 图原样发过去，iPhone 只会把它当成一张普通照片——视频就在里面，但它不会去找。
//!
//! 拆开之后还要把配对标识**补回图片一侧**：相机实况的 EXIF 里没有 Apple 的 MakerNote
//! （`ContentIdentifier` 写在 tag 0x0011），而 MOV 里 `com.apple.quicktime.content.identifier`
//! 得两边一致才配对。这一步是发送侧能成立的关键。
//!
//! 补 MakerNote 的方式是**整段换 EXIF 模板**（Apple 的 MakerNote 是一棵带偏移的私有
//! IFD 树，照着规范手写必错），而模板自带一套显示参数：它取自一张 iPhone 竖拍，
//! `Orientation=6`、4032×3024。本机相机正相反——像素已经摆正、标记留 1（3072×4096）。
//! 原样换上去等于给一张正立的图贴上「再顺时针转 90°」的标签，iPhone 照着转，封面就和
//! 本机相册差 90°。接收侧早就踩过这个坑（`MotionPhoto.make` 里"方向必须继承原图"），
//! 发送侧换 EXIF 时又丢了一次。所以换模板之后要把源图的**朝向与像素尺寸**写回去，
//! 见 [`with_makernote`]。
//!
//! # 为什么在 FFI 层而不是 Java 层
//!
//! 拆出来的视频字节不必落盘：`archive()` 已经按成员流式写归档，把「来源」从路径扩展成
//! 「路径或内存」就能直接把两段送进去。省掉一次写临时文件，也省掉清理。

/// 一次拆分的结果：图片（到 EOI 为止）与尾部视频。
pub struct LivePhoto {
    /// 主图，从 SOI 到 EOI，**不含**视频。
    pub image: Vec<u8>,
    /// EOI 之后的那段视频（原样，一个字节不动）。
    pub video: Vec<u8>,
    /// MOV 里 `com.apple.quicktime.content.identifier` 的值，形如
    /// `672FB079-402D-4E1E-AECE-67D388C64CE8`。缺这个就配不上对。
    pub identifier: Option<String>,
    /// MOV 自己的时间基（`mvhd` 的 timescale / duration）。`metadata.plist` 里那段
    /// 时间信息就是这两个数，读不到时按 600 / 0 写。
    /// MOV 的  时间基。
    pub timescale: u32,
    /// MOV 的  时长（以  为单位）。
    pub duration: u64,
}

/// 一个文件是不是 Motion Photo；是就拆成两半。
///
/// 判据全部来自文件本身，不信扩展名：SOI 开头、XMP 里声明了 Motion Photo、声明的长度
/// 把文件恰好切在一个 EOI 上。三条都满足才拆——任何一条不满足，原文件照常作为一个成员
/// 发出去，宁可少配一次对，也不能把一个好文件切坏。
pub fn split(bytes: &[u8]) -> Option<LivePhoto> {
    if bytes.len() < 4 || bytes[0] != 0xFF || bytes[1] != 0xD8 {
        return None;
    }
    let xmp = xmp_segment(bytes)?;
    if !is_motion_photo(xmp) {
        return None;
    }
    let length = motion_length(xmp)?;
    if length == 0 || length >= bytes.len() {
        return None;
    }
    let cut = bytes.len() - length;
    // 视频必须紧贴在 EOI 之后。差一个字节就说明声明的长度不是这个意思（GainMap、
    // 水印段、或者别的尾部数据），这时候拆开只会把图片切坏。
    if cut < 2 || bytes[cut - 2] != 0xFF || bytes[cut - 1] != 0xD9 {
        return None;
    }
    let mut video = bytes[cut..].to_vec();
    let mut identifier = content_identifier(&video);
    if identifier.is_none() {
        // 相机自己拍的实况没有 Apple 的配对标识（OPPO 不写这套）：视频里没有
        // content.identifier，图片里没有 MakerNote。这样发过去，收的一端只看到一对
        // 没有关联的图与视频——iPhone 甚至会把 `.pvt` 当成打不开的图片。
        //
        // 所以自己造一个 UUID 并写进视频的 moov/meta（图片那一半在合成的第一步做，
        // 见 `MotionPhoto`）。没有这一步，相机实况永远发不成实况。
        let fresh = fresh_uuid();
        if let Some(rewritten) = with_identifier(&video, &fresh) {
            video = rewritten;
            identifier = Some(fresh);
        }
    }
    let mut image = bytes[..cut].to_vec();
    // 图片那一半：相机实况的 EXIF 里没有 Apple 的 MakerNote（0x0011 就是配对 UUID），
    // 而 iPhone 看的是**两边都有且相同**。视频补了、图片没补，照样配不成对——用户实测
    // 的表现是「iPhone 显示一张打不开的图片」。
    if let Some(id) = &identifier {
        if let Some(rewritten) = with_makernote(&image, id) {
            image = rewritten;
        }
    }
    let (timescale, duration) = timing(&video);
    Some(LivePhoto {
        image,
        identifier,
        timescale,
        duration,
        video,
    })
}

/// 真机一张 Live Photo 的完整 EXIF（12 KB），从用户的 iPhone 照片里提取。
///
/// <p>用它当模板而不是自己拼：Apple 的 MakerNote（tag 0x927C）是一棵带偏移的私有
/// IFD 树，1455 字节里有十几个 tag，UUID（tag 0x0011）埋在偏移 1132 处。照着规范一位
/// 一位手写，错了的后果是「图片能开、但不是实况」，而错在哪里没有任何东西会告诉你。
///
/// <p>模板里的拍摄信息（机型、镜头、序列号）随文件一起发给对端。这是用户自己设备拍的
/// 照片，收的一方也是他自己的设备；换成空值需要重排整棵 IFD，收益只是好看。
const EXIF_TEMPLATE: &[u8] = include_bytes!("livephoto_exif.bin");

/// 模板里那条旧 UUID（模板来源照片的 ContentIdentifier）。
const TEMPLATE_UUID: &[u8] = b"672FB079-402D-4E1E-AECE-67D388C64CE8";

/// 把图片的 EXIF 换成模板（含 MakerNote），并写回源图的朝向与尺寸。
///
/// <p>只在图片自己**没有** MakerNote 时才动手：转发场景（iPhone 发来、我们合成、再发
/// 回去）的图片原样保留着 Apple 写的 EXIF，那份比模板更准——朝向也在里面，不用补。
fn with_makernote(jpeg: &[u8], uuid: &str) -> Option<Vec<u8>> {
    if uuid.len() != 36 || find(jpeg, b"Apple iOS").is_some() {
        return None;
    }
    let mut template = EXIF_TEMPLATE.to_vec();
    let at = find(&template, TEMPLATE_UUID)?;
    template[at..at + 36].copy_from_slice(uuid.as_bytes());
    // 模板的方向与尺寸是它来源那张照片的，不是这张的。读不到源图的尺寸就整段不写：
    // 编一对假尺寸比沿用模板的 4032×3024 更糟。
    if let Some(frame) = frame(jpeg) {
        stamp_frame(&mut template, &frame);
    }

    let mut segment = Vec::with_capacity(template.len() + 4);
    segment.extend_from_slice(&[0xFF, 0xE1]);
    segment.extend_from_slice(&((template.len() + 2) as u16).to_be_bytes());
    segment.extend_from_slice(&template);

    let mut out = Vec::with_capacity(jpeg.len() + segment.len());
    out.extend_from_slice(&jpeg[..2]);
    let mut at = 2;
    let mut replaced = false;
    while at + 4 <= jpeg.len() {
        if jpeg[at] != 0xFF {
            out.extend_from_slice(&jpeg[at..]);
            return replaced.then_some(out);
        }
        let marker = jpeg[at + 1];
        if marker == 0xDA || marker == 0xD9 {
            // 没有 EXIF 段（罕见）：插在图像数据之前。
            if !replaced {
                out.extend_from_slice(&segment);
                replaced = true;
            }
            out.extend_from_slice(&jpeg[at..]);
            return replaced.then_some(out);
        }
        let length = ((jpeg[at + 2] as usize) << 8) | jpeg[at + 3] as usize;
        if length < 2 || at + 2 + length > jpeg.len() {
            return None;
        }
        let body = &jpeg[at + 4..at + 2 + length];
        if marker == 0xE1 && body.starts_with(b"Exif\x00\x00") && !replaced {
            out.extend_from_slice(&segment);
            replaced = true;
        } else {
            out.extend_from_slice(&jpeg[at..at + 2 + length]);
        }
        at += 2 + length;
    }
    replaced.then_some(out)
}

/// 源图在屏幕上「怎么摆」：EXIF 的朝向标记 + SOF 里的存储尺寸。
///
/// <p>尺寸取自 SOF 而不是 EXIF：SOF 是解码器真正会读到的值，EXIF 里的
/// `0x0100/0x0101` 有些机型根本不写。朝向只认 `0x0112`，缺失或越界时按 1（正立）——
/// 绝大多数相机把像素转正、标记留 1，按 1 处理最不容易错。
#[derive(Clone, Copy)]
struct Frame {
    orientation: u16,
    width: u32,
    height: u32,
}

/// 读源图的显示参数；读不到尺寸返回 `None`（没有 SOF 说明这不是一张能解出来的 JPEG）。
fn frame(jpeg: &[u8]) -> Option<Frame> {
    let mut out = Frame {
        orientation: 1,
        width: 0,
        height: 0,
    };
    let mut at = 2;
    while at + 4 <= jpeg.len() {
        if jpeg[at] != 0xFF {
            return None;
        }
        let marker = jpeg[at + 1];
        if marker == 0xDA || marker == 0xD9 {
            break;
        }
        // 无长度的独立标记（TEM / RSTn），段链里不该出现；真出现就跳两字节。
        if marker == 0x01 || (0xD0..=0xD7).contains(&marker) {
            at += 2;
            continue;
        }
        let length = ((jpeg[at + 2] as usize) << 8) | jpeg[at + 3] as usize;
        if length < 2 || at + 2 + length > jpeg.len() {
            return None;
        }
        let body = &jpeg[at + 4..at + 2 + length];
        if marker == 0xE1 && body.starts_with(b"Exif\x00\x00") {
            if let Some((big, ifd0)) = tiff_head(&body[6..]) {
                if let Some((_, value)) = entry(&body[6..], ifd0, big, 0x0112) {
                    // 0 与 9 以上都不是合法朝向，当正立处理。
                    if (1..=8).contains(&value) {
                        out.orientation = value as u16;
                    }
                }
            }
        } else if is_sof(marker) && body.len() >= 5 {
            // SOF：精度(1) 高(2) 宽(2)，都是大端。
            out.height = ((body[1] as u32) << 8) | body[2] as u32;
            out.width = ((body[3] as u32) << 8) | body[4] as u32;
        }
        at += 2 + length;
    }
    (out.width > 0 && out.height > 0).then_some(out)
}

/// SOF 系列（帧头）：0xC0–0xCF 里排除 DHT(0xC4)、JPG(0xC8)、DAC(0xCC)。
fn is_sof(marker: u8) -> bool {
    (0xC0..=0xCF).contains(&marker) && !matches!(marker, 0xC4 | 0xC8 | 0xCC)
}

/// 把源图的朝向与尺寸写进模板里**已有的**那几条 tag。
///
/// <p>只改值、不动类型、不增删条目：模板是一棵已经对好的偏移树，加一条 tag 就得重排
/// 整棵 IFD 并修正所有 `value offset`。这几条都是计数为 1 的短值，值就存在条目内部的
/// 那四个字节里，改它不影响任何偏移。
fn stamp_frame(template: &mut [u8], frame: &Frame) {
    let Some(tiff) = template.get_mut(6..) else {
        return;
    };
    let Some((big, ifd0)) = tiff_head(tiff) else {
        return;
    };
    put(tiff, ifd0, big, 0x0112, frame.orientation as u32);
    put(tiff, ifd0, big, 0x0100, frame.width);
    put(tiff, ifd0, big, 0x0101, frame.height);
    // ExifIFD 里的 0xA002/0xA003 是「实际像素尺寸」，iPhone 排版也看它。
    if let Some((_, exif)) = entry(tiff, ifd0, big, 0x8769) {
        put(tiff, exif as usize, big, 0xA002, frame.width);
        put(tiff, exif as usize, big, 0xA003, frame.height);
    }
}

/// TIFF 头：字节序（`MM` 大端 / `II` 小端）与第一层 IFD 的偏移。
fn tiff_head(tiff: &[u8]) -> Option<(bool, usize)> {
    if tiff.len() < 8 {
        return None;
    }
    let big = match &tiff[..2] {
        b"MM" => true,
        b"II" => false,
        _ => return None,
    };
    let at = num32(tiff, 4, big)? as usize;
    (at + 2 <= tiff.len()).then_some((big, at))
}

fn num16(bytes: &[u8], at: usize, big: bool) -> Option<u16> {
    let pair = [*bytes.get(at)?, *bytes.get(at + 1)?];
    Some(if big {
        u16::from_be_bytes(pair)
    } else {
        u16::from_le_bytes(pair)
    })
}

fn num32(bytes: &[u8], at: usize, big: bool) -> Option<u32> {
    let quad = [
        *bytes.get(at)?,
        *bytes.get(at + 1)?,
        *bytes.get(at + 2)?,
        *bytes.get(at + 3)?,
    ];
    Some(if big {
        u32::from_be_bytes(quad)
    } else {
        u32::from_le_bytes(quad)
    })
}

/// 在这一层 IFD 里找 `tag`，返回（类型，值）。只认**计数为 1** 的 SHORT/LONG：
/// 这两个长度不超过四个字节，值就在条目内部，取它不必跟偏移。
fn entry(bytes: &[u8], ifd: usize, big: bool, tag: u16) -> Option<(u16, u32)> {
    let count = num16(bytes, ifd, big)? as usize;
    for index in 0..count {
        let at = ifd + 2 + index * 12;
        if at + 12 > bytes.len() {
            return None;
        }
        if num16(bytes, at, big)? != tag {
            continue;
        }
        let kind = num16(bytes, at + 2, big)?;
        if num32(bytes, at + 4, big)? != 1 {
            return None;
        }
        return match kind {
            3 => num16(bytes, at + 8, big).map(|value| (kind, value as u32)),
            4 => num32(bytes, at + 8, big).map(|value| (kind, value)),
            _ => None,
        };
    }
    None
}

/// 把 `tag` 的值改成 `value`，类型沿用原条目（SHORT 写两字节，LONG 写四字节）。
/// 找不到或类型不认识就什么都不做——少改一条顶多让尺寸不准，改错一格就整段解析不了。
fn put(bytes: &mut [u8], ifd: usize, big: bool, tag: u16, value: u32) {
    let count = match num16(bytes, ifd, big) {
        Some(count) => count as usize,
        None => return,
    };
    for index in 0..count {
        let at = ifd + 2 + index * 12;
        if at + 12 > bytes.len() {
            return;
        }
        if num16(bytes, at, big) != Some(tag) {
            continue;
        }
        let kind = match num16(bytes, at + 2, big) {
            Some(kind) => kind,
            None => return,
        };
        let value_at = at + 8;
        let raw = match kind {
            3 => {
                let short = value as u16;
                if big {
                    short.to_be_bytes().to_vec()
                } else {
                    short.to_le_bytes().to_vec()
                }
            }
            4 => {
                if big {
                    value.to_be_bytes().to_vec()
                } else {
                    value.to_le_bytes().to_vec()
                }
            }
            _ => return,
        };
        bytes[value_at..value_at + raw.len()].copy_from_slice(&raw);
        return;
    }
}

/// 一个 v4 UUID，大写带连字符——和 Apple 写在 MOV 里、plist 里的形状一致。
fn fresh_uuid() -> String {
    let mut bytes = [0u8; 16];
    if let Ok(mut source) = std::fs::File::open("/dev/urandom") {
        use std::io::Read as _;
        let _ = source.read_exact(&mut bytes);
    }
    bytes[6] = (bytes[6] & 0x0F) | 0x40;
    bytes[8] = (bytes[8] & 0x3F) | 0x80;
    format!(
        "{:02X}{:02X}{:02X}{:02X}-{:02X}{:02X}-{:02X}{:02X}-{:02X}{:02X}-\
         {:02X}{:02X}{:02X}{:02X}{:02X}{:02X}",
        bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
        bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]
    )
}

/// 给视频的 `moov` 末尾加一个只在声明配对标识的 `meta` box。
///
/// <p>结构照抄真机（`IMG_7603.MOV` 的 moov/meta：hdlr + keys + ilst，keys 的命名空间是
/// `mdta`，ilst 里每项是一个 `data` box）。只写 `content.identifier` 这一条：make /
/// model / software 说的是拍摄设备，我们不是那台设备，写进去就是假话。
///
/// <p>能这么做的前提是这条视频的 `mdat` 在 `moov` **前面**（iPhone 与 OPPO 的实况都是）。
/// 那样往 moov 末尾追加不会移动任何采样数据，`stco` 里的偏移一个都不用改。反过来的布局
/// 直接放弃改写，返回 `None`——宁可不配对，也不能写出一条播不了的视频。
fn with_identifier(video: &[u8], uuid: &str) -> Option<Vec<u8>> {
    let (at, size) = top_box(video, b"moov")?;
    // mdat 必须在 moov 之前：之后的话，加长 moov 会把 mdat 往后推，而 stco 里记的是
    // 绝对偏移，那些数字就成了错的。
    let (mdat_at, _) = top_box(video, b"mdat")?;
    if mdat_at > at {
        return None;
    }
    // 已经有 meta 就不碰：那说明这条视频本来就带着自己的元数据，改它需要合并 keys，
    // 而合并的收益只是省下 172 字节。
    if child_box(&video[at..at + size], b"meta").is_some() {
        return None;
    }
    let meta = meta_box(uuid);
    let grown = size + meta.len();
    let mut out = Vec::with_capacity(video.len() + meta.len());
    out.extend_from_slice(&video[..at]);
    out.extend_from_slice(&(grown as u32).to_be_bytes());
    out.extend_from_slice(&video[at + 4..at + size]);
    out.extend_from_slice(&meta);
    out.extend_from_slice(&video[at + size..]);
    Some(out)
}

/// 真机的 `meta` 里的 hdlr：34 字节，组件子类型是 `mdta`（元数据）。
const HDLR: &[u8] = &[
    0x00, 0x00, 0x00, 0x22, b'h', b'd', b'l', b'r', 0, 0, 0, 0, 0, 0, 0, 0, b'm', b'd', b't', b'a',
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
];

const IDENTIFIER_KEY: &[u8] = b"com.apple.quicktime.content.identifier";

fn meta_box(uuid: &str) -> Vec<u8> {
    let value = uuid.as_bytes();
    let mut keys = Vec::new();
    keys.extend_from_slice(&(8 + 4 + 4 + 4 + 4 + IDENTIFIER_KEY.len() as u32).to_be_bytes());
    keys.extend_from_slice(b"keys");
    keys.extend_from_slice(&[0, 0, 0, 0]);
    keys.extend_from_slice(&1u32.to_be_bytes());
    keys.extend_from_slice(&(IDENTIFIER_KEY.len() as u32).to_be_bytes());
    keys.extend_from_slice(b"mdta");
    keys.extend_from_slice(IDENTIFIER_KEY);

    let mut data = Vec::new();
    data.extend_from_slice(&(8 + 4 + 4 + value.len() as u32).to_be_bytes());
    data.extend_from_slice(b"data");
    data.extend_from_slice(&1u32.to_be_bytes());
    data.extend_from_slice(&0u32.to_be_bytes());
    data.extend_from_slice(value);

    let mut item = Vec::new();
    item.extend_from_slice(&(4 + 4 + data.len() as u32).to_be_bytes());
    item.extend_from_slice(&1u32.to_be_bytes());
    item.extend_from_slice(&data);

    let mut ilst = Vec::new();
    ilst.extend_from_slice(&(8 + item.len() as u32).to_be_bytes());
    ilst.extend_from_slice(b"ilst");
    ilst.extend_from_slice(&item);

    let mut meta = Vec::new();
    meta.extend_from_slice(&(8 + HDLR.len() as u32 + keys.len() as u32 + ilst.len() as u32).to_be_bytes());
    meta.extend_from_slice(b"meta");
    meta.extend_from_slice(HDLR);
    meta.extend_from_slice(&keys);
    meta.extend_from_slice(&ilst);
    meta
}

/// 顶层 box 的位置与大小。
fn top_box(bytes: &[u8], kind: &[u8]) -> Option<(usize, usize)> {
    let mut at = 0;
    while at + 8 <= bytes.len() {
        let size = u32::from_be_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
            as usize;
        if size < 8 || at + size > bytes.len() {
            return None;
        }
        if &bytes[at + 4..at + 8] == kind {
            return Some((at, size));
        }
        at += size;
    }
    None
}

/// 某一个 box 的直接子 box。
fn child_box(bytes: &[u8], kind: &[u8]) -> Option<(usize, usize)> {
    let mut at = 8;
    while at + 8 <= bytes.len() {
        let size = u32::from_be_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
            as usize;
        if size < 8 || at + size > bytes.len() {
            return None;
        }
        if &bytes[at + 4..at + 8] == kind {
            return Some((at, size));
        }
        at += size;
    }
    None
}

impl LivePhoto {
    /// iPhone 的 `.pvt` 包里那张 `metadata.plist`，逐字段照抄真机样本。
    ///
    /// 样本（`IMG_7603.pvt/metadata.plist`，2026-11-26 从暂存目录取到）：三个键——
    /// 配对用的 UUID、一段时间信息、版本号 1。少了它，收到的那一端把 `.pvt` 当成一个
    /// 装着两个文件的普通目录：图能看、视频能放，但不是实况。没有 UUID 就配不成对，
    /// 这时不出 plist，让对端至少还能当两个文件用。
    pub fn plist(&self) -> Option<String> {
        let identifier = self.identifier.as_ref()?;
        Some(format!(
            r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>PFVideoComplementMetadataPairidIdentifierKey</key>
	<string>{identifier}</string>
	<key>PFVideoComplementMetadataTimingInfoKey</key>
	<dict>
		<key>epoch</key>
		<integer>0</integer>
		<key>flags</key>
		<integer>1</integer>
		<key>timescale</key>
		<integer>{}</integer>
		<key>value</key>
		<integer>{}</integer>
	</dict>
	<key>PFVideoComplementMetadataVersionKey</key>
	<string>1</string>
</dict>
</plist>
"#,
            self.timescale, self.duration
        ))
    }
}

/// `moov/mvhd` 的 timescale 与 duration。两样都读不到时按 600 / 0 报——plist 里那段
/// 时间信息不是配对依据，值不精确顶多让「从第几秒开始播」有点偏。
fn timing(video: &[u8]) -> (u32, u64) {
    fn walk(bytes: &[u8], start: usize, end: usize, out: &mut (u32, u64)) {
        let mut at = start;
        while at + 8 <= end {
            let size = u32::from_be_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
                as usize;
            if size < 8 || at + size > end {
                return;
            }
            let kind = &bytes[at + 4..at + 8];
            let body = at + 8;
            if kind == b"moov" {
                walk(bytes, body, at + size, out);
            } else if kind == b"mvhd" {
                let version = bytes[body];
                if version == 0 && body + 20 <= at + size {
                    let timescale = read_u32(bytes, body + 12);
                    let duration = read_u32(bytes, body + 16) as u64;
                    *out = (timescale, duration);
                } else if version == 1 && body + 32 <= at + size {
                    let timescale = read_u32(bytes, body + 20);
                    let duration = u64::from_be_bytes([
                        bytes[body + 24],
                        bytes[body + 25],
                        bytes[body + 26],
                        bytes[body + 27],
                        bytes[body + 28],
                        bytes[body + 29],
                        bytes[body + 30],
                        bytes[body + 31],
                    ]);
                    *out = (timescale, duration);
                }
                return;
            }
            at += size;
        }
    }
    let mut out = (600u32, 0u64);
    walk(video, 0, video.len(), &mut out);
    if out.0 == 0 {
        out.0 = 600;
    }
    out
}

fn read_u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_be_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
}

/// XMP 包本身（`http://ns.adobe.com/xap/1.0/\0` 之后到段尾）。
///
/// 只找 APP1 段：Motion Photo 的 XMP 按规范就在那里，别的段里出现同样的字符串只可能是
/// 图像数据碰巧撞上。
fn xmp_segment(bytes: &[u8]) -> Option<&[u8]> {
    const URI: &[u8] = b"http://ns.adobe.com/xap/1.0/\x00";
    let mut at = 2;
    while at + 4 <= bytes.len() {
        if bytes[at] != 0xFF {
            return None;
        }
        let marker = bytes[at + 1];
        // SOS 之后是压缩数据，段链到此为止；EOI 同理。
        if marker == 0xDA || marker == 0xD9 {
            return None;
        }
        let length = ((bytes[at + 2] as usize) << 8) | bytes[at + 3] as usize;
        if length < 2 || at + 2 + length > bytes.len() {
            return None;
        }
        let body = &bytes[at + 4..at + 2 + length];
        if marker == 0xE1 && body.starts_with(URI) {
            return Some(&body[URI.len()..]);
        }
        at += 2 + length;
    }
    None
}

/// XMP 里有没有「这是一张动图」的声明。
///
/// 两种写法都要认：Google 的 `GCamera:MotionPhoto="1"`（多数 Android 相册），以及我们
/// 合成时照抄的 OPPO 私有命名空间 `OpCamera:OLivePhotoVersion`（ColorOS 相机）。
fn is_motion_photo(xmp: &[u8]) -> bool {
    contains(xmp, b"MotionPhoto=\"1\"") || contains(xmp, b"OLivePhotoVersion")
}

/// XMP 声明的视频字节数。
///
/// 先找 `Semantic="MotionPhoto"` 那一项自己的 `Item:Length`——Container 里第一项是
/// Primary（长度 0），拿错了就会切出 0 字节的视频。找不到再退回 OPPO 的
/// `OpCamera:VideoLength`（它只有一个）。
fn motion_length(xmp: &[u8]) -> Option<usize> {
    if let Some(at) = find(xmp, b"Semantic=\"MotionPhoto\"") {
        if let Some(length) = number_after(&xmp[at..], b"Item:Length=\"") {
            return Some(length);
        }
    }
    number_after(xmp, b"OpCamera:VideoLength=\"")
}

/// `key` 之后第一段十进制数字。
fn number_after(haystack: &[u8], key: &[u8]) -> Option<usize> {
    let at = find(haystack, key)? + key.len();
    let mut value: usize = 0;
    let mut digits = 0;
    for &byte in &haystack[at..] {
        if !byte.is_ascii_digit() {
            break;
        }
        value = value.checked_mul(10)?.checked_add((byte - b'0') as usize)?;
        digits += 1;
    }
    if digits == 0 { None } else { Some(value) }
}

/// MOV 里的 `com.apple.quicktime.content.identifier`。
///
/// 值是一个 36 字符的 UUID 字符串，装在 `moov/meta/ilst` 的 data box 里。这里不解析整棵
/// box 树：先找到键名，再在它之后找第一个形状正确的 UUID——键名和值在同一个 ilst 里，
/// 中间只隔几十字节，而逐个 box 解析要跟着 `keys` 的索引表跳，出错的方式反而更多。
fn content_identifier(video: &[u8]) -> Option<String> {
    const KEY: &[u8] = b"com.apple.quicktime.content.identifier";
    let at = find(video, KEY)?;
    // 值在键名之后；文件名等其它字符串也在附近，所以只认严格 UUID 形状。
    let tail = &video[at + KEY.len()..];
    let end = tail.len().min(512);
    for start in 0..end {
        if let Some(text) = uuid_at(&tail[start..]) {
            return Some(text);
        }
    }
    None
}

/// 从 `at` 开始能不能读出一个 `8-4-4-4-12` 的十六进制 UUID。
fn uuid_at(bytes: &[u8]) -> Option<String> {
    const DASHES: [usize; 4] = [8, 13, 18, 23];
    let mut out = String::with_capacity(36);
    let mut read = 0;
    for (index, &byte) in bytes.iter().enumerate() {
        if index >= 36 {
            break;
        }
        let dash = DASHES.iter().any(|&d| d == index);
        let ok = if dash {
            byte == b'-'
        } else {
            byte.is_ascii_hexdigit()
        };
        if !ok {
            return None;
        }
        out.push(byte as char);
        read += 1;
    }
    if read == 36 { Some(out) } else { None }
}

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    find(haystack, needle).is_some()
}

fn find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || haystack.len() < needle.len() {
        return None;
    }
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 造一张最小的 Motion Photo：APP1（XMP）+ SOS + 数据 + EOI + 视频。
    fn motion_photo(xmp: &str, video: &[u8]) -> Vec<u8> {
        let mut out = vec![0xFF, 0xD8];
        let body = format!("http://ns.adobe.com/xap/1.0/\x00{xmp}");
        let length = body.len() + 2;
        out.extend_from_slice(&[0xFF, 0xE1]);
        out.extend_from_slice(&(length as u16).to_be_bytes());
        out.extend_from_slice(body.as_bytes());
        out.extend_from_slice(&[0xFF, 0xDA, 0x00, 0x02, 0x01, 0x02, 0x03, 0xFF, 0xD9]);
        out.extend_from_slice(video);
        out
    }

    const XMP: &str = "GCamera:MotionPhoto=\"1\" <Container:Item Item:Mime=\"video/mp4\" \
                       Item:Semantic=\"MotionPhoto\" Item:Length=\"5\"/>";

    #[test]
    fn splits_a_motion_photo_at_its_eoi() {
        let file = motion_photo(XMP, b"VIDEO");
        let live = split(&file).expect("this is a motion photo");
        assert_eq!(live.image.last(), Some(&0xD9));
        assert_eq!(live.video, b"VIDEO");
    }

    /// Primary 项的 `Item:Length="0"` 排在前面；拿它就会切出 0 字节的视频。
    #[test]
    fn takes_the_motion_photo_item_not_the_primary_one() {
        let xmp = "GCamera:MotionPhoto=\"1\" \
                   <Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" \
                   Item:Length=\"0\"/><Container:Item Item:Mime=\"video/mp4\" \
                   Item:Semantic=\"MotionPhoto\" Item:Length=\"5\"/>";
        let file = motion_photo(xmp, b"VIDEO");
        assert_eq!(split(&file).expect("still a motion photo").video, b"VIDEO");
    }

    #[test]
    fn refuses_a_plain_photo() {
        let file = motion_photo("<xmpmeta/>", b"");
        assert!(split(&file).is_none());
    }

    /// 声明的长度没有落在 EOI 上：不是这个意思，不能拆。
    #[test]
    fn refuses_when_the_declared_length_misses_the_eoi() {
        let file = motion_photo(XMP, b"VIDEO");
        let mut wrong = file.clone();
        // 把 EOI 前插一个字节，声明长度就对不上了。
        wrong.insert(wrong.len() - 5, 0x00);
        assert!(split(&wrong).is_none());
    }

    #[test]
    fn reads_a_uuid_out_of_the_mov() {
        let mut video = b"...com.apple.quicktime.content.identifier..".to_vec();
        video.extend_from_slice(b"672FB079-402D-4E1E-AECE-67D388C64CE8");
        assert_eq!(
            content_identifier(&video).as_deref(),
            Some("672FB079-402D-4E1E-AECE-67D388C64CE8")
        );
    }

    /// 相机实况：视频里没有配对标识时补一个，而且补完能读回来（否则等于没写）。
    #[test]
    fn adds_an_identifier_to_a_moov_without_one() {
        let uuid = "58485A87-1AEA-41A5-B94D-630384319DA5";
        let mut video = Vec::new();
        video.extend_from_slice(&16u32.to_be_bytes());
        video.extend_from_slice(b"ftyp");
        video.extend_from_slice(b"qt  ");
        video.extend_from_slice(&0u32.to_be_bytes());
        video.extend_from_slice(&12u32.to_be_bytes());
        video.extend_from_slice(b"mdat");
        video.extend_from_slice(b"data");
        video.extend_from_slice(&8u32.to_be_bytes());
        video.extend_from_slice(b"moov");

        let out = with_identifier(&video, uuid).expect("moov without meta gets one");
        assert_eq!(out.len(), video.len() + 172);
        assert_eq!(content_identifier(&out).as_deref(), Some(uuid));
        // moov 自己的 size 要跟着长（28 是 moov 的起点）。
        assert_eq!(
            u32::from_be_bytes([out[28], out[29], out[30], out[31]]),
            (8 + 172) as u32
        );
    }

    /// mdat 在 moov 后面的布局不能改：加长 moov 会把 mdat 推开，stco 里的偏移就全错了。
    #[test]
    fn refuses_when_mdat_follows_moov() {
        let mut video = Vec::new();
        video.extend_from_slice(&16u32.to_be_bytes());
        video.extend_from_slice(b"ftyp");
        video.extend_from_slice(b"qt  ");
        video.extend_from_slice(&0u32.to_be_bytes());
        video.extend_from_slice(&8u32.to_be_bytes());
        video.extend_from_slice(b"moov");
        video.extend_from_slice(&12u32.to_be_bytes());
        video.extend_from_slice(b"mdat");
        video.extend_from_slice(b"data");
        assert!(with_identifier(&video, "58485A87-1AEA-41A5-B94D-630384319DA5").is_none());
    }

    /// 相机拍的图没有 MakerNote：补一份，而且里面的 UUID 必须是本次配对的那一个。
    #[test]
    fn adds_a_makernote_when_the_picture_has_none() {
        let uuid = "58485A87-1AEA-41A5-B94D-630384319DA5";
        let jpeg = b"\xFF\xD8\xFF\xDA\x00\x02\x01\xFF\xD9".to_vec();
        let out = with_makernote(&jpeg, uuid).expect("a plain picture gets one");
        assert!(find(&out, b"Apple iOS").is_some(), "模板被写进去了");
        assert!(find(&out, uuid.as_bytes()).is_some(), "UUID 换成了这一次的");
        assert!(find(&out, TEMPLATE_UUID).is_none(), "模板自带的旧 UUID 不能留着");
        // 段长要自洽：FF E1 之后是两个字节的长度。
        assert_eq!(&out[2..4], &[0xFF, 0xE1]);
        let length = ((out[4] as usize) << 8) | out[5] as usize;
        assert_eq!(length, EXIF_TEMPLATE.len() + 2);
    }

    /// 转发场景：图片自己带着 Apple 的 EXIF，那份比模板准，不许覆盖。
    #[test]
    fn leaves_a_picture_that_already_has_one() {
        let mut jpeg = b"\xFF\xD8".to_vec();
        jpeg.extend_from_slice(b"Apple iOS");
        assert!(with_makernote(&jpeg, "58485A87-1AEA-41A5-B94D-630384319DA5").is_none());
    }

    /// 相机实况：像素已经摆正（`Orientation=1`、3072×4096），模板却是 iPhone 竖拍
    /// （`Orientation=6`、4032×3024）。整段换上去，iPhone 会照着模板再转 90°——接收侧
    /// 早就踩过这个坑（"模板固定 Orientation 会让封面横竖颠倒"），发送侧不能重演。
    #[test]
    fn keeps_the_source_orientation_and_size() {
        let source = camera_picture();
        let before = frame(&source).expect("样本自己得先读对");
        assert_eq!(
            (before.orientation, before.width, before.height),
            (1, 3072, 4096)
        );
        let out = with_makernote(&source, "58485A87-1AEA-41A5-B94D-630384319DA5")
            .expect("相机图没有 MakerNote，要补");
        let read = frame(&out).expect("补完还得是一张能读出尺寸的图");
        assert_eq!(read.orientation, 1, "方向必须继承源图，模板的 6 不能用");
        assert_eq!((read.width, read.height), (3072, 4096), "尺寸也要跟着源图");
        // ExifIFD 那两条是 iPhone 排版时看的，同样要改。
        let tiff = &out[12..];
        let (big, ifd0) = tiff_head(tiff).expect("模板自己的 TIFF 头");
        let (_, exif) = entry(tiff, ifd0, big, 0x8769).expect("ExifIFD 指针");
        assert_eq!(
            entry(tiff, exif as usize, big, 0xA002).map(|(_, v)| v),
            Some(3072)
        );
        assert_eq!(
            entry(tiff, exif as usize, big, 0xA003).map(|(_, v)| v),
            Some(4096)
        );
    }

    /// 一张和相机输出同形的图：EXIF 里只有 `Orientation=1`，SOF0 里 3072×4096。
    fn camera_picture() -> Vec<u8> {
        let mut jpeg = b"\xFF\xD8".to_vec();
        let mut exif = b"Exif\x00\x00MM\x00\x2A\x00\x00\x00\x08".to_vec();
        exif.extend_from_slice(&1u16.to_be_bytes()); // IFD0 条目数
        exif.extend_from_slice(&0x0112u16.to_be_bytes());
        exif.extend_from_slice(&3u16.to_be_bytes()); // SHORT
        exif.extend_from_slice(&1u32.to_be_bytes()); // count
        exif.extend_from_slice(&[0x00, 0x01, 0x00, 0x00]); // 值 1
        exif.extend_from_slice(&0u32.to_be_bytes()); // 没有下一层 IFD
        jpeg.extend_from_slice(&[0xFF, 0xE1]);
        jpeg.extend_from_slice(&((exif.len() + 2) as u16).to_be_bytes());
        jpeg.extend_from_slice(&exif);
        // SOF0：精度 8、高 4096、宽 3072、三个分量。
        jpeg.extend_from_slice(&[0xFF, 0xC0, 0x00, 0x11, 0x08, 0x10, 0x00, 0x0C, 0x00, 0x03]);
        jpeg.extend_from_slice(&[0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01]);
        jpeg.extend_from_slice(&[0xFF, 0xDA, 0x00, 0x02, 0x01, 0xFF, 0xD9]);
        jpeg
    }

    #[test]
    fn no_identifier_is_not_an_error() {
        assert_eq!(content_identifier(b"no keys here"), None);
    }
}
