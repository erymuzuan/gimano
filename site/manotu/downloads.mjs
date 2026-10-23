// Read-only consumer of the Android team's release manifest. Never infer a release from a build number.
export function androidRelease(value) {
  if (!value || !Number.isSafeInteger(value.versionCode) || value.versionCode < 2 ||
      !/^\d+\.\d+\.\d+$/.test(value.versionName) || !/^[a-f0-9]{64}$/i.test(value.sha256)) return null;
  const expected = `https://www.bukitbunga.com/gimano/download/gimano-${value.versionName}.apk`;
  if (value.apkUrl !== expected) return null;
  if (value.sizeBytes !== undefined && (!Number.isSafeInteger(value.sizeBytes) || value.sizeBytes <= 0)) return null;
  return value;
}

export function renderAndroid(release, document) {
  for (const id of ['android-download', 'apk-link', 'apk-mirror']) {
    const link = document.getElementById(id);
    if (link) link.href = release.apkUrl;
  }
  const texts = {
    'android-release-label': `APK · ${release.versionName} · Android 8+`,
    'apk-version': release.versionName,
    'apk-file': `gimano-${release.versionName}.apk`,
    'apk-hash-file': `gimano-${release.versionName}.apk`,
    'apk-sha256': release.sha256.toLowerCase(),
    'apk-mirror': `gimano-${release.versionName}.apk`,
  };
  for (const [id, text] of Object.entries(texts)) {
    const node = document.getElementById(id);
    if (node) node.textContent = text;
  }
  const size = document.getElementById('apk-size');
  const bytes = document.getElementById('apk-bytes');
  if (size) size.textContent = release.sizeBytes ? `${(release.sizeBytes / 1000000).toFixed(1)} MB` : 'APK';
  if (bytes) bytes.textContent = release.sizeBytes ? release.sizeBytes.toLocaleString('en-US') : '—';
}

if (typeof document !== 'undefined') {
  fetch('/gimano/download/version.json', {cache: 'no-store', credentials: 'omit'})
    .then(response => response.ok ? response.json() : null)
    .then(value => { const release = androidRelease(value); if (release) renderAndroid(release, document); })
    .catch(() => {}); // Offline, unavailable or invalid manifest: retain verified static preview links.
}
