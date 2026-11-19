mod engine;
mod tls_pin;

use engine::{ConnectParams, Platform};
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jstring, JNI_TRUE};
use jni::JNIEnv;
use std::net::Ipv4Addr;
use std::sync::Mutex;

struct AndroidPlatform {
    jvm: jni::JavaVM,
    callbacks: GlobalRef,
}

impl AndroidPlatform {
    fn with_env<F, R>(&self, f: F) -> Result<R, String>
    where
        F: FnOnce(&mut JNIEnv) -> Result<R, String>,
    {
        let mut env = self
            .jvm
            .attach_current_thread()
            .map_err(|e| format!("attach: {e}"))?;
        f(&mut env)
    }
}

impl Platform for AndroidPlatform {
    fn configure_tun(
        &mut self,
        address: Ipv4Addr,
        prefix: u8,
        dns: &[Ipv4Addr],
        routes: &[String],
        mtu: i32,
    ) -> Result<i32, String> {
        self.with_env(|env| {
            let address = env
                .new_string(address.to_string())
                .map_err(|e| e.to_string())?;
            let dns_arr = env
                .new_object_array(dns.len() as i32, "java/lang/String", JObject::null())
                .map_err(|e| e.to_string())?;
            for (i, d) in dns.iter().enumerate() {
                let s = env.new_string(d.to_string()).map_err(|e| e.to_string())?;
                env.set_object_array_element(&dns_arr, i as i32, s)
                    .map_err(|e| e.to_string())?;
            }
            let routes_arr = env
                .new_object_array(routes.len() as i32, "java/lang/String", JObject::null())
                .map_err(|e| e.to_string())?;
            for (i, r) in routes.iter().enumerate() {
                let s = env.new_string(r).map_err(|e| e.to_string())?;
                env.set_object_array_element(&routes_arr, i as i32, s)
                    .map_err(|e| e.to_string())?;
            }

            let result = env
                .call_method(
                    self.callbacks.as_obj(),
                    "configureTun",
                    "(Ljava/lang/String;I[Ljava/lang/String;[Ljava/lang/String;I)I",
                    &[
                        JValue::Object(&address),
                        JValue::Int(prefix as i32),
                        JValue::Object(&dns_arr),
                        JValue::Object(&routes_arr),
                        JValue::Int(mtu),
                    ],
                )
                .map_err(|e| format!("configureTun: {e}"))?;
            result.i().map_err(|e| e.to_string())
        })
    }

    fn on_status(&mut self, status: &str, detail: &str) {
        let _ = self.with_env(|env| {
            let status = env.new_string(status).map_err(|e| e.to_string())?;
            let detail = env.new_string(detail).map_err(|e| e.to_string())?;
            env.call_method(
                self.callbacks.as_obj(),
                "onStatus",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                &[JValue::Object(&status), JValue::Object(&detail)],
            )
            .map_err(|e| e.to_string())?;
            Ok(())
        });
    }

    fn on_routes(&mut self, routes: &[String]) {
        let _ = self.with_env(|env| {
            let routes_arr = env
                .new_object_array(routes.len() as i32, "java/lang/String", JObject::null())
                .map_err(|e| e.to_string())?;
            for (i, r) in routes.iter().enumerate() {
                let s = env.new_string(r).map_err(|e| e.to_string())?;
                env.set_object_array_element(&routes_arr, i as i32, s)
                    .map_err(|e| e.to_string())?;
            }
            env.call_method(
                self.callbacks.as_obj(),
                "onRoutesUpdated",
                "([Ljava/lang/String;)V",
                &[JValue::Object(&routes_arr)],
            )
            .map_err(|e| e.to_string())?;
            Ok(())
        });
    }

    fn protect_socket(&mut self, fd: i32) -> bool {
        self.with_env(|env| {
            let result = env
                .call_method(
                    self.callbacks.as_obj(),
                    "protectSocket",
                    "(I)Z",
                    &[JValue::Int(fd)],
                )
                .map_err(|e| e.to_string())?;
            Ok(result.z().unwrap_or(false))
        })
        .unwrap_or(false)
    }
}

static INIT_LOG: Mutex<bool> = Mutex::new(false);

fn ensure_log() {
    let mut g = INIT_LOG.lock().unwrap();
    if !*g {
        #[cfg(target_os = "android")]
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("OpenKerio"),
        );
        *g = true;
    }
}

fn jstring_to_string(env: &mut JNIEnv, value: JString) -> Result<String, String> {
    env.get_string(&value)
        .map(|s| s.into())
        .map_err(|e| e.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_openkerio_vpn_vpn_NativeEngine_nativeProbeCertificate(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    port: jint,
) -> jstring {
    ensure_log();
    let result = (|| {
        let host = jstring_to_string(&mut env, host)?;
        engine::probe(&host, port as u16)
    })();

    match result {
        Ok(fp) => env
            .new_string(fp)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(err) => {
            let _ = env.throw_new("java/lang/IllegalStateException", err);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_openkerio_vpn_vpn_NativeEngine_nativeStart<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    port: jint,
    username: JString<'local>,
    password: JString<'local>,
    fingerprint: JString<'local>,
    callbacks: JObject<'local>,
) {
    ensure_log();
    let result = (|| {
        let host = jstring_to_string(&mut env, host)?;
        let username = jstring_to_string(&mut env, username)?;
        let password = jstring_to_string(&mut env, password)?;
        let fingerprint = jstring_to_string(&mut env, fingerprint)?;
        let jvm = env.get_java_vm().map_err(|e| e.to_string())?;
        let callbacks = env
            .new_global_ref(callbacks)
            .map_err(|e| e.to_string())?;
        let platform = Box::new(AndroidPlatform { jvm, callbacks });
        let params = ConnectParams {
            host,
            port: port as u16,
            username,
            password,
            fingerprint_sha256: fingerprint,
        };
        engine::run(params, platform)
    })();

    if let Err(err) = result {
        let _ = env.throw_new("java/lang/IllegalStateException", err);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_openkerio_vpn_vpn_NativeEngine_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) {
    engine::request_stop();
}

#[no_mangle]
pub extern "system" fn Java_com_openkerio_vpn_vpn_NativeEngine_nativeIsRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if engine::is_running() {
        JNI_TRUE
    } else {
        0
    }
}
