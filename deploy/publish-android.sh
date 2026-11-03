#!/usr/bin/env bash
# Build the Play beta AAB on the MSI workstation, sign it here with the upload
# key (the key never leaves this machine) and release it to a Play testing track.
#
#   deploy/publish-android.sh [--pending <secrets file>] [--track internal|alpha] [--notes "..."] [--draft]
#
# alpha is Play's closed testing track. Production is refused by play_publish.py.
# --pending turns first gifts on, reading the public -Premanence.pending.* lines
# that deploy/pending-secrets.py printed; without it they stay off.
# Bump remanence.androidVersionCode in android/gradle.properties first: Play
# rejects a version code it has seen before.
set -euo pipefail

repo=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
sha=$(git -C "$repo" rev-parse --short=8 HEAD)
signing=$HOME/.local/share/remanence-play-signing
python=$HOME/remanence/tools/play-venv/bin/python
out=$HOME/remanence/aab
msi=msi-workstation
qa='~/remanence-qa/claude'

pending_env=""
if [ "${1:-}" = "--pending" ]; then
  prop() { sed -n "s/^-Premanence\.pending\.$1=//p" "$2"; }
  key_id=$(prop serviceKeyId "$2"); keyset=$(prop serviceKeysetB64 "$2")
  [[ $key_id =~ ^[0-9a-f-]{36}$ && $keyset =~ ^[A-Za-z0-9+/=]+$ ]] || { echo "no pending pin in $2" >&2; exit 1; }
  pending_env="PENDING_ENABLED=true PENDING_KEY_ID=$key_id PENDING_KEYSET_B64=$keyset"
  shift 2
fi

[ -z "$(git -C "$repo" status --porcelain)" ] || { echo "working tree is dirty; commit first" >&2; exit 1; }
git -C "$repo" fetch -q origin
git -C "$repo" merge-base --is-ancestor HEAD "origin/$(git -C "$repo" rev-parse --abbrev-ref HEAD)" \
  || { echo "HEAD is not pushed" >&2; exit 1; }
code=$(sed -n 's/^remanence.androidVersionCode=//p' "$repo/android/gradle.properties")
echo "building $sha, versionCode $code, first gifts ${pending_env:+on}${pending_env:-off}"

git -C "$repo" archive --format=tar HEAD | ssh $msi "mkdir -p $qa/$sha && tar -x -C $qa/$sha"
trap 'ssh $msi "rm -rf $qa/$sha $qa/tmp/*" || true' EXIT
ssh $msi "cd $qa && $pending_env ./gradle.sh $sha :app:bundleBeta > bundle-$sha.log 2>&1" \
  || { ssh $msi "tail -30 $qa/bundle-$sha.log" >&2; exit 1; }

mkdir -p "$out"
aab="$out/Remanence-$code-$sha.aab"
scp -q "$msi:$qa/$sha/android/app/build/outputs/bundle/beta/app-beta.aab" "$aab"

jarsigner -keystore "$signing/remanence-upload.p12" -storetype PKCS12 \
  -storepass:file "$signing/upload.pass" -sigalg SHA256withRSA -digestalg SHA-256 \
  "$aab" remanence-upload >/dev/null
jarsigner -verify "$aab" >/dev/null
signer=$(keytool -printcert -jarfile "$aab" | sed -n 's/^\s*SHA256: //p' | head -1)
[ "$signer" = "82:E3:FA:77:27:BF:53:BD:E5:59:26:AF:9C:1A:07:72:3C:7D:8E:88:93:CB:1B:98:16:13:E5:FD:89:8A:5C:8F" ] \
  || { echo "unexpected signer $signer" >&2; exit 1; }
echo "signed with the upload key: $aab ($(du -h "$aab" | cut -f1))"

"$python" "$repo/deploy/play_publish.py" --upload "$aab" "$@"
