//! Kerio / OpenControl client session using opencontrol-protocol.

use crate::tls_pin::{self, TlsStream};
use bytes::Bytes;
use opencontrol_protocol::connection::{PeerEndpoint, ProtocolSession, SessionEvent, SessionProfile};
use opencontrol_protocol::crypto::SessionKey;
use opencontrol_protocol::datagram::{Datagram, NetworkPacket};
use opencontrol_protocol::liveness::CloseReason;
use opencontrol_protocol::s2s::Flow;
use opencontrol_protocol::session::SessionEpoch;
use opencontrol_protocol::{
    CipherConfig, ClientMetadata, ControlMessage, Credential, InitialConfig, LegacyPolicy,
    ProtocolDecoder, ProtocolEncoder, ProtocolLimits, Role, WireVersion,
};
use std::io::{ErrorKind, Read, Write};
use std::net::{Ipv4Addr, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

pub trait Platform: Send {
    fn configure_tun(
        &mut self,
        address: Ipv4Addr,
        prefix: u8,
        dns: &[Ipv4Addr],
        routes: &[String],
        mtu: i32,
    ) -> Result<i32, String>;

    fn on_status(&mut self, status: &str, detail: &str);
    fn on_routes(&mut self, routes: &[String]);
    fn protect_socket(&mut self, fd: i32) -> bool;
}

#[derive(Clone)]
pub struct ConnectParams {
    pub host: String,
    pub port: u16,
    pub username: String,
    pub password: String,
    pub fingerprint_sha256: String,
}

static STOP: AtomicBool = AtomicBool::new(false);
static RUNNING: AtomicBool = AtomicBool::new(false);

pub fn request_stop() {
    STOP.store(true, Ordering::SeqCst);
}

pub fn is_running() -> bool {
    RUNNING.load(Ordering::SeqCst)
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn prefix_from_mask(mask: Ipv4Addr) -> u8 {
    u32::from(mask).count_ones() as u8
}

fn ipv4(bytes: [u8; 4]) -> Ipv4Addr {
    Ipv4Addr::new(bytes[0], bytes[1], bytes[2], bytes[3])
}

fn route_cidrs(cfg: &opencontrol_protocol::RouteConfiguration) -> Vec<String> {
    cfg.routes()
        .iter()
        .map(|r| {
            let net = ipv4(r.network);
            let prefix = prefix_from_mask(ipv4(r.netmask));
            format!("{net}/{prefix}")
        })
        .collect()
}

fn send_control(
    tls: &mut TlsStream,
    session: &mut ProtocolSession,
    epoch: SessionEpoch,
    encoder: &ProtocolEncoder,
    message: &ControlMessage,
    version: WireVersion,
) -> Result<(), String> {
    let frame = encoder
        .encode(message, version)
        .map_err(|e| format!("encode: {e}"))?;
    tls.write_all(&frame)
        .map_err(|e| format!("TLS write: {e}"))?;
    tls.flush().map_err(|e| format!("TLS flush: {e}"))?;
    session
        .observe(epoch, Flow::Outgoing, message, now_ms())
        .map_err(|e| format!("observe out: {e}"))?;
    Ok(())
}

fn try_read_control(
    tls: &mut TlsStream,
    decoder: &mut ProtocolDecoder,
    version: WireVersion,
    limits: ProtocolLimits,
) -> Result<Option<ControlMessage>, String> {
    if let Some(frame) = decoder
        .next_frame()
        .map_err(|e| format!("decode frame: {e}"))?
    {
        return ControlMessage::decode(frame, version, limits)
            .map(Some)
            .map_err(|e| format!("decode message: {e}"));
    }

    let mut buf = [0u8; 4096];
    match tls.read(&mut buf) {
        Ok(0) => Err("TLS closed by peer".into()),
        Ok(n) => {
            let mut remaining = &buf[..n];
            while !remaining.is_empty() {
                let consumed = decoder
                    .push_bytes(remaining)
                    .map_err(|e| format!("push_bytes: {e}"))?;
                remaining = &remaining[consumed..];
            }
            if let Some(frame) = decoder
                .next_frame()
                .map_err(|e| format!("decode frame: {e}"))?
            {
                ControlMessage::decode(frame, version, limits)
                    .map(Some)
                    .map_err(|e| format!("decode message: {e}"))
            } else {
                Ok(None)
            }
        }
        Err(e) if e.kind() == ErrorKind::WouldBlock || e.kind() == ErrorKind::TimedOut => Ok(None),
        Err(e) => Err(format!("TLS read: {e}")),
    }
}

fn read_control_blocking(
    tls: &mut TlsStream,
    decoder: &mut ProtocolDecoder,
    version: WireVersion,
    limits: ProtocolLimits,
    timeout: Duration,
) -> Result<ControlMessage, String> {
    let deadline = Instant::now() + timeout;
    loop {
        if STOP.load(Ordering::SeqCst) {
            return Err("cancelled".into());
        }
        if Instant::now() > deadline {
            return Err("control read timeout".into());
        }
        match try_read_control(tls, decoder, version, limits)? {
            Some(msg) => return Ok(msg),
            None => std::thread::sleep(Duration::from_millis(20)),
        }
    }
}

pub fn run(params: ConnectParams, mut platform: Box<dyn Platform>) -> Result<(), String> {
    if RUNNING.swap(true, Ordering::SeqCst) {
        return Err("already running".into());
    }
    STOP.store(false, Ordering::SeqCst);
    let result = run_inner(params, &mut *platform);
    RUNNING.store(false, Ordering::SeqCst);
    if let Err(ref e) = result {
        platform.on_status("error", e);
    }
    result
}

fn run_inner(params: ConnectParams, platform: &mut dyn Platform) -> Result<(), String> {
    platform.on_status("connecting", &format!("{}:{}", params.host, params.port));

    let mut tls =
        tls_pin::connect_pinned(&params.host, params.port, &params.fingerprint_sha256)?;
    let tcp_fd = tls_pin::tcp_raw_fd(tls.get_ref());
    if tcp_fd >= 0 && !platform.protect_socket(tcp_fd) {
        return Err("protect(TCP) failed".into());
    }
    tls.get_ref()
        .set_read_timeout(Some(Duration::from_millis(200)))
        .ok();

    let limits = ProtocolLimits::default();
    let mut decoder = ProtocolDecoder::new(limits);
    let encoder = ProtocolEncoder::new(limits);
    let mut session = ProtocolSession::new(
        SessionProfile::ClientServer(Role::Client),
        WireVersion::V4,
        limits,
        LegacyPolicy::Disabled,
        now_ms(),
        30,
    )
    .map_err(|e| format!("session: {e}"))?;
    let epoch = session.epoch();

    send_control(
        &mut tls,
        &mut session,
        epoch,
        &encoder,
        &ControlMessage::Version(WireVersion::V4),
        WireVersion::V4,
    )?;
    let reply = read_control_blocking(
        &mut tls,
        &mut decoder,
        WireVersion::V4,
        limits,
        Duration::from_secs(20),
    )?;
    let negotiated = match &reply {
        ControlMessage::Version(v) => *v,
        other => return Err(format!("expected VERSION, got opcode {}", other.opcode())),
    };
    session
        .observe(epoch, Flow::Incoming, &reply, now_ms())
        .map_err(|e| format!("observe VERSION: {e}"))?;

    let user = ControlMessage::Username(
        Credential::from_utf8(&params.username, limits).map_err(|e| format!("username: {e}"))?,
    );
    send_control(&mut tls, &mut session, epoch, &encoder, &user, negotiated)?;
    let reply = read_control_blocking(
        &mut tls,
        &mut decoder,
        negotiated,
        limits,
        Duration::from_secs(20),
    )?;
    if !matches!(reply, ControlMessage::Ok(_)) {
        return Err("username rejected".into());
    }
    session
        .observe(epoch, Flow::Incoming, &reply, now_ms())
        .map_err(|e| format!("observe user OK: {e}"))?;

    let pass = ControlMessage::Password(
        Credential::from_utf8(&params.password, limits).map_err(|e| format!("password: {e}"))?,
    );
    send_control(&mut tls, &mut session, epoch, &encoder, &pass, negotiated)?;
    let reply = read_control_blocking(
        &mut tls,
        &mut decoder,
        negotiated,
        limits,
        Duration::from_secs(20),
    )?;
    match &reply {
        ControlMessage::Ok(_) => {}
        ControlMessage::Error(_) => return Err("authentication failed".into()),
        other => {
            return Err(format!(
                "unexpected password reply opcode {}",
                other.opcode()
            ))
        }
    }
    session
        .observe(epoch, Flow::Incoming, &reply, now_ms())
        .map_err(|e| format!("observe pass OK: {e}"))?;

    let key = SessionKey::generate().map_err(|e| format!("keygen: {e}"))?;
    let metadata = ClientMetadata::new([0, 9, 0], 1, 3, "OpenKerio Android")
        .map_err(|e| format!("metadata: {e}"))?;
    let initial = ControlMessage::InitialConfig(InitialConfig {
        cep: 30,
        metadata: matches!(negotiated, WireVersion::V3 | WireVersion::V4).then_some(metadata),
        cipher: CipherConfig { slot: 0, key },
    });
    send_control(
        &mut tls,
        &mut session,
        epoch,
        &encoder,
        &initial,
        negotiated,
    )?;

    let reply = read_control_blocking(
        &mut tls,
        &mut decoder,
        negotiated,
        limits,
        Duration::from_secs(20),
    )?;
    let ip = match &reply {
        ControlMessage::IpConfig(cfg) => (**cfg).clone(),
        ControlMessage::Error(_) => return Err("IP config rejected".into()),
        other => return Err(format!("expected IP_CONFIG, got opcode {}", other.opcode())),
    };
    session
        .observe(epoch, Flow::Incoming, &reply, now_ms())
        .map_err(|e| format!("observe IP_CONFIG: {e}"))?;

    let address = ipv4(ip.address);
    let prefix = prefix_from_mask(ipv4(ip.netmask));
    let dns: Vec<Ipv4Addr> = ip.client_dns_servers().into_iter().map(ipv4).collect();

    let udp = UdpSocket::bind("0.0.0.0:0").map_err(|e| format!("UDP bind: {e}"))?;
    udp.set_nonblocking(true)
        .map_err(|e| format!("UDP nonblocking: {e}"))?;
    #[cfg(unix)]
    {
        use std::os::fd::AsRawFd;
        if !platform.protect_socket(udp.as_raw_fd()) {
            return Err("protect(UDP) failed".into());
        }
    }

    let peer_addr = std::net::ToSocketAddrs::to_socket_addrs(&(&params.host[..], params.port))
        .map_err(|e| format!("resolve: {e}"))?
        .find(|a| a.is_ipv4())
        .ok_or_else(|| "no IPv4 address for server".to_string())?;
    udp.connect(peer_addr)
        .map_err(|e| format!("UDP connect: {e}"))?;

    let peer = PeerEndpoint {
        address: match peer_addr.ip() {
            std::net::IpAddr::V4(v4) => v4.octets(),
            _ => return Err("IPv6 peer unsupported".into()),
        },
        port: peer_addr.port(),
    };
    session
        .bind_endpoint(epoch, peer, &ip.secret)
        .map_err(|e| format!("bind_endpoint: {e}"))?;

    let connect_bytes = Datagram::Connect(ip.secret.clone())
        .encode(limits)
        .map_err(|e| format!("encode CONNECT: {e}"))?;

    let bind_deadline = Instant::now() + Duration::from_secs(15);
    let mut last_connect = Instant::now() - Duration::from_secs(5);
    while !session.active() {
        if STOP.load(Ordering::SeqCst) {
            return Err("cancelled".into());
        }
        if Instant::now() > bind_deadline {
            return Err("UDP binding timeout".into());
        }
        if last_connect.elapsed() > Duration::from_millis(500) {
            let _ = udp.send(&connect_bytes);
            last_connect = Instant::now();
        }
        if let Some(msg) = try_read_control(&mut tls, &mut decoder, negotiated, limits)? {
            session
                .observe(epoch, Flow::Incoming, &msg, now_ms())
                .map_err(|e| format!("observe bind: {e}"))?;
        } else {
            std::thread::sleep(Duration::from_millis(50));
        }
    }

    let mut routes = session
        .routes()
        .current()
        .map(route_cidrs)
        .unwrap_or_default();
    if routes.is_empty() {
        routes.push(format!("{address}/{prefix}"));
        routes.push("0.0.0.0/1".into());
        routes.push("128.0.0.0/1".into());
    }

    let tun_fd = platform.configure_tun(address, prefix, &dns, &routes, 1400)?;
    if tun_fd < 0 {
        return Err("VpnService.establish failed".into());
    }
    platform.on_status("connected", &address.to_string());

    #[cfg(unix)]
    set_nonblock(tun_fd);

    let mut tun = unsafe { std::fs::File::from_raw_fd_checked(tun_fd)? };
    // Prevent Drop from closing Android-owned PFD.
    let mut tun_buf = [0u8; 2048];
    let mut udp_buf = [0u8; 8192];
    let mut last_control_ka = Instant::now();

    while !STOP.load(Ordering::SeqCst) {
        let now = now_ms();
        let events = match session.poll(epoch, now) {
            Ok(e) => e,
            Err(e) => {
                log::warn!("poll error: {e}");
                break;
            }
        };
        if events.close.is_some() {
            break;
        }
        if events.control_keepalive_due || last_control_ka.elapsed() > Duration::from_secs(60) {
            let _ = send_control(
                &mut tls,
                &mut session,
                epoch,
                &encoder,
                &ControlMessage::KeepaliveRequest,
                negotiated,
            );
            last_control_ka = Instant::now();
        }
        if events.udp_keepalive_due {
            if let Ok(ka) = Datagram::Keepalive.encode(limits) {
                let _ = udp.send(&ka);
            }
        }
        if events.rekey_due {
            if let (Ok(key), Some(primary)) = (SessionKey::generate(), session.keys().primary_slot())
            {
                let slot = if primary == 0 { 1 } else { 0 };
                let msg = ControlMessage::KeyChange(CipherConfig { slot, key });
                let _ = send_control(&mut tls, &mut session, epoch, &encoder, &msg, negotiated);
            }
        }

        match try_read_control(&mut tls, &mut decoder, negotiated, limits) {
            Ok(Some(msg)) => {
                match session.observe(epoch, Flow::Incoming, &msg, now_ms()) {
                    Ok(SessionEvent::Routes { complete: true, .. }) => {
                        if let Some(cfg) = session.routes().current() {
                            let list = route_cidrs(cfg);
                            platform.on_routes(&list);
                        }
                    }
                    Ok(SessionEvent::KeyInstalled {
                        ack_required: true, ..
                    }) => {
                        let ack = ControlMessage::KeyChanged(Bytes::from_static(&[0]));
                        let _ = send_control(
                            &mut tls,
                            &mut session,
                            epoch,
                            &encoder,
                            &ack,
                            negotiated,
                        );
                    }
                    Ok(SessionEvent::PeerRejected) => break,
                    Ok(_) => {}
                    Err(e) => {
                        log::warn!("observe error: {e}");
                        break;
                    }
                }
            }
            Ok(None) => {}
            Err(e) if e == "cancelled" || e.starts_with("TLS closed") => break,
            Err(e) => {
                log::warn!("control read: {e}");
                break;
            }
        }

        match udp.recv(&mut udp_buf) {
            Ok(n) => {
                let bytes = Bytes::copy_from_slice(&udp_buf[..n]);
                if let Ok(decoded) = session.open_from(epoch, peer, bytes, now_ms()) {
                    let _ = tun.write_all(&decoded.packet.payload);
                }
            }
            Err(e) if e.kind() == ErrorKind::WouldBlock => {}
            Err(_) => {}
        }

        match tun.read(&mut tun_buf) {
            Ok(0) => break,
            Ok(n) => {
                let packet = NetworkPacket {
                    ether_type: 0x0800,
                    payload: Bytes::copy_from_slice(&tun_buf[..n]),
                };
                if let Ok(sealed) = session.seal(epoch, &packet, now_ms()) {
                    let _ = udp.send(&sealed);
                }
            }
            Err(e) if e.kind() == ErrorKind::WouldBlock || e.kind() == ErrorKind::Interrupted => {}
            Err(_) => std::thread::sleep(Duration::from_millis(5)),
        }
    }

    let _ = session.close(now_ms(), CloseReason::Cancelled);
    // Leak the File handle so we do not close Android's ParcelFileDescriptor.
    std::mem::forget(tun);
    platform.on_status("disconnected", "");
    Ok(())
}

#[cfg(unix)]
fn set_nonblock(fd: i32) {
    use std::os::fd::RawFd;
    let fd = fd as RawFd;
    unsafe {
        let flags = libc::fcntl(fd, libc::F_GETFL);
        if flags >= 0 {
            let _ = libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
        }
    }
}

trait FromRawFdChecked: Sized {
    unsafe fn from_raw_fd_checked(fd: i32) -> Result<Self, String>;
}

impl FromRawFdChecked for std::fs::File {
    unsafe fn from_raw_fd_checked(fd: i32) -> Result<Self, String> {
        if fd < 0 {
            return Err("invalid TUN fd".into());
        }
        #[cfg(unix)]
        {
            use std::os::fd::{FromRawFd, OwnedFd};
            let owned = OwnedFd::from_raw_fd(fd);
            Ok(std::fs::File::from(owned))
        }
        #[cfg(not(unix))]
        {
            let _ = fd;
            Err("TUN only supported on Unix/Android".into())
        }
    }
}

pub fn probe(host: &str, port: u16) -> Result<String, String> {
    tls_pin::probe_fingerprint(host, port)
}
