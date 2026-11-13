//! The endpoint over a real socket, driven by a client that writes the bytes a peer writes.
//!
//! The unit tests in `server.rs` prove the framing decisions against a slice. These prove
//! that the two properties the protocol actually depends on survive a real TCP connection:
//! that a `200` reaches a peer whose body has not arrived, and that an archive of the sizes
//! a photo and a video actually are arrives byte for byte.
//!
//! The client is written by hand rather than reusing anything, because a test client that
//! shares code with the server can only prove the server agrees with itself.

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::thread;

use opairdrop_airdrop::payload::{discover_request, Item, Sender};
use opairdrop_airdrop::server::{Receiver, Upload};
use opairdrop_airdrop::{
    ask_request, offered_bytes, Ask, AskAnswer, Client, DiscoverAnswer, Server, TransferId,
};

/// What the far end of the socket saw.
#[derive(Default)]
struct Received {
    senders: Vec<String>,
    offers: Vec<Ask>,
    archives: Vec<Vec<u8>>,
    uploads: Vec<Uploaded>,
}

/// The head of an upload, as the receiver read it.
///
/// A named struct rather than a tuple because the three facts are what UP-9 and UP-4 are
/// about, and `.1` in an assertion says nothing about which of them failed.
struct Uploaded {
    content_type: String,
    /// Whether the `TransferID` matched the offer on the same connection.
    matches_offer: Option<bool>,
}

struct Sink {
    received: Received,
}

impl Receiver for Sink {
    fn discover(&mut self) -> DiscoverAnswer {
        DiscoverAnswer::new("Air")
    }

    fn ask(&mut self, _offer: Option<&Ask>) -> AskAnswer {
        AskAnswer::new("Air", "OPPO PJZ110")
    }

    fn saw_sender(&mut self, sender: &Sender) {
        self.received.senders.push(sender.computer_name.clone());
    }

    fn saw_ask(&mut self, offer: &Ask) {
        self.received.offers.push(offer.clone());
    }

    fn upload(&mut self, body: &mut dyn Read, upload: &Upload<'_>) -> std::io::Result<()> {
        let mut archive = Vec::new();
        body.read_to_end(&mut archive)?;
        self.received.archives.push(archive);
        self.received.uploads.push(Uploaded {
            content_type: upload.content_type.to_string(),
            matches_offer: upload.matches_offer,
        });
        Ok(())
    }
}

/// A listener with one server thread behind it, and the address to reach it on.
///
/// The thread hands back the whole server so the test can read what its receiver was told;
/// returning a copy would mean copying a three-megabyte archive to check its length.
fn listen() -> (TcpListener, thread::JoinHandle<Server<Sink>>) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("binds");
    let handle = {
        let listener = listener.try_clone().expect("clones the listener");
        thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accepts");
            let mut reader = stream.try_clone().expect("clones the socket");
            let mut writer = stream;
            let mut server = Server::new(Sink {
                received: Received::default(),
            });
            server
                .serve(&mut reader, &mut writer)
                .expect("serves the connection");
            server
        })
    };
    (listener, handle)
}

fn connect(listener: &TcpListener) -> TcpStream {
    let stream = TcpStream::connect(listener.local_addr().expect("an address")).expect("connects");
    // A hung test is worse than a failing one.
    stream
        .set_read_timeout(Some(std::time::Duration::from_secs(10)))
        .expect("sets a timeout");
    stream.set_nodelay(true).expect("disables Nagle");
    stream
}

/// One response: its status, and its body when it declared a length.
struct Reply {
    status: u16,
    body: Vec<u8>,
}

