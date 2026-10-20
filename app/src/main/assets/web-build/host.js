// Runs esbuild for the app. The app injects `whittleBuild` (a WebMessageListener limited to this page's
// origin) and talks over it in JSON:
//   page -> app  { type: "ready" } once esbuild is loaded, or { type: "failed", message }
//   app -> page  { type: "build", id, request, routes: { source, resolve, remote } }
//   page -> app  { type: "result", id, result }
// The routes are URL prefixes on this page's own origin that the app answers: workspace files, the CDN
// address for a package, and CDN modules through the app's cache. This page can't reach anything else.
import * as esbuild from './esbuild/browser.min.js';
import { bundle } from './bundle.js';

const channel = window.whittleBuild;

function send(message) {
  channel.postMessage(JSON.stringify(message));
}

async function get(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error((await response.text()) || `${response.status} ${url}`);
  return response;
}

function io(routes) {
  const bytes = async (url) => new Uint8Array(await (await get(url)).arrayBuffer());
  return {
    readSource: (path) => bytes(routes.source + path.split('/').map(encodeURIComponent).join('/')),
    resolvePackage: async (specifier) => (await get(routes.resolve + encodeURIComponent(specifier))).text(),
    fetchRemote: (url) => bytes(routes.remote + encodeURIComponent(url)),
  };
}

channel.onmessage = async (event) => {
  const message = JSON.parse(event.data);
  if (message.type !== 'build') return;
  const result = await bundle(esbuild, message.request, io(message.routes));
  send({ type: 'result', id: message.id, result });
};

try {
  await esbuild.initialize({ wasmURL: new URL('./esbuild/esbuild.wasm', import.meta.url).href, worker: false });
  send({ type: 'ready' });
} catch (error) {
  send({ type: 'failed', message: String(error && error.message || error) });
}
