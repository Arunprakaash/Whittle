// Bundles a project from a chat's workspace with esbuild. Everything outside esbuild (reading the workspace,
// mapping npm packages to the package CDN, fetching CDN modules) comes through `io`, so this file runs the same
// in Whittle's hidden WebView and in tests:
//   io.readSource(path)        -> Uint8Array of a workspace file
//   io.resolvePackage(spec)    -> the CDN URL for a bare import such as "react-dom/client"
//   io.fetchRemote(url)        -> Uint8Array of a module on an allowed host
//
// request: {
//   entryPoints: [{ in: "src/main.tsx", out: "main" }],
//   files: ["src/main.tsx", ...],            every workspace file the build may import
//   resolveExtensions: [".tsx", ".ts", ...], tried in order for extensionless imports
//   loaders: { ".tsx": "tsx", ".png": "dataurl", ... },
//   options: { target, format, jsx, minify, ... } passed to esbuild as is
//   maxMessages: 20
// }
// returns { ok, files: [{ path, base64 }], errors: [string], warnings: [string] }

const WORKSPACE = 'workspace';
const REMOTE = 'remote';
const OUT_DIR = '/out';
const REMOTE_URL = /^https?:\/\//i;
const DEFAULT_LOADER = 'js';
const STYLESHEET_REFERENCES = new Set(['import-rule', 'url-token']);

export async function bundle(esbuild, request, io) {
  const files = new Set(request.files);
  const loaders = request.loaders;
  const plugin = {
    name: 'whittle-workspace',
    setup(build) {
      build.onResolve({ filter: /.*/ }, async (args) => {
        // Stylesheets and fonts a stylesheet links to on the web (Google Fonts, say) stay links the page loads.
        if (REMOTE_URL.test(args.path) && STYLESHEET_REFERENCES.has(args.kind)) return { path: args.path, external: true };
        if (REMOTE_URL.test(args.path)) return { path: args.path, namespace: REMOTE };
        if (args.namespace === REMOTE) {
          if (isRelative(args.path)) return { path: new URL(args.path, args.importer).href, namespace: REMOTE };
          return { path: await io.resolvePackage(args.path), namespace: REMOTE };
        }
        if (isRelative(args.path) || args.kind === 'entry-point') {
          const from = args.kind === 'entry-point' ? '' : directoryOf(args.importer);
          const found = findLocal(files, join(from, args.path), request.resolveExtensions);
          if (found === null) return { errors: [{ text: `Can't find "${args.path}" in the workspace` }] };
          return { path: found, namespace: WORKSPACE };
        }
        return { path: await io.resolvePackage(args.path), namespace: REMOTE };
      });
      build.onLoad({ filter: /.*/, namespace: WORKSPACE }, async (args) => ({
        contents: await io.readSource(args.path),
        loader: loaderFor(loaders, args.path),
      }));
      build.onLoad({ filter: /.*/, namespace: REMOTE }, async (args) => ({
        contents: await io.fetchRemote(args.path),
        loader: loaderFor(loaders, new URL(args.path).pathname),
      }));
    },
  };

  try {
    const result = await esbuild.build({
      ...request.options,
      entryPoints: request.entryPoints,
      bundle: true,
      write: false,
      outdir: OUT_DIR,
      plugins: [plugin],
      logLevel: 'silent',
    });
    return {
      ok: true,
      files: result.outputFiles.map((file) => ({ path: file.path.slice(OUT_DIR.length + 1), base64: toBase64(file.contents) })),
      errors: [],
      warnings: describe(result.warnings, request.maxMessages),
    };
  } catch (error) {
    return {
      ok: false,
      files: [],
      errors: error && Array.isArray(error.errors) ? describe(error.errors, request.maxMessages) : [String(error && error.message || error)],
      warnings: error && Array.isArray(error.warnings) ? describe(error.warnings, request.maxMessages) : [],
    };
  }
}

function isRelative(path) {
  return path.startsWith('./') || path.startsWith('../') || path.startsWith('/') || path === '.' || path === '..';
}

function directoryOf(path) {
  const slash = path.lastIndexOf('/');
  return slash < 0 ? '' : path.slice(0, slash);
}

/** Workspace paths have no leading slash; "/x" means the workspace root. Never climbs above the root. */
function join(from, path) {
  const parts = path.startsWith('/') ? [] : from.split('/').filter(Boolean);
  for (const part of path.split('/')) {
    if (part === '' || part === '.') continue;
    if (part === '..') parts.pop();
    else parts.push(part);
  }
  return parts.join('/');
}

/** Like a bundler: the exact file, then with each extension, then as a folder's index; TypeScript's "./x.js" means x.ts. */
function findLocal(files, path, extensions) {
  if (files.has(path)) return path;
  for (const extension of extensions) if (files.has(path + extension)) return path + extension;
  for (const extension of extensions) if (files.has(`${path}/index${extension}`)) return `${path}/index${extension}`;
  const script = path.match(/^(.*)\.(m?js|jsx)$/);
  if (script) {
    for (const extension of ['.ts', '.tsx', '.mts']) if (files.has(script[1] + extension)) return script[1] + extension;
  }
  return null;
}

function loaderFor(loaders, path) {
  const name = path.slice(path.lastIndexOf('/') + 1);
  const dot = name.lastIndexOf('.');
  return dot < 0 ? DEFAULT_LOADER : loaders[name.slice(dot).toLowerCase()] || DEFAULT_LOADER;
}

/**
 * One line per message: "src/App.tsx:3:9: text". A plugin error (a missing package, a failed download) is placed
 * at the import that asked for it rather than inside esbuild.
 */
function describe(messages, limit) {
  const lines = messages.slice(0, limit).map((message) => {
    const located = message.pluginName ? [...(message.notes || [])].reverse().find((note) => note.location) : message;
    const where = located && located.location ? `${displayPath(located.location.file)}:${located.location.line}:${located.location.column}: ` : '';
    const notes = message.pluginName ? [] : (message.notes || []).map((note) => note.text).filter(Boolean);
    return where + message.text + (notes.length ? ` (${notes.join(' ')})` : '');
  });
  if (messages.length > limit) lines.push(`…and ${messages.length - limit} more`);
  return lines;
}

function displayPath(file) {
  for (const namespace of [WORKSPACE, REMOTE]) if (file.startsWith(`${namespace}:`)) return file.slice(namespace.length + 1);
  return file;
}

function toBase64(bytes) {
  const chunk = 0x8000;
  let binary = '';
  for (let i = 0; i < bytes.length; i += chunk) binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
  return btoa(binary);
}
