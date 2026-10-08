import base64
import json

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


def generate_key_pair():
    private_key = X25519PrivateKey.generate()
    public_key = private_key.public_key()

    return private_key, public_key


def derive_session_key(private_key, peer_public_key):
    shared_secret = private_key.exchange(peer_public_key)

    return HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=None,
        info=b"teton-provisioning-v1",
    ).derive(shared_secret)

def derive_session_key_from_peer(
    private_key,
    peer_public_key_bytes,
):
    peer_public_key = X25519PublicKey.from_public_bytes(
        peer_public_key_bytes
    )

    return derive_session_key(
        private_key,
        peer_public_key,
    )


def decrypt_credentials(session_key, nonce, ciphertext):
    aesgcm = AESGCM(session_key)

    plaintext = aesgcm.decrypt(
        nonce,
        ciphertext,
        None,
    )

    return json.loads(plaintext.decode("utf-8"))


def decode_base64(value):
    return base64.b64decode(value)