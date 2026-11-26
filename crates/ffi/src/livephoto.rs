//! 发送侧的实况照片：从一张 Motion Photo 里拆出图片与视频，并读出 Apple 的配对标识。
//!
//! # 为什么发送要拆
//!
//! 我们收到 iPhone 的实况时合成的是 **Android Motion Photo**（JPEG 尾部嵌视频，XMP 里
//! 带 `MotionPhoto=1`）。那是给 Android 相册看的格式，而 iPhone 认的是**两个文件**：
//! 一张图片 + 一个同名的 MOV，两者带**相同的 `ContentIdentifier`**（UUID）。所以把合成
//! 图原样发过去，iPhone 只会把它当成一张普通照片——视频就在里面，但它不会去找。
//!
//! 拆开之后还要把配对标识**补回图片一侧**：我们合成时用 OPPO 的 EXIF 模板替换了原图的
//! EXIF，Apple 写在那里的 MakerNote（含 `ContentIdentifier`）随之丢失，而 MOV 里
//! `com.apple.quicktime.content.identifier` 还在。两边不一致，iPhone 就不配对——这一步
//! 是发送侧能成立的关键，见 [`LivePhoto::identifier`]。
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
    let video = bytes[cut..].to_vec();
    let (timescale, duration) = timing(&video);
    Some(LivePhoto {
        image: bytes[..cut].to_vec(),
        identifier: content_identifier(&video),
        timescale,
        duration,
        video,
    })
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

    #[test]
    fn no_identifier_is_not_an_error() {
        assert_eq!(content_identifier(b"no keys here"), None);
    }
}
