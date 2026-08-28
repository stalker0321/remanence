"""Protocol-v1 limits used by capsule request validation."""

from dataclasses import dataclass


@dataclass(frozen=True)
class ProtocolV1Limits:
    schema_version: int = 1
    protocol_version: int = 1
    email_max_utf8_bytes: int = 254
    password_min_code_points: int = 12
    password_max_code_points: int = 128
    handle_min_ascii_chars: int = 3
    handle_max_ascii_chars: int = 30
    note_max_utf8_bytes: int = 1000
    place_label_max_utf8_bytes: int = 120
    photo_count_min: int = 3
    photo_count_max: int = 5
    photo_ordinal_min: int = 0
    photo_ordinal_max: int = 4
    non_photo_ordinal: int = -1
    recognition_manifest_count: int = 1
    content_manifest_count: int = 1
    recipient_envelope_count: int = 1
    normalized_photo_media_type: str = "image/jpeg"
    normalized_photo_max_long_edge_px: int = 2560
    normalized_photo_max_plaintext_bytes: int = 8_388_608
    artifact_aead_overhead_bytes: int = 33
    encrypted_photo_max_ciphertext_bytes: int = 8_388_641
    recognition_manifest_max_ciphertext_bytes: int = 1_048_576
    content_manifest_max_ciphertext_bytes: int = 65_536
    recipient_envelope_max_ciphertext_bytes: int = 16_384
    total_capsule_max_ciphertext_bytes: int = 44_040_192
    draft_lifetime_seconds: int = 604_800
    incoming_page_default: int = 50
    incoming_page_max: int = 100
    mvp_track_attachment_allowed: bool = False


LIMITS_V1 = ProtocolV1Limits()

# The request envelope is bounded before JSON parsing. This is deliberately
# separate from the artifact limits in protocol/fixtures/limits-v1.json.
MAX_CREATE_DRAFT_REQUEST_BYTES = 16 * 1024
# Legal finalize JSON is dominated by unpadded base64url of a 16 KiB envelope
# plus a 4 KiB statement. 32 KiB is above that expansion and below the draft
# photo-body cap; it must not be used as the draft request limit.
MAX_FINALIZE_REQUEST_BYTES = 32 * 1024
MAX_JSON_NESTING = 8
