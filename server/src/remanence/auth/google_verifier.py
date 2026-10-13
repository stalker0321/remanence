"""Google's signature/cert/issuer/expiry verifier plus our audience/nonce policy."""
import hmac
from google.auth.transport.requests import Request
from google.oauth2 import id_token


class BoundedRequest(Request):
    def __call__(self, url, method="GET", body=None, headers=None, timeout=10, **kwargs):
        return super().__call__(url, method, body, headers, timeout=min(timeout, 10), **kwargs)


def verify_google_token(token: str, nonce: str, settings) -> dict:
    claims = id_token.verify_oauth2_token(token, BoundedRequest(), audience=settings.google_client_ids)
    return validate_google_claims(claims, nonce, settings)


def validate_google_claims(claims: dict, nonce: str, settings) -> dict:
    # Never use email as the provider identifier or auto-link matching emails.
    if claims.get("iss") not in {"accounts.google.com", "https://accounts.google.com"}:
        raise ValueError("invalid Google issuer")
    if claims.get("aud") not in settings.google_client_ids:
        raise ValueError("invalid Google audience")
    presenter = claims.get("azp")
    if presenter is not None and presenter not in settings.google_client_ids + settings.google_presenter_ids:
        raise ValueError("invalid Google presenter")
    received = claims.get("nonce")
    if not isinstance(received, str) or not hmac.compare_digest(received, nonce):
        raise ValueError("invalid Google nonce")
    if claims.get("email_verified") is not True:
        raise ValueError("verified email required")
    subject = claims.get("sub")
    if not isinstance(subject, str) or not subject or len(subject) > 255:
        raise ValueError("invalid Google subject")
    from remanence.api.auth_schemas import normalize_email
    return {
        "subject": subject,
        "email": normalize_email(claims["email"]),
        # Authority evidence preserved for delivery verification (T185):
        # trusted Gmail/Workspace-hd distinction and token age must be
        # derivable from validated originals, never re-fetched ad hoc.
        "email_verified": True,
        "hd": claims.get("hd"),
        "iat": claims.get("iat"),
    }
