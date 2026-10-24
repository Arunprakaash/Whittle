#!/usr/bin/env node
// Builds the landing page into site/dist: fills src/index.html from site.json, the app's beaver sprite and the
// repository's published GitHub Releases. Needs Node 18 or later and no packages. GITHUB_TOKEN, when set, is
// used for the Releases API so CI isn't rate limited.
//
//   node site/build.mjs

import { createHash } from 'node:crypto';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';

const siteDir = new URL('./', import.meta.url);
const srcDir = new URL('src/', siteDir);
const distDir = new URL('dist/', siteDir);

const readJson = async (url) => JSON.parse(await readFile(url, 'utf8'));
const config = await readJson(new URL('site.json', siteDir));
const beaver = await readJson(new URL(config.sprites.beaver, siteDir));
const chai = await readJson(new URL(config.sprites.chai, siteDir));

const repoUrl = `https://github.com/${config.repository}`;
const escapeHtml = (text) => String(text).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

// ---- Releases --------------------------------------------------------------------------------------------

const fetchReleases = async () => {
  const headers = { Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  const response = await fetch(`https://api.github.com/repos/${config.repository}/releases?per_page=30`, { headers });
  if (!response.ok) throw new Error(`GitHub Releases answered ${response.status}: ${await response.text()}`);
  const releases = (await response.json())
    .filter((release) => !release.draft && !release.prerelease)
    .sort((a, b) => b.published_at.localeCompare(a.published_at));
  if (!releases.length) throw new Error(`${config.repository} has no published releases`);
  return releases;
};

/** Markdown inline marks down to plain text. */
const plain = (markdown) => markdown
  .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
  .replace(/`([^`]+)`/g, '$1')
  .replace(/\*\*([^*]+)\*\*/g, '$1')
  .replace(/(^|\W)_([^_]+)_(?=\W|$)/g, '$1$2')
  .trim();

const firstSentence = (text) => text.match(/^.*?[.!?](?=\s|$)/)?.[0] ?? text;

/**
 * The top-level items of a release's "What's new" section: each "- **Lead.** More text" line becomes a lead and
 * the first sentence after it. Nested lines and other sections are left to the full notes on GitHub.
 */
const notesOf = (body) => {
  const section = body.replace(/\r/g, '').split(/^## /m).find((part) => /^what's new/i.test(part)) ?? body;
  return section.split('\n')
    .map((line) => line.match(/^- \*\*(.+?)\*\*(.*)$/))
    .filter(Boolean)
    .map(([, lead, rest]) => ({ lead: plain(lead), text: firstSentence(plain(rest)) }));
};

const dateFormat = new Intl.DateTimeFormat(config.releases.dateLocale, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
const versionOf = (release) => release.tag_name.replace(/^v/, '');

/** Joins a lead to its text: no space when the text carries on the lead's sentence ("Lead, more…"). */
const leadAndText = ({ lead, text }) => `<b>${escapeHtml(lead)}</b>${/^[,;:]/.test(text) ? '' : ' '}${escapeHtml(text)}`;

const renderReleases = (releases) => {
  const [latest, ...earlier] = releases;
  const items = notesOf(latest.body ?? '').slice(0, config.releases.latestItems);
  const latestHtml = `
          <a class="entry entry-latest" href="${escapeHtml(latest.html_url)}">
            <div class="entry-head"><span class="entry-v">${escapeHtml(versionOf(latest))}</span><span class="badge">latest</span><span class="entry-d">${dateFormat.format(new Date(latest.published_at))}</span></div>
            <ul>${items.map((item) => `<li><span>${leadAndText(item)}</span></li>`).join('')}</ul>
          </a>`;
  const earlierHtml = earlier.slice(0, config.releases.earlier).map((release) => {
    const summary = notesOf(release.body ?? '').slice(0, config.releases.summaryLeads).map(({ lead }) => lead.replace(/[.!]$/, '')).join(' · ');
    return `
          <a class="entry" href="${escapeHtml(release.html_url)}">
            <div class="entry-head"><span class="entry-v">${escapeHtml(versionOf(release))}</span><span class="entry-d">${dateFormat.format(new Date(release.published_at))}</span></div>
            <div class="entry-summary">${escapeHtml(summary)}</div>
          </a>`;
  }).join('');
  return latestHtml + earlierHtml;
};

// ---- Sprites ---------------------------------------------------------------------------------------------

/** A sprite frame as pixel-art SVG, merging runs of one colour like the app does. */
const frameSvg = (sprite, rows) => {
  const rects = [];
  rows.forEach((row, y) => {
    for (let x = 0; x < row.length;) {
      let end = x + 1;
      while (end < row.length && row[end] === row[x]) end++;
      if (row[x] !== '.') rects.push(`<rect x="${x}" y="${y}" width="${end - x}" height="1" fill="${sprite.palette[row[x]]}"/>`);
      x = end;
    }
  });
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${sprite.width} ${sprite.height}" shape-rendering="crispEdges">${rects.join('')}</svg>\n`;
};

/** Only what site.js plays: the palette, pivot, crossfade, the poses and the frames they use. */
const playable = (sprite) => {
  const used = new Set(Object.values(sprite.poses).flatMap((pose) => [...(pose.steps ?? []).map((step) => step.frame), pose.bite?.rest, pose.bite?.bite]).filter(Boolean));
  return {
    width: sprite.width,
    height: sprite.height,
    pivot: sprite.pivot,
    crossfadeMillis: sprite.crossfadeMillis,
    palette: sprite.palette,
    poses: sprite.poses,
    frames: Object.fromEntries(Object.entries(sprite.frames).filter(([name]) => used.has(name))),
  };
};

