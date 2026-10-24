#!/usr/bin/env node
// Renders the landing page's film from film.html: every frame in headless Chromium, the synthesized soundtrack
// (soundtrack.mjs) mixed to web loudness, then the web versions (AV1 and H.264, 1080p and 720p), the sound on its own,
// a poster and film.json, which tells the site build what to offer. Everything lands in site/src/media.
//
//   cd site/film && npm install && node render.mjs           the film, its sound and poster
//   node render.mjs stills 4,13.5,36                         single frames into site/film/stills/, to check
//
// Needs ffmpeg on the PATH and a Chromium: set FILM_CHROME to its executable, or have Chrome installed.

import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { chromium } from 'playwright-core';
import { soundtrack } from './soundtrack.mjs';

const here = new URL('./', import.meta.url);
const media = new URL('../src/media/', here);
const [mode, times] = process.argv.slice(2);

/** When the poster is taken: the title card, with the wordmark and swoosh drawn. */
const POSTER_SECONDS = 13.6;
const POSTER = 'whittle-film-poster.jpg';
const SOUND = 'whittle-film-sound.m4a';

/**
 * The web versions, in the order the page offers them: the browser plays the first it can. Phones get 720p; AV1
 * comes first for browsers that play it, H.264 after for the rest. Flat motion graphics stay sharp at these settings.
 */
const VERSIONS = [
  { codec: 'av1', height: 720, quality: 44, media: '(max-width: 700px)' },
  { codec: 'h264', height: 720, quality: 29, level: '3.1', media: '(max-width: 700px)' },
  { codec: 'av1', height: 1080, quality: 46 },
  { codec: 'h264', height: 1080, quality: 30, level: '4.0' },
];
const ENCODERS = {
  av1: ({ quality }) => ['-c:v', 'libsvtav1', '-preset', '5', '-crf', String(quality), '-svtav1-params', 'tune=0'],
  // The level is set, not guessed, so phones that check it agree to play the file.
  h264: ({ quality, level }) => ['-c:v', 'libx264', '-preset', 'slower', '-crf', String(quality), '-tune', 'animation', '-profile:v', 'high', '-level:v', level],
};
/** A keyframe this often, so the film starts and loops without waiting long. */
const KEYFRAME_SECONDS = 8;

/** The RFC 6381 codecs string for a file's video, for the page's <source type>. */
const codecsOf = async (file) => {
  const probe = await new Promise((resolve, reject) => {
    const ffprobe = spawn('ffprobe', ['-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=codec_name,profile,level', '-of', 'json', file]);
    let out = '';
    ffprobe.stdout.on('data', (chunk) => { out += chunk; });
    ffprobe.on('close', (code) => (code === 0 ? resolve(JSON.parse(out).streams[0]) : reject(new Error('ffprobe failed'))));
  });
  if (probe.codec_name === 'h264') return `avc1.6400${probe.level.toString(16).padStart(2, '0').toUpperCase()}`;
  if (probe.codec_name === 'av1') return `av01.0.${String(probe.level).padStart(2, '0')}M.08`;
  throw new Error(`no codecs string for ${probe.codec_name}`);
};

const sprites = {
  beaver: JSON.parse(await readFile(new URL('../../docs/design/sprites/beaver.sprite.json', here), 'utf8')),
  chai: JSON.parse(await readFile(new URL('../src/chai.sprite.json', here), 'utf8')),
};
const work = await mkdtemp(join(tmpdir(), 'whittle-film-'));
const page_ = join(work, 'film.html');
const html = (await readFile(new URL('film.html', here), 'utf8')).replace('/*SPRITES*/', JSON.stringify(sprites).replace(/</g, '\\u003c'));
await writeFile(page_, html);

const browser = await chromium.launch(process.env.FILM_CHROME ? { executablePath: process.env.FILM_CHROME } : { channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
page.on('pageerror', (error) => { throw error; });
await page.goto(`file://${page_}`);
await page.waitForFunction(() => window.filmReady === true, null, { timeout: 30000 });
const { FPS, DURATION } = await page.evaluate(() => window.FILM);
const frameAt = async (seconds, options) => {
  await page.evaluate((t) => window.renderAt(t), seconds);
  return page.screenshot(options);
};

const run = (args, input) => new Promise((resolve, reject) => {
  const ffmpeg = spawn('ffmpeg', ['-y', '-loglevel', 'error', ...args], { stdio: [input ? 'pipe' : 'ignore', 'inherit', 'inherit'] });
  ffmpeg.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`ffmpeg exited ${code}`))));
  if (input) input(ffmpeg.stdin);
});

if (mode === 'stills') {
  const out = new URL('stills/', here);
  await mkdir(out, { recursive: true });
  for (const t of times.split(',').map(Number)) await frameAt(t, { path: new URL(`${t}.png`, out).pathname });
} else {
  await mkdir(media, { recursive: true });
  // A near-lossless master first; every web version is encoded from it.
  const master = join(work, 'master.mp4');
  const frames = Math.round(FPS * DURATION);
  await run(['-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'mjpeg', '-i', '-', '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '8', '-pix_fmt', 'yuv420p', master], async (stdin) => {
    for (let i = 0; i < frames; i++) {
      const jpeg = await frameAt(i / FPS, { type: 'jpeg', quality: 96 });
      if (!stdin.write(jpeg)) await new Promise((resolve) => stdin.once('drain', resolve));
      if (i % (FPS * 10) === 0) console.log(`frame ${i}/${frames}`);
    }
    stdin.end();
  });
  await frameAt(POSTER_SECONDS, { path: new URL(POSTER, media).pathname, type: 'jpeg', quality: 82 });
  await browser.close();

  const sources = [];
  for (const version of VERSIONS) {
    const name = `whittle-film-${version.height}.${version.codec}.mp4`;
    const out = new URL(name, media).pathname;
    console.log(`encoding ${name}`);
    await run(['-i', master, '-an', '-vf', `scale=-2:${version.height}:flags=lanczos`, ...ENCODERS[version.codec](version),
      '-g', String(KEYFRAME_SECONDS * FPS), '-pix_fmt', 'yuv420p', '-movflags', '+faststart', out]);
    sources.push({ src: `media/${name}`, type: `video/mp4; codecs="${await codecsOf(out)}"`, media: version.media ?? null });
  }
  // The sound is its own file, fetched only when someone turns it on: most visitors watch muted.
  const wav = join(work, 'soundtrack.wav');
  await writeFile(wav, soundtrack(DURATION));
  await run(['-i', wav, '-af', 'loudnorm=I=-16:TP=-1.5:LRA=11', '-ar', '48000', '-c:a', 'aac', '-b:a', '64k', '-movflags', '+faststart', new URL(SOUND, media).pathname]);

  await writeFile(new URL('film.json', media), `${JSON.stringify({ poster: `media/${POSTER}`, sound: `media/${SOUND}`, sources }, null, 2)}\n`);
  console.log('wrote site/src/media: the film versions, its sound, poster and film.json');
}
if (mode === 'stills') await browser.close();
await rm(work, { recursive: true, force: true });
