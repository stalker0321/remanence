"""Reserved owner → one permanent transaction. No SEALED/private payload stage."""
import hashlib
import hmac
import json
import secrets
import uuid
from datetime import timedelta

import tink
from tink import signature, tink_config
from sqlalchemy import delete, func, or_, select, text
from sqlalchemy.exc import IntegrityError
from webauthn import generate_registration_options, generate_authentication_options, options_to_json, verify_registration_response, verify_authentication_response
from webauthn.helpers.structs import AuthenticatorSelectionCriteria, ResidentKeyRequirement, UserVerificationRequirement, PublicKeyCredentialDescriptor

from remanence.api.recovery import b64, unb64, manifest, PRF_INPUT
from remanence.auth.google_models import GoogleIdentity
from remanence.auth.passwords import PasswordService
from remanence.auth.registration import RegistrationService
from remanence.recovery.models import RecoveryCredential, RecoveryRecord
from remanence.registration_setup.models import RegistrationPolicy, RegistrationReservation as Reservation, RegistrationCredential as Credential, RegistrationChallenge as Challenge
from remanence.users.key_models import UserKeyBundle
from remanence.users.models import User


class SetupFailure(Exception):
    def __init__(self, reason, status=400, *, persist=False):
        self.reason, self.status, self.persist = reason, status, persist
        super().__init__(reason)


def token_hash(value):
    return hashlib.sha256(value.encode("ascii")).digest()


def lock_subject(db, subject):
    db.execute(text("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))"),
               {"key": "atomic-registration-subject:" + subject})


def required_policy(db, settings):
    # The one-row latch is never cleared by process config/admission rollback.
    row = db.scalar(select(RegistrationPolicy).where(RegistrationPolicy.id == 1).with_for_update())
    if row is None:
        raise RuntimeError("atomic registration migration required")
    if settings.registration_atomic_required:
        row.required = True
        db.flush()
    return row.required