/// Reads exactly one response, using its own `Content-Length`.
///
/// A `1xx` carries no length at all, so it is recognised by its status and ends at the
/// blank line.
fn read_reply(stream: &mut TcpStream) -> Reply {
    let mut head = Vec::new();
    let mut byte = [0u8; 1];
    while !head.ends_with(b"\r\n\r\n") {
        let read = stream.read(&mut byte).expect("reads the head");
        assert_ne!(read, 0, "the connection closed mid-head");
        head.push(byte[0]);
    }
    let text = String::from_utf8_lossy(&head).to_string();
    let status: u16 = text
        .split_whitespace()
        .nth(1)
        .expect("a status code")
        .parse()
        .expect("a number");
    if (100..200).contains(&status) {
        return Reply {
            status,
            body: Vec::new(),
        };
    }
    let length: usize = text
        .lines()
        .find_map(|line| {
            line.strip_prefix("Content-Length: ")
                .map(|value| value.trim().parse().expect("a length"))
        })
        .expect("a length on a non-1xx reply");
    let mut body = vec![0u8; length];
    stream.read_exact(&mut body).expect("reads the body");
    Reply { status, body }
}

/// A chunked upload body: 64 KiB blocks, which is what a phone sends.
fn chunked(payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(payload.len() + payload.len() / 65536 * 8 + 16);
    for block in payload.chunks(64 * 1024) {
        out.extend_from_slice(format!("{:x}\r\n", block.len()).as_bytes());
        out.extend_from_slice(block);
        out.extend_from_slice(b"\r\n");
    }
    out.extend_from_slice(b"0\r\n\r\n");
    out
}

fn id() -> TransferId {
    TransferId::from_bytes16(&[0x5c; 16])
}

#[test]
fn the_three_endpoints_walk_over_one_connection() {
    let (listener, handle) = listen();
    let mut stream = connect(&listener);

    let sender = Sender::new("iPhone von Ada", "iPhone", "8A2B:1F:9C");
    let discover = discover_request(&sender);
    write!(
        stream,
        "POST /Discover HTTP/1.1\r\nHost: air\r\nContent-Type: application/octet-stream\r\nContent-Length: {}\r\n\r\n",
        discover.len()
    )
    .expect("writes the head");
    stream.write_all(&discover).expect("writes the body");
    let reply = read_reply(&mut stream);
    assert_eq!(reply.status, 200);
    let answer = DiscoverAnswer::parse(&reply.body).expect("a plist");
    assert!(answer.airdropable);
    assert_eq!(answer.computer_name, "Air");

    let files = [Item::new("a.jpg").with_size(3_000_019), Item::new("empty.txt").with_size(0)];
    let ask = ask_request(&sender, &id(), &files, &[]);
    write!(
        stream,
        "POST /Ask HTTP/1.1\r\nHost: air\r\nContent-Type: application/octet-stream\r\nContent-Length: {}\r\n\r\n",
        ask.len()
    )
    .expect("writes the head");
    stream.write_all(&ask).expect("writes the body");
    let reply = read_reply(&mut stream);
    assert_eq!(reply.status, 200);
    let answer = AskAnswer::parse(&reply.body).expect("a plist");
    assert_eq!(answer.model_name, "OPPO PJZ110");

    let archive = chunked(b"07070100000000TRAILER");
    write!(
        stream,
        "POST /Upload HTTP/1.1\r\nHost: air\r\nContent-Type: application/x-dvzip\r\nTransfer-Encoding: chunked\r\nTransferID: {}\r\nExpect: 100-continue\r\n\r\n",
        id()
    )
    .expect("writes the head");
    let reply = read_reply(&mut stream);
    assert_eq!(reply.status, 100, "the peer releases its body on this line");
    stream.write_all(&archive).expect("writes the archive");
    let reply = read_reply(&mut stream);
    assert_eq!(reply.status, 200, "the archive was accepted");
    assert!(reply.body.is_empty(), "an upload answer carries no body");

    drop(stream);
    let server = handle.join().expect("the server thread finishes");
    let received = &server.receiver().received;
    assert_eq!(received.senders, ["iPhone von Ada"]);
    assert_eq!(received.offers.len(), 1);
    assert_eq!(received.offers[0].files.len(), 2);
    assert_eq!(received.offers[0].files[0].size, Some(3_000_019));
    assert_eq!(received.archives.len(), 1);
    assert_eq!(received.archives[0], b"07070100000000TRAILER");
}

