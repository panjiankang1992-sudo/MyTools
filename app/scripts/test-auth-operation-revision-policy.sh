#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MANAGER="$APP_DIR/entry/src/main/ets/features/auth/AuthSessionManager.ets"
CLIENT="$APP_DIR/entry/src/main/ets/shared/network/AuthorizedApiClient.ets"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

grep -Fq 'private authenticationRevision: number = 0;' "$MANAGER"
grep -Fq 'const revision = ++this.authenticationRevision;' "$MANAGER"
grep -Fq 'this.assertAuthenticationCurrent(revision);' "$MANAGER"
grep -Fq 'private authenticationCommitTask: Promise<void> = Promise.resolve();' "$MANAGER"
grep -Fq 'await this.commitAuthentication(revision, normalizedBaseUrl, session);' "$MANAGER"
grep -Fq 'await this.clearLocalSession(revision);' "$MANAGER"
grep -Fq 'await this.invalidateRejectedSession(revision);' "$MANAGER"
restore_block="$(sed -n '/async restore(/,/^  }/p' "$MANAGER")"
printf '%s\n' "$restore_block" | grep -Fq 'error instanceof AuthRejectedError' || {
  echo 'restore does not distinguish an explicit authentication rejection' >&2
  exit 1
}
rejected_restore_block="$(printf '%s\n' "$restore_block" | sed -n '/if (error instanceof AuthRejectedError)/,/^[[:space:]]*}/p')"
printf '%s\n' "$rejected_restore_block" | grep -Fq 'await this.invalidateRejectedSession(revision);' || {
  echo 'explicit authentication rejection must clear the stored session' >&2
  exit 1
}
printf '%s\n' "$rejected_restore_block" | grep -Fq 'throw error;' || {
  echo 'explicit authentication rejection must not revive the cleared session' >&2
  exit 1
}
printf '%s\n' "$restore_block" | grep -Fq "const error = caught instanceof Error ? caught : new Error('会话恢复失败');" || {
  echo 'restore does not normalize unknown failures for ArkTS' >&2
  exit 1
}
printf '%s\n' "$restore_block" | grep -Fq 'throw error;' || {
  echo 'restore must preserve transient failures instead of clearing credentials' >&2
  exit 1
}
grep -Fq 'const current = previous.catch(() => {}).then(async () =>' "$MANAGER"
grep -Fq 'return new AuthApi().testConnection(baseUrl);' "$MANAGER"
grep -Fq '++this.authenticationRevision;' "$MANAGER"
grep -Fq 'async forceRefresh(rejectedAccessToken?: string): Promise<string>' "$MANAGER"
grep -Fq 'this.session.accessToken !== rejectedAccessToken' "$MANAGER"
grep -Fq 'this.authManager.forceRefresh(accessToken)' "$CLIENT"
if grep -Fq 'this.authManager.forceRefresh()' "$CLIENT"; then
  echo 'authorized requests must identify the rejected token to avoid repeated refresh rotation' >&2
  exit 1
fi

for method in SubmitLogin TestConnection RestoreSession; do
  block="$(sed -n "/private async ${method}(/,/^  }/p" "$PAGE")"
  printf '%s\n' "$block" | grep -Fq 'authenticationUiRevision' || {
    echo "$method does not use the authentication UI revision" >&2
    exit 1
  }
done

login_page="$(sed -n '/private LoginPage()/,/^  }/p' "$PAGE")"
if printf '%s\n' "$login_page" | grep -Fq "this.LoginField('服务地址'"; then
  echo 'fixed MyTools service address must not be user-editable' >&2
  exit 1
fi
printf '%s\n' "$login_page" | grep -Fq "Text('MyTools 云服务')" || {
  echo 'login page must identify the fixed MyTools cloud service' >&2
  exit 1
}
printf '%s\n' "$login_page" | grep -Fq ".accessibilityDescription('重新检查固定MyTools云服务连接')" || {
  echo 'fixed service health retry must expose its effect' >&2
  exit 1
}
printf '%s\n' "$login_page" | grep -Fq '.enabled(!this.authInProgress && this.account.trim().length > 0 && this.password.length > 0)' || {
  echo 'login button must require complete credentials without blocking on an advisory health probe' >&2
  exit 1
}
if printf '%s\n' "$login_page" | grep -Fq 'this.connectionHealthy && this.account'; then
  echo 'advisory health state must not disable credential submission' >&2
  exit 1
fi

submit_block="$(sed -n '/private async SubmitLogin()/,/^  }/p' "$PAGE")"
if printf '%s\n' "$submit_block" | grep -Fq 'if (!this.connectionHealthy)'; then
  echo 'SubmitLogin must attempt the real login endpoint even when the advisory health endpoint is unavailable' >&2
  exit 1
fi

grep -Fq "private readonly serviceUrl: string = 'https://mytools.yuyutian.top';" "$PAGE" || {
  echo 'production MyTools service address must be a fixed HTTPS product constant' >&2
  exit 1
}
if grep -Fq "getSync('service_base_url'" "$PAGE" || grep -Fq "putSync('service_base_url'" "$PAGE"; then
  echo 'fixed MyTools service address must not be loaded from or saved to Preferences' >&2
  exit 1
fi
open_login="$(sed -n '/private OpenLogin()/,/^  }/p' "$PAGE")"
printf '%s\n' "$open_login" | grep -Fq 'if (!this.connectionHealthy && !this.authInProgress) this.TestConnection();' || {
  echo 'opening login must automatically test the fixed MyTools service' >&2
  exit 1
}

grep -Fq 'this.authenticationUiRevision++;' "$PAGE"
echo 'Authentication operation revision policy tests passed'