class AtomicRegistration:
    def __init__(self, db, settings, clock):
        self.db, self.settings, self.clock = db, settings, clock

    def _budget_lock(self):
        self.db.execute(text("SELECT pg_advisory_xact_lock(162403)"))

    def _credential_ids(self, row):
        if row.state == "ACTIVE":
            record = self.db.get(RecoveryRecord, row.account_id)
            return [record.credential_id] if record else []
        return list(self.db.scalars(select(Credential.id).where(Credential.reservation_id == row.id).order_by(Credential.id)))

    def outcome(self, row):
        if row.accepted_operation_id is None:
            return None
        return dict(operation_id=str(row.accepted_operation_id), account_id=str(row.account_id),
                    bundle_id=str(row.accepted_bundle_id), credential_id=row.accepted_credential_id,
                    generation=1, handle=row.accepted_handle)

    def view(self, row):
        return dict(id=str(row.id), account_id=str(row.account_id), state=row.state, epoch=row.epoch,
                    expires_at=row.expires_at.isoformat(), credential_ids=self._credential_ids(row),
                    outcome=self.outcome(row), restarted_into=str(row.restarted_into) if row.restarted_into else None)

    def _forget_setup(self, row):
        self.db.execute(delete(Challenge).where(Challenge.reservation_id == row.id))
        self.db.execute(delete(Credential).where(Credential.reservation_id == row.id))

    def _expire(self, row):
        now = self.clock()
        if row.state == "RESERVED" and row.expires_at <= now:
            row.state, row.terminal_at, row.restart_authorized = "EXPIRED", now, False
            self._forget_setup(row)
        if row.state == "ACTIVE" and row.receipt_expires_at and row.receipt_expires_at <= now:
            row.accepted_operation_id = row.accepted_bundle_id = row.accepted_credential_id = None
            row.accepted_handle = row.accepted_grant_hash = row.receipt_expires_at = None
        self.db.flush()

    def _capability(self, row):
        secret = "rgs_" + b64(secrets.token_bytes(32))
        row.setup_session_id = uuid.uuid4()
        row.setup_token_hash = token_hash(secret)
        row.setup_expires_at = self.clock() + timedelta(seconds=self.settings.registration_setup_ttl_seconds)
        self.db.flush()
        return dict(status="SETUP_REQUIRED", reservation=self.view(row), setup_token=secret,
                    setup_session_id=str(row.setup_session_id), setup_expires_at=row.setup_expires_at.isoformat())

    def _new(self, subject, email):
        if not self.settings.registration_atomic_admission_enabled:
            raise SetupFailure("REGISTRATION_ADMISSION_CLOSED", 403)
        self._budget_lock()
        count = self.db.scalar(select(func.count()).select_from(Reservation).where(
            Reservation.state == "RESERVED", Reservation.expires_at > self.clock()))
        if count >= 512:
            raise SetupFailure("SETUP_LIMIT_REACHED", 429)
        now = self.clock()
        row = Reservation(id=uuid.uuid4(), account_id=uuid.uuid4(), subject=subject, email=email,
                          state="RESERVED", epoch=1, is_current=True, restart_authorized=False,
                          setup_session_id=uuid.uuid4(), setup_expires_at=now, created_at=now,
                          expires_at=now + timedelta(seconds=self.settings.registration_reservation_ttl_seconds))
        self.db.add(row)
        self.db.flush()
        return row

    def reserve_verified(self, subject, email):
        # Google handler holds this same lock before its existing-account lookup.
        lock_subject(self.db, subject)
        if self.db.get(GoogleIdentity, subject):
            raise SetupFailure("ALREADY_ACTIVE", 409)
        row = self.db.scalar(select(Reservation).where(Reservation.subject == subject, Reservation.is_current).with_for_update())
        if row is None:
            row = self._new(subject, email)
        else:
            self._expire(row)
            if row.state == "ACTIVE":
                raise SetupFailure("ALREADY_ACTIVE", 409)
            row.epoch += 1
            row.email = email
            self.db.execute(delete(Challenge).where(Challenge.reservation_id == row.id))
        row.restart_authorized = row.state in ("CANCELLED", "EXPIRED")
        return self._capability(row)

    def authorized(self, reservation_id, secret, epoch=None):
        try:
            if not isinstance(secret, str) or not secret.startswith("rgs_"):
                raise ValueError()
            unb64(secret[4:], 32, 32)
        except (ValueError, TypeError):
            raise SetupFailure("SETUP_AUTH_INVALID", 401) from None
        hashed = token_hash(secret)
        preliminary = self.db.scalar(select(Reservation).where(Reservation.setup_token_hash == hashed))
        if preliminary is None:
            raise SetupFailure("SETUP_AUTH_INVALID", 401)
        if preliminary.id != reservation_id:
            raise SetupFailure("RESERVATION_NOT_FOUND", 404)
        lock_subject(self.db, preliminary.subject)
        row = self.db.scalar(select(Reservation).where(Reservation.id == reservation_id)
                             .execution_options(populate_existing=True).with_for_update())
        if row is None or row.setup_token_hash is None or not hmac.compare_digest(row.setup_token_hash, hashed) or row.setup_expires_at <= self.clock():
            raise SetupFailure("SETUP_AUTH_INVALID", 401)
        if epoch is not None and epoch != row.epoch:
            raise SetupFailure("STALE_SETUP", 409)
        self._expire(row)
        return row

    def ordinary_status(self, reservation_id, user_id):
        row = self.db.get(Reservation, reservation_id)
        if row is None or row.state != "ACTIVE" or row.account_id != user_id:
            raise SetupFailure("RESERVATION_NOT_FOUND", 404)
        lock_subject(self.db, row.subject)
        row = self.db.scalar(select(Reservation).where(Reservation.id == reservation_id)
                             .execution_options(populate_existing=True).with_for_update())
        self._expire(row)
        return dict(reservation=self.view(row))

    def _reserved(self, row):
        if row.state == "ACTIVE":
            raise SetupFailure("ALREADY_ACTIVE", 409, persist=True)
        if row.state != "RESERVED":
            raise SetupFailure("RESERVATION_TERMINAL", 410, persist=True)

    def _issue(self, row, purpose, credential_id=None, challenge=None, secret=None):
        self._budget_lock()
        now = self.clock()
        # This reservation has at most eight live ceremonies; expired scoped
        # rows can be removed without touching other owners or permanent data.
        self.db.execute(delete(Challenge).where(Challenge.reservation_id == row.id,
            or_(Challenge.expires_at <= now, Challenge.consumed_at.is_not(None))))
        active = select(func.count()).select_from(Challenge).where(Challenge.expires_at > now, Challenge.consumed_at.is_(None))
        if self.db.scalar(active) >= 512 or self.db.scalar(active.where(Challenge.reservation_id == row.id)) >= 8:
            raise SetupFailure("SETUP_LIMIT_REACHED", 429, persist=True)
        item = Challenge(id=uuid.uuid4(), reservation_id=row.id, epoch=row.epoch,
                         setup_session_id=row.setup_session_id, purpose=purpose, credential_id=credential_id,
                         challenge=challenge or secrets.token_bytes(32), secret_hash=token_hash(secret) if secret else None,
                         expires_at=now + timedelta(seconds=self.settings.registration_ceremony_ttl_seconds))
        self.db.add(item)
        self.db.flush()
        return item

    def credential_options(self, row):
        self._reserved(row)
        pending = self._credential_ids(row)
        if len(pending) >= 4:
            raise SetupFailure("SETUP_LIMIT_REACHED", 429)
        options = generate_registration_options(
            rp_id=self.settings.recovery_rp_id, rp_name="Remanence", user_id=row.account_id.bytes,
            user_name=f"remanence-{row.account_id}",
            authenticator_selection=AuthenticatorSelectionCriteria(resident_key=ResidentKeyRequirement.REQUIRED,
                                                                   user_verification=UserVerificationRequirement.REQUIRED),
            exclude_credentials=[PublicKeyCredentialDescriptor(id=unb64(value)) for value in pending])
        flow = self._issue(row, "REGISTER", challenge=options.challenge)
        out = json.loads(options_to_json(options)); out["extensions"] = {"prf": {}}
        return dict(flow=str(flow.id), options=out, expires_at=flow.expires_at.isoformat())

    def _consume(self, row, flow_id, purpose):
        flow = self.db.scalar(select(Challenge).where(Challenge.id == uuid.UUID(flow_id)).with_for_update())
        if not flow or (flow.reservation_id, flow.epoch, flow.setup_session_id, flow.purpose) != (row.id, row.epoch, row.setup_session_id, purpose) or flow.expires_at <= self.clock() or flow.consumed_at is not None:
            raise SetupFailure("SETUP_REJECTED")
        flow.consumed_at = self.clock()
        self.db.flush()
        return flow

    def credential_verify(self, row, payload):
        self._reserved(row)
        flow = self._consume(row, payload.flow, "REGISTER")
        try:
            with self.db.begin_nested():
                try:
                    verified = verify_registration_response(credential=payload.response, expected_challenge=flow.challenge,
                        expected_rp_id=self.settings.recovery_rp_id, expected_origin=self.settings.recovery_origins,
                        require_user_verification=True, require_user_presence=True)
                except Exception:
                    raise ValueError("invalid public proof") from None
                credential_id = b64(verified.credential_id)
                if len(self._credential_ids(row)) >= 4:
                    raise SetupFailure("SETUP_LIMIT_REACHED", 429, persist=True)
                if self.db.get(RecoveryCredential, credential_id) or self.db.get(Credential, credential_id):
                    raise SetupFailure("CREDENTIAL_CONFLICT", 409, persist=True)
                self.db.add(Credential(id=credential_id, reservation_id=row.id, public_key=verified.credential_public_key,
                                       counter=verified.sign_count, created_at=self.clock()))
                self.db.flush()
        except IntegrityError:
            raise SetupFailure("CREDENTIAL_CONFLICT", 409, persist=True) from None
        except ValueError:
            raise SetupFailure("SETUP_REJECTED", persist=True) from None
        return dict(credential_id=credential_id, registered=True, recoverable=False)

    def setup_options(self, row, credential_id):
        self._reserved(row)
        credential = self.db.get(Credential, credential_id)
        if not credential or credential.reservation_id != row.id:
            raise SetupFailure("SETUP_REJECTED")
        options = generate_authentication_options(rp_id=self.settings.recovery_rp_id,
            allow_credentials=[PublicKeyCredentialDescriptor(id=unb64(credential_id))],
            user_verification=UserVerificationRequirement.REQUIRED)
        flow = self._issue(row, "SETUP", credential_id, options.challenge)
        out = json.loads(options_to_json(options)); out["extensions"] = {"prf": {"eval": {"first": b64(PRF_INPUT)}}}
        return dict(flow=str(flow.id), options=out, expires_at=flow.expires_at.isoformat())

    def setup_verify(self, row, payload):
        self._reserved(row)
        flow = self._consume(row, payload.flow, "SETUP")
        try:
            with self.db.begin_nested():
                credential = self.db.scalar(select(Credential).where(Credential.id == payload.response["id"]).with_for_update())
                if not credential or credential.reservation_id != row.id or flow.credential_id != credential.id:
                    raise ValueError()
                handle = payload.response["response"].get("userHandle")
                if handle and unb64(handle) != row.account_id.bytes:
                    raise ValueError()
                try:
                    verified = verify_authentication_response(credential=payload.response, expected_challenge=flow.challenge,
                        expected_rp_id=self.settings.recovery_rp_id, expected_origin=self.settings.recovery_origins,
                        credential_public_key=credential.public_key, credential_current_sign_count=credential.counter,
                        require_user_verification=True)
                except Exception:
                    raise ValueError("invalid public proof") from None
                credential.counter = verified.new_sign_count
                secret = "rgf_" + b64(secrets.token_bytes(32))
                grant = self._issue(row, "FINAL", credential.id, secret=secret)
        except ValueError:
            raise SetupFailure("SETUP_REJECTED", persist=True) from None
        return dict(credential_id=credential.id, authentication_verified=True,
                    final_authorization=secret, expires_at=grant.expires_at.isoformat())

    def finalize(self, row, payload):
        secret_hash = token_hash(payload.final_authorization.get_secret_value())
        bundle, record = payload.key_bundle, payload.record
        if row.state == "ACTIVE":
            if row.accepted_operation_id != uuid.UUID(payload.operation_id):
                raise SetupFailure("ALREADY_ACTIVE", 409, persist=True)
            stored = self.db.get(RecoveryRecord, row.account_id)
            public = self.db.get(UserKeyBundle, row.accepted_bundle_id)
            if not stored or not public or row.accepted_grant_hash != secret_hash or row.accepted_handle != payload.handle or (
                public.id, public.suite, public.protocol_version, public.encryption_public_keyset, public.signing_public_keyset
            ) != (bundle.key_bundle_id, bundle.suite, bundle.protocol_version, bundle.encryption_public_keyset, bundle.signing_public_keyset) or stored.payload != record.model_dump():
                raise SetupFailure("INTENT_CONFLICT", 409, persist=True)
            return 200, dict(status="ACTIVE", outcome=self.outcome(row), reauthenticate=True)
        self._reserved(row)
        grant = self.db.scalar(select(Challenge).where(Challenge.secret_hash == secret_hash).with_for_update())
        if not grant or (grant.reservation_id, grant.epoch, grant.setup_session_id, grant.purpose) != (row.id, row.epoch, row.setup_session_id, "FINAL") or grant.expires_at <= self.clock() or grant.consumed_at is not None:
            raise SetupFailure("SETUP_REJECTED")
        grant.consumed_at = self.clock()
        self.db.flush()
        try:
            with self.db.begin_nested():
                credential = self.db.scalar(select(Credential).where(Credential.id == grant.credential_id).with_for_update())
                if not credential or credential.reservation_id != row.id or (record.account_id, record.bundle_id, record.credential_id) != (str(row.account_id), str(bundle.key_bundle_id), credential.id):
                    raise ValueError()
                if uuid.UUID(bytes=hashlib.md5(bundle.encryption_public_keyset).digest(), version=3) != bundle.key_bundle_id:
                    raise ValueError()
                tink_config.register()
                key = tink.KeysetHandle.read_no_secret(tink.BinaryKeysetReader(bundle.signing_public_keyset))
                key.primitive(signature.PublicKeyVerify).verify(unb64(record.signature), manifest(record))
                if self.db.get(GoogleIdentity, row.subject):
                    raise SetupFailure("ALREADY_ACTIVE", 409, persist=True)
                result = RegistrationService(self.db, PasswordService()).register(
                    email_normalized=row.email, password=None, handle_normalized=payload.handle,
                    key_bundle_id=bundle.key_bundle_id, suite=bundle.suite, protocol_version=bundle.protocol_version,
                    encryption_public_keyset=bundle.encryption_public_keyset, signing_public_keyset=bundle.signing_public_keyset,
                    now=self.clock(), reserved_user_id=row.account_id)
                self.db.add(GoogleIdentity(subject=row.subject, user_id=row.account_id))
                self.db.add(RecoveryCredential(id=credential.id, user_id=row.account_id, public_key=credential.public_key,
                                              counter=credential.counter, created_at=credential.created_at))
                self.db.flush()
                self.db.add(RecoveryRecord(user_id=row.account_id, credential_id=credential.id,
                                          bundle_id=bundle.key_bundle_id, generation=1, payload=record.model_dump()))
                row.state, row.activated_at = "ACTIVE", self.clock()
                row.accepted_operation_id = uuid.UUID(payload.operation_id)
                row.accepted_bundle_id, row.accepted_credential_id = bundle.key_bundle_id, credential.id
                row.accepted_handle, row.accepted_grant_hash = payload.handle, secret_hash
                row.receipt_expires_at = self.clock() + timedelta(seconds=self.settings.registration_receipt_ttl_seconds)
                self.db.flush()
                login = self._login(result, bundle)
                self._forget_setup(row)
                self.db.flush()
        except IntegrityError as error:
            constraint = getattr(getattr(error.orig, "diag", None), "constraint_name", "")
            if constraint == "uq_users_handle_normalized":
                grant.consumed_at = None
                self.db.flush()
                raise SetupFailure("HANDLE_TAKEN", 409, persist=True) from None
            raise SetupFailure("REGISTRATION_CONFLICT", 409, persist=True) from None
        except (ValueError, tink.TinkError):
            raise SetupFailure("SETUP_REJECTED", persist=True) from None
        return 201, dict(status="ACTIVE", outcome=self.outcome(row), login=login)

    def _login(self, result, bundle):
        from remanence.api.auth_schemas import LoginResponse, RegistrationUserResponse, ActiveKeyBundleResponse
        user = self.db.get(User, result.user_id)
        return LoginResponse(user=RegistrationUserResponse(user_id=user.id, email=user.email_normalized,
            handle=user.handle_normalized, created_at=user.created_at),
            active_key_bundle=ActiveKeyBundleResponse(key_bundle_id=bundle.key_bundle_id, suite=bundle.suite,
                protocol_version=bundle.protocol_version, status="ACTIVE"), session_id=result.session_id,
            access_token=result.access_token, refresh_token=result.refresh_token,
            access_expires_at=result.access_expires_at, refresh_expires_at=result.refresh_expires_at).model_dump(mode="json")

    def leave(self, row):
        row.setup_token_hash = None
        self.db.execute(delete(Challenge).where(Challenge.reservation_id == row.id))
        return dict(left=True)

    def cancel(self, row):
        if row.state == "ACTIVE":
            raise SetupFailure("ALREADY_ACTIVE", 409, persist=True)
        if row.state == "RESERVED":
            row.state, row.terminal_at, row.restart_authorized = "CANCELLED", self.clock(), False
            self._forget_setup(row)
        return dict(reservation=self.view(row))

    def restart(self, row):
        if row.state == "ACTIVE":
            raise SetupFailure("ALREADY_ACTIVE", 409, persist=True)
        if row.restarted_into:
            successor = self.db.get(Reservation, row.restarted_into)
            if successor is None:
                raise SetupFailure("RESERVATION_NOT_FOUND", 404)
            self._expire(successor)
            return dict(status="RESTARTED", reservation=self.view(successor), reauthenticate=True)
        if row.state not in ("EXPIRED", "CANCELLED"):
            raise SetupFailure("RESTART_NOT_ALLOWED", 409)
        if not row.restart_authorized:
            raise SetupFailure("SETUP_REAUTH_REQUIRED", 401, persist=True)
        if self.db.get(GoogleIdentity, row.subject):
            raise SetupFailure("ALREADY_ACTIVE", 409)
        row.is_current = False
        self.db.flush()
        successor = self._new(row.subject, row.email)
        row.restarted_into = successor.id
        return self._capability(successor)