/// 3 MiB plus seventeen bytes: the size a video is, on a link that drops long frames.
///
/// The byte count is not decoration. A chunk decoder that loses the last block's tail, or
/// an upload path that trusts `Content-Length` over the framing, produces an archive that
/// is short by a few bytes and a member that unpacks as a truncated file.
#[test]
fn three_megabytes_and_seventeen_bytes_arrive_whole() {
    let (listener, handle) = listen();
    let mut stream = connect(&listener);

    let payload: Vec<u8> = (0..3 * 1024 * 1024 + 17)
        .map(|index| (index % 251) as u8)
        .collect();
    let body = chunked(&payload);
    write!(
        stream,
        "POST /Upload HTTP/1.1\r\nHost: air\r\nContent-Type: application/x-cpio\r\nTransfer-Encoding: chunked\r\n\r\n"
    )
    .expect("writes the head");
    // A peer that declared chunked has no length to read, so the archive goes out as one
    // stream and the answer arrives after the last block.
    stream.write_all(&body).expect("writes the archive");
    let reply = read_reply(&mut stream);
    assert_eq!(reply.status, 200);

    drop(stream);
    let server = handle.join().expect("the server thread finishes");
    let received = &server.receiver().received;
    assert_eq!(received.archives.len(), 1);
    assert_eq!(received.archives[0].len(), payload.len());
    assert!(
        received.archives[0] == payload,
        "the archive arrived with a difference somewhere"
    );
}

/// The property the whole endpoint exists for: a `200` reaches a peer whose body has not.
#[test]
fn a_head_with_no_body_is_answered_while_the_socket_stays_open() {
    let (listener, handle) = listen();
    let mut stream = connect(&listener);

    // A `Content-Length` of 400 bytes of plist, none of which is ever sent.
    write!(
        stream,
        "POST /Discover HTTP/1.1\r\nHost: air\r\nContent-Length: 400\r\n\r\n"
    )
    .expect("writes the head");

    // Read the answer without writing another byte. If the endpoint waited for the body,
    // this read would block until the timeout and the test would fail on `expect`.
    let reply = read_reply(&mut stream);
    assert_eq!(reply.status, 200);
    assert!(DiscoverAnswer::parse(&reply.body).is_ok());

    drop(stream);
    let server = handle.join().expect("the server thread finishes");
    let received = &server.receiver().received;
    assert!(
        received.senders.is_empty(),
        "a body that never arrived names nobody"
    );
}

/// A peer that frames an offer and then hangs up gets no answer it can use, and no panic.
#[test]
fn an_offer_whose_body_is_cut_off_ends_the_connection_quietly() {    let (listener, handle) = listen();
    let mut stream = connect(&listener);

    let ask = ask_request(&Sender::new("Mac", "MacBook", "id"), &id(), &[Item::new("a")], &[]);
    write!(
        stream,
        "POST /Ask HTTP/1.1\r\nHost: air\r\nContent-Length: {}\r\n\r\n",
        ask.len()
    )
    .expect("writes the head");
    // Half of the body, then a close: the peer gave up mid-frame.
    stream
        .write_all(&ask[..ask.len() / 2])
        .expect("writes half a body");
    drop(stream);

    let server = handle.join().expect("the server thread finishes");
    let received = &server.receiver().received;
    assert!(received.offers.is_empty(), "a half offer is not an offer");
}

