// Server-side Vite configuration only; never expose credentials to the browser.
export function localApiOrigin(value = 'http://127.0.0.1:10010') {
  if (!/^http:\/\/127\.0\.0\.1:([1-9][0-9]{0,4})$/.test(value)) {
    throw new Error('XUCAI_API_ORIGIN must be an explicit local HTTP origin');
  }
  const port = Number(new URL(value).port || 80);
  if (port > 65535) throw new Error('Invalid local API port');
  return value;
}
