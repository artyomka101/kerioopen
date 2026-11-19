//! TLS connector with SHA-256 certificate pinning (Kerio often uses a private CA).

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, Error, SignatureScheme};
use sha2::{Digest, Sha256};
use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::Arc;
use std::time::Duration;

#[derive(Debug)]
struct PinVerifier {
    expected_sha256_hex: String,
}

impl PinVerifier {
    fn fingerprint_hex(cert: &CertificateDer<'_>) -> String {
        let digest = Sha256::digest(cert.as_ref());
        hex::encode(digest)
    }
}

impl ServerCertVerifier for PinVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, Error> {
        let actual = Self::fingerprint_hex(end_entity);
        if actual.eq_ignore_ascii_case(&self.expected_sha256_hex) {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(Error::General(format!(
                "certificate pin mismatch: got {actual}"
            )))
        }
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::RSA_PKCS1_SHA256,
            SignatureScheme::RSA_PKCS1_SHA384,
            SignatureScheme::RSA_PKCS1_SHA512,
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::RSA_PSS_SHA256,
            SignatureScheme::RSA_PSS_SHA384,
            SignatureScheme::RSA_PSS_SHA512,
            SignatureScheme::ED25519,
        ]
    }
}

/// Accept any cert and return its SHA-256 fingerprint (for the probe UI).
#[derive(Debug)]
struct CaptureVerifier {
    fingerprint: std::sync::Mutex<Option<String>>,
}

impl ServerCertVerifier for CaptureVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, Error> {
        let fp = PinVerifier::fingerprint_hex(end_entity);
        *self.fingerprint.lock().unwrap() = Some(fp);
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        PinVerifier {
            expected_sha256_hex: String::new(),
        }
        .supported_verify_schemes()
    }
}

pub fn probe_fingerprint(host: &str, port: u16) -> Result<String, String> {
    let capture = Arc::new(CaptureVerifier {
        fingerprint: std::sync::Mutex::new(None),
    });
    let config = ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(capture.clone())
        .with_no_client_auth();
    let _stream = connect_tls(host, port, Arc::new(config))?;
    capture
        .fingerprint
        .lock()
        .unwrap()
        .clone()
        .ok_or_else(|| "certificate fingerprint missing".into())
}

pub type TlsStream = rustls::StreamOwned<rustls::ClientConnection, TcpStream>;

pub fn connect_pinned(host: &str, port: u16, fingerprint_sha256: &str) -> Result<TlsStream, String> {
    let verifier = Arc::new(PinVerifier {
        expected_sha256_hex: fingerprint_sha256.trim().to_lowercase(),
    });
    let config = ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_no_client_auth();
    connect_tls(host, port, Arc::new(config))
}

fn connect_tls(host: &str, port: u16, config: Arc<ClientConfig>) -> Result<TlsStream, String> {
    let addr = format!("{host}:{port}");
    let tcp = TcpStream::connect(&addr).map_err(|e| format!("TCP connect {addr}: {e}"))?;
    tcp.set_read_timeout(Some(Duration::from_secs(30)))
        .map_err(|e| e.to_string())?;
    tcp.set_write_timeout(Some(Duration::from_secs(30)))
        .map_err(|e| e.to_string())?;
    tcp.set_nodelay(true).ok();

    let server_name = ServerName::try_from(host.to_string())
        .map_err(|_| format!("invalid server name: {host}"))?;
    let conn = rustls::ClientConnection::new(config, server_name)
        .map_err(|e| format!("TLS client: {e}"))?;
    let mut tls = rustls::StreamOwned::new(conn, tcp);
    // Force handshake.
    tls.flush().map_err(|e| format!("TLS handshake: {e}"))?;
    let mut buf = [0u8; 0];
    let _ = tls.read(&mut buf);
    Ok(tls)
}

#[cfg(unix)]
pub fn tcp_raw_fd(stream: &TcpStream) -> i32 {
    use std::os::fd::AsRawFd;
    stream.as_raw_fd()
}

#[cfg(not(unix))]
pub fn tcp_raw_fd(_stream: &TcpStream) -> i32 {
    -1
}