/// Our sender and our receiver, one connection, one archive carrying both size boundaries.
///
/// The archive holds a member with no bytes in it and a member of 3 MiB plus seventeen
/// bytes, because those are the two places an off-by-one in framing hides: an empty body is
/// the one that a `Content-Length: 0` fallback silently turns into "no upload", and the
/// odd-sized one is what a decoder that rounds to a block boundary loses its tail to.
///
/// The path the bytes take is the real one end to end — [`Client`] frames chunked, the
/// socket carries it, [`Server`] de-chunks it, and the archive is then DVZip-decoded and
/// parsed as cpio. Nothing here is a stand-in for anything else, which is the only reason a
/// pass means a transfer works.
#[test]
fn a_sender_and_a_receiver_move_one_archive_the_whole_way() {
    let (listener, handle) = listen();
    let stream = connect(&listener);

    let payload: Vec<u8> = (0..3 * 1024 * 1024 + 17)
        .map(|index| (index % 251) as u8)
        .collect();
    let entries = vec![
        opairdrop_cpio::Entry::file("empty.txt", Vec::new()),
        opairdrop_cpio::Entry::file("video.mov", payload.clone()),
    ];
    let archive =
        opairdrop_cpio::to_vec(&entries, opairdrop_cpio::Format::Newc).expect("builds an archive");

    let sender = Sender::new("OPPO Find X", "OPPO PJZ110", "1A2B:3C4D");
    let transfer_id = id();
    let files = [
        Item::new("empty.txt").with_size(0),
        Item::new("video.mov").with_size(payload.len() as u64),
    ];

    let mut client = Client::new(stream, "air.local");
    let found = client.discover(&sender).expect("the receiver answers");
    assert_eq!(found.computer_name, "Air");
    // The name a progress dialog would show, taken from the answer rather than invented.
    let names = client
        .ask(&sender, &transfer_id, &files, &[])
        .expect("the offer is accepted");
    assert_eq!(names.model_name, "OPPO PJZ110");

    let offered = offered_bytes(&files);
    assert_eq!(
        offered,
        payload.len() as u64,
        "an empty member contributes nothing to UP-10's total"
    );
    client
        .upload(&transfer_id, offered, |out| {
            // The archive goes out through DVZip, which is what UP-1b requires of a sender
            // that does not know the receiver's codecs.
            let mut encoder = opairdrop_cpio::dvzip::Encoder::new(out);
            encoder.write_all(&archive)?;
            encoder.finish()
        })
        .expect("the archive is accepted");

    // The receiver closed after the upload, which is the end of a conversation by design.
    drop(client.into_inner());

    let server = handle.join().expect("the server thread finishes");
    let received = &server.receiver().received;
    assert_eq!(received.senders, ["OPPO Find X"]);

    assert_eq!(received.offers.len(), 1);
    let offer = &received.offers[0];
    assert_eq!(offer.files.len(), 2);
    assert_eq!(offer.files[0].size, Some(0));
    assert_eq!(offer.files[1].size, Some(payload.len() as u64));
    assert_eq!(
        offer.transfer_id.as_deref(),
        Some(transfer_id.as_str()),
        "the offer carries the identifier the upload will repeat"
    );

    // UP-9 and UP-4, as the receiver saw them.
    assert_eq!(received.uploads.len(), 1);
    assert_eq!(received.uploads[0].content_type, "application/x-dvzip");
    assert_eq!(
        received.uploads[0].matches_offer,
        Some(true),
        "the TransferID header repeated what /Ask promised"
    );

    // De-chunked by the server, and now DVZip-decoded back into the archive that was sent.
    let mut decoded = Vec::new();
    opairdrop_cpio::dvzip::Decoder::new(received.archives[0].as_slice())
        .read_to_end(&mut decoded)
        .expect("the DVZip block sequence is intact");
    assert_eq!(decoded, archive, "the archive arrived byte for byte");

    let unpacked = opairdrop_cpio::read(&decoded).expect("the cpio archive parses");
    assert_eq!(unpacked.len(), 2);
    assert_eq!(unpacked[0].name, "empty.txt");
    assert!(unpacked[0].data.is_empty(), "the empty member stayed empty");
    assert_eq!(unpacked[1].data.len(), payload.len());
    assert!(
        unpacked[1].data == payload,
        "the video arrived with a difference somewhere"
    );
}

