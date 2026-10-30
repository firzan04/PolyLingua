#!/usr/bin/env python3
"""Deterministically derives the PolyLingua Edge APK signing keystore from KEYSTORE_PASSWORD.

Why: Android only installs an update over an existing app when both APKs carry the *same*
signing certificate, but committing a private key to a public repository leaks it. Instead,
the RSA-3072 key pair and its self-signed certificate are derived from the secret password
(HMAC-SHA256 DRBG -> Miller-Rabin primes -> PKCS#1 v1.5 self-signature, all deterministic).
CI (GitHub secret KEYSTORE_PASSWORD) and local builds (build_apk.sh / build_apk.bat, password
typed at the prompt) therefore produce byte-identical signing certificates while no key
material is ever stored in git. The derived key is exactly as secret as the password.

Usage:  KEYSTORE_PASSWORD=... python3 scripts/derive_release_keystore.py keystore/polylingua-release.p12
Requires: pip install cryptography (>= 38)
"""
import datetime
import hashlib
import hmac
import math
import os
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

DERIVATION_LABEL = b"polylingua-edge/apk-signing/rsa3072/v1"
KEY_ALIAS = b"polylingua"
PUBLIC_EXPONENT = 65537
PRIME_BITS = 1536


class HmacDrbg:
    """Counter-mode HMAC-SHA256 byte stream (deterministic for a given seed)."""

    def __init__(self, seed: bytes):
        self._key = seed
        self._counter = 0

    def read(self, n: int) -> bytes:
        out = bytearray()
        while len(out) < n:
            block = hmac.new(self._key, b"block" + self._counter.to_bytes(8, "big"), hashlib.sha256).digest()
            out.extend(block)
            self._counter += 1
        return bytes(out[:n])

    def randbelow(self, upper: int) -> int:
        nbytes = (upper.bit_length() + 7) // 8 + 8
        return int.from_bytes(self.read(nbytes), "big") % upper


def _small_primes(limit: int):
    sieve = bytearray([1]) * (limit + 1)
    sieve[0:2] = b"\x00\x00"
    for i in range(2, int(limit ** 0.5) + 1):
        if sieve[i]:
            sieve[i * i:: i] = bytearray(len(sieve[i * i:: i]))
    return [i for i in range(limit + 1) if sieve[i]]


SMALL_PRIMES = _small_primes(3000)


def is_probable_prime(n: int, drbg: HmacDrbg, rounds: int = 48) -> bool:
    if n < 2:
        return False
    for p in SMALL_PRIMES:
        if n == p:
            return True
        if n % p == 0:
            return False
    d, s = n - 1, 0
    while d % 2 == 0:
        d //= 2
        s += 1
    for _ in range(rounds):
        a = 2 + drbg.randbelow(n - 3)
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(s - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def generate_prime(drbg: HmacDrbg) -> int:
    while True:
        candidate = int.from_bytes(drbg.read(PRIME_BITS // 8), "big")
        candidate |= (3 << (PRIME_BITS - 2)) | 1  # exact bit length, odd
        if math.gcd(PUBLIC_EXPONENT, candidate - 1) != 1:
            continue
        if is_probable_prime(candidate, drbg):
            return candidate


def derive(password: str):
    seed = hmac.new(password.encode("utf-8"), DERIVATION_LABEL, hashlib.sha256).digest()
    drbg = HmacDrbg(seed)
    p = generate_prime(drbg)
    q = generate_prime(drbg)
    while q == p or abs(p - q).bit_length() < PRIME_BITS - 100:
        q = generate_prime(drbg)
    if p < q:
        p, q = q, p
    n = p * q
    phi = (p - 1) * (q - 1)
    d = pow(PUBLIC_EXPONENT, -1, phi)
    numbers = rsa.RSAPrivateNumbers(
        p=p, q=q, d=d, dmp1=d % (p - 1), dmq1=d % (q - 1), iqmp=pow(q, -1, p),
        public_numbers=rsa.RSAPublicNumbers(PUBLIC_EXPONENT, n),
    )
    key = numbers.private_key()
    name = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, "PolyLingua Edge"),
        x509.NameAttribute(NameOID.ORGANIZATIONAL_UNIT_NAME, "Mobile"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "PolyLingua"),
        x509.NameAttribute(NameOID.COUNTRY_NAME, "ID"),
    ])
    serial = int.from_bytes(hashlib.sha256(seed + b"serial").digest()[:16], "big") >> 1
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(serial)
        .not_valid_before(datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc))
        .not_valid_after(datetime.datetime(2056, 1, 1, tzinfo=datetime.timezone.utc))
        .sign(key, hashes.SHA256(), rsa_padding=padding.PKCS1v15())
    )
    return key, cert


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    password = os.environ.get("KEYSTORE_PASSWORD", "")
    if len(password) < 12:
        print("KEYSTORE_PASSWORD is missing or shorter than 12 characters", file=sys.stderr)
        return 1
    key, cert = derive(password)
    encryption = (
        serialization.PrivateFormat.PKCS12.encryption_builder()
        .kdf_rounds(50000)
        .key_cert_algorithm(pkcs12.PBES.PBESv2SHA256AndAES256CBC)
        .hmac_hash(hashes.SHA256())
        .build(password.encode("utf-8"))
    )
    blob = pkcs12.serialize_key_and_certificates(KEY_ALIAS, key, cert, None, encryption)
    out = sys.argv[1]
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "wb") as f:
        f.write(blob)
    fp = cert.fingerprint(hashes.SHA256()).hex(":").upper()
    print(f"Derived release keystore {out} (alias polylingua)")
    print(f"Signing certificate SHA-256: {fp}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