// ---- Page ------------------------------------------------------------------------------------------------

const choresHtml = config.chores.items.map((chore) => `
          <li><button type="button" class="chore" data-chore aria-pressed="false">
            <span class="box" aria-hidden="true"><span class="ms">check</span></span>
            <span class="chore-body">
              <span class="chore-text"><span class="strike">${escapeHtml(chore.text)}</span></span>
              <span class="chore-steps" data-steps></span>
              <span class="receipt" data-receipt hidden>→ ${escapeHtml(chore.steps.at(-1))}</span>
            </span>
          </button></li>`).join('');

const sourceHtml = ([icon, name]) => `<span class="src"><span class="ms">${escapeHtml(icon)}</span>${escapeHtml(name)}</span>`;
const terminalHtml = config.terminal.map(([kind, text]) => `<div class="${escapeHtml(kind)}">${escapeHtml(text)}</div>`).join('');
const modelsHtml = config.models.map((model, index) => `<button type="button" class="model" role="radio" aria-checked="${index === 0}" data-model><span class="radio" aria-hidden="true"></span>${escapeHtml(model.name)}<small>${escapeHtml(model.detail)}</small></button>`).join('');

/** Material Symbols trimmed to the icons the page uses, in the page itself or set by site.js. */
const fontLinks = (html) => {
  const icons = new Set([...html.matchAll(/class="ms[^"]*"[^>]*>([a-z0-9_]+)</g)].map((match) => match[1]));
  ['check_circle', 'progress_activity'].forEach((icon) => icons.add(icon));
  config.questions.forEach((question) => question.sources.forEach(([icon]) => icons.add(icon)));
  const symbols = `https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@20..48,400,0..1,0&icon_names=${[...icons].sort().join(',')}&display=block`;
  const text = 'https://fonts.googleapis.com/css2?family=Figtree:wght@400;500;600;700;800;900&family=Geist+Mono:wght@400;500&display=swap';
  return `<link rel="stylesheet" href="${escapeHtml(text)}">\n<link rel="stylesheet" href="${escapeHtml(symbols)}">`;
};

const fill = (template, raw, text) => {
  const html = template
    .replace(/\{\{\{(\w+)\}\}\}/g, (_, key) => { if (!(key in raw)) throw new Error(`No value for {{{${key}}}}`); return raw[key]; })
    .replace(/\{\{(\w+)\}\}/g, (_, key) => { if (!(key in text)) throw new Error(`No value for {{${key}}}`); return escapeHtml(text[key]); });
  return html;
};

const version = (content) => createHash('sha256').update(content).digest('hex').slice(0, 10);

const releases = await fetchReleases();
const latest = releases[0];
const apk = latest.assets.find((asset) => new RegExp(config.releases.apkPattern).test(asset.name));
const [styles, script, template] = await Promise.all(['styles.css', 'site.js', 'index.html'].map((name) => readFile(new URL(name, srcDir), 'utf8')));
const firstQuestion = config.questions[0];

const raw = {
  choresHtml,
  firstSources: firstQuestion.sources.map(sourceHtml).join(''),
  terminalHtml,
  modelsHtml,
  releasesHtml: renderReleases(releases),
  // Kept safe inside <script>: no "</script>" can close it early.
  clientConfig: JSON.stringify({
    sprites: { beaver: playable(beaver), chai: { width: chai.width, height: chai.height, steamMillis: chai.steamMillis, palette: chai.palette, frames: chai.frames } },
    chores: config.chores.items,
    questions: config.questions,
    terminal: config.terminal,
  }).replace(/</g, '\\u003c'),
  fontLinks: '',
};
const text = {
  url: config.url,
  repoUrl,
  releasesUrl: `${repoUrl}/releases`,
  issuesUrl: `${repoUrl}/issues`,
  downloadUrl: apk?.browser_download_url ?? `${repoUrl}/releases/latest`,
  apkName: apk?.name ?? `Whittle ${versionOf(latest)}`,
  apkDate: dateFormat.format(new Date(latest.published_at)),
  version: versionOf(latest),
  requirements: config.requirements,
  choresTitle: config.chores.title,
  choresLeft: `${config.chores.items.length} to go`,
  firstQuestion: firstQuestion.q,
  firstAnswer: firstQuestion.answer,
  stylesVersion: version(styles),
  scriptVersion: version(script),
};
const draft = fill(template, raw, text);
const page = fill(template, { ...raw, fontLinks: fontLinks(draft) }, text);

await rm(distDir, { recursive: true, force: true });
await mkdir(distDir, { recursive: true });
await Promise.all([
  writeFile(new URL('index.html', distDir), page),
  writeFile(new URL('styles.css', distDir), styles),
  writeFile(new URL('site.js', distDir), script),
  writeFile(new URL('beaver.svg', distDir), frameSvg(beaver, beaver.frames.idle)),
  writeFile(new URL('chai.svg', distDir), frameSvg(chai, Object.values(chai.frames)[0])),
  // GitHub Pages serves the files as they are.
  writeFile(new URL('.nojekyll', distDir), ''),
]);
console.log(`Built site/dist for ${latest.tag_name} with ${Math.min(releases.length, config.releases.earlier + 1)} releases.`);
